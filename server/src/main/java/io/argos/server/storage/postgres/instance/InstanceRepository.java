/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.instance;

import io.argos.server.session.InstanceChanges;
import io.argos.server.session.InstanceMetadata;
import io.argos.server.session.SessionStore;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code instances} (PostgreSQL V001, 12 §4.3) for the collector's session start and {@code
 * InstanceUpdate} (11 §5, ADR-0010 C-128, C-146): identity and metadata columns only. The state
 * columns ({@code status}, {@code warn_flags}, {@code clock_offset_ms}, {@code last_seen_at},
 * {@code archived_at}) are WO-22's and never written here.
 *
 * <p>Idempotent writes: an update writes the row only when a value differs ({@code IS DISTINCT
 * FROM}), so the same metadata twice changes the row once (C-146).
 *
 * <p>Metadata text is cut to the V001 column sizes, which are narrower than some 11 §4.2 caps
 * ({@code host} 255, {@code jvm_*}, {@code os} 64, {@code agent_version} 32, {@code config_hash}
 * 16). The identity column {@code name} (128 against 2 KiB) is never merged by cutting: a longer
 * name is stored as {@link SessionStore#storedInstanceName} (ADR-0010 C-157 (4)).
 */
@Repository
public class InstanceRepository {

  /** Seconds a statement may take (the Hello path waits on it). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String FIND = "SELECT id FROM argos.instances WHERE app_id = ? AND name = ?";

  private static final String STARTED_AT = "SELECT started_at FROM argos.instances WHERE id = ?";

  private static final String COUNT =
      "SELECT count(*) FROM argos.instances WHERE app_id = ? AND archived_at IS NULL";

  private static final String COUNT_SINCE =
      "SELECT count(*) FROM argos.instances WHERE app_id = ? AND first_seen_at >= ?";

  private static final String META_COLUMNS =
      "host, pid, jvm_version, jvm_vendor, os, cgroup, virtual_threads, agent_version,"
          + " protocol_version, version_tag, ip_mode, user_id_mode, query_string_mode,"
          + " sql_param_mode, config_hash, remote_config_allow, started_at";

  /** The 17 metadata values with explicit types (one binding each). */
  private static final String META_VALUES =
      "CAST(? AS varchar(255)) AS host, CAST(? AS int) AS pid,"
          + " CAST(? AS varchar(64)) AS jvm_version, CAST(? AS varchar(64)) AS jvm_vendor,"
          + " CAST(? AS varchar(64)) AS os, CAST(? AS varchar(8)) AS cgroup,"
          + " CAST(? AS boolean) AS virtual_threads, CAST(? AS varchar(32)) AS agent_version,"
          + " CAST(? AS int) AS protocol_version, CAST(? AS varchar(128)) AS version_tag,"
          + " CAST(? AS varchar(8)) AS ip_mode, CAST(? AS varchar(8)) AS user_id_mode,"
          + " CAST(? AS varchar(10)) AS query_string_mode, CAST(? AS varchar(8)) AS sql_param_mode,"
          + " CAST(? AS char(16)) AS config_hash, CAST(? AS text[]) AS remote_config_allow,"
          + " CAST(? AS timestamptz) AS started_at";

  private static final String INSERT =
      "INSERT INTO argos.instances (app_id, name, first_seen_at, "
          + META_COLUMNS
          + ") SELECT ?, ?, ?, v.* FROM (SELECT "
          + META_VALUES
          + ") v ON CONFLICT (app_id, name) DO NOTHING RETURNING id";

  private static final String UPDATE_META =
      "UPDATE argos.instances i SET host = v.host, pid = v.pid, jvm_version = v.jvm_version,"
          + " jvm_vendor = v.jvm_vendor, os = v.os, cgroup = v.cgroup,"
          + " virtual_threads = v.virtual_threads, agent_version = v.agent_version,"
          + " protocol_version = v.protocol_version, version_tag = v.version_tag,"
          + " ip_mode = v.ip_mode, user_id_mode = v.user_id_mode,"
          + " query_string_mode = v.query_string_mode, sql_param_mode = v.sql_param_mode,"
          + " config_hash = v.config_hash, remote_config_allow = v.remote_config_allow,"
          + " started_at = v.started_at FROM (SELECT "
          + META_VALUES
          + ") v WHERE i.id = ? AND (i.host, i.pid, i.jvm_version, i.jvm_vendor, i.os, i.cgroup,"
          + " i.virtual_threads, i.agent_version, i.protocol_version, i.version_tag, i.ip_mode,"
          + " i.user_id_mode, i.query_string_mode, i.sql_param_mode, i.config_hash,"
          + " i.remote_config_allow, i.started_at) IS DISTINCT FROM (v.host, v.pid,"
          + " v.jvm_version, v.jvm_vendor, v.os, v.cgroup, v.virtual_threads, v.agent_version,"
          + " v.protocol_version, v.version_tag, v.ip_mode, v.user_id_mode, v.query_string_mode,"
          + " v.sql_param_mode, v.config_hash, v.remote_config_allow, v.started_at)";

  private static final String APPLY_UPDATE =
      "UPDATE argos.instances i SET name = COALESCE(v.name, i.name),"
          + " virtual_threads = COALESCE(v.virtual_threads, i.virtual_threads),"
          + " version_tag = COALESCE(v.version_tag, i.version_tag),"
          + " config_hash = COALESCE(v.config_hash, i.config_hash)"
          + " FROM (SELECT CAST(? AS varchar(128)) AS name, CAST(? AS boolean) AS virtual_threads,"
          + " CAST(? AS varchar(128)) AS version_tag, CAST(? AS char(16)) AS config_hash) v"
          + " WHERE i.id = ? AND ((v.name IS NOT NULL AND v.name IS DISTINCT FROM i.name)"
          + " OR (v.virtual_threads IS NOT NULL AND v.virtual_threads IS DISTINCT FROM"
          + " i.virtual_threads)"
          + " OR (v.version_tag IS NOT NULL AND v.version_tag IS DISTINCT FROM i.version_tag)"
          + " OR (v.config_hash IS NOT NULL AND v.config_hash IS DISTINCT FROM i.config_hash))";

  private final JdbcTemplate jdbc;

  public InstanceRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** The id of ({@code appId}, {@code name}); {@code name} is taken in its stored form. */
  public OptionalInt find(int appId, String name) {
    List<Integer> ids =
        jdbc.query(FIND, (rs, row) -> rs.getInt(1), appId, SessionStore.storedInstanceName(name));
    return ids.isEmpty() ? OptionalInt.empty() : OptionalInt.of(ids.get(0));
  }

  /**
   * {@code started_at} of instance {@code id} in epoch milliseconds, empty when NULL or no row
   * (ADR-0010 C-240 (1): the new-process rule of the instance states).
   */
  public OptionalLong startedAtMs(int id) {
    List<Timestamp> values = jdbc.query(STARTED_AT, (rs, row) -> rs.getTimestamp(1), id);
    if (values.isEmpty() || values.get(0) == null) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(values.get(0).toInstant().toEpochMilli());
  }

  /** Instances of {@code appId} that are not archived. */
  public int countActive(int appId) {
    Integer count = jdbc.queryForObject(COUNT, Integer.class, appId);
    return count == null ? 0 : count;
  }

  /** Instances of {@code appId} first seen at or after {@code since}. */
  public int countCreatedSince(int appId, Instant since) {
    Integer count = jdbc.queryForObject(COUNT_SINCE, Integer.class, appId, Timestamp.from(since));
    return count == null ? 0 : count;
  }

  /**
   * Inserts ({@code appId}, {@code name}) first seen at {@code now} unless it exists.
   *
   * @return the id of the new row, empty when the row already existed
   */
  public OptionalInt insert(int appId, String name, InstanceMetadata metadata, Instant now) {
    List<Integer> ids =
        jdbc.query(
            con -> {
              PreparedStatement ps = con.prepareStatement(INSERT);
              ps.setInt(1, appId);
              ps.setString(2, SessionStore.storedInstanceName(name));
              ps.setTimestamp(3, Timestamp.from(now));
              bindMetadata(con, ps, 4, metadata);
              return ps;
            },
            (rs, row) -> rs.getInt(1));
    return ids.isEmpty() ? OptionalInt.empty() : OptionalInt.of(ids.get(0));
  }

  /**
   * Writes {@code metadata} to instance {@code id} when a value differs.
   *
   * @return whether the row was written
   */
  public boolean updateMetadata(int id, InstanceMetadata metadata) {
    return jdbc.update(
            con -> {
              PreparedStatement ps = con.prepareStatement(UPDATE_META);
              int next = bindMetadata(con, ps, 1, metadata);
              ps.setInt(next, id);
              return ps;
            })
        > 0;
  }

  /**
   * Applies the set fields of {@code changes} to instance {@code id} when a value differs.
   *
   * @return whether the row was written
   * @throws DuplicateKeyException when another instance of the app has the new name
   */
  public boolean applyUpdate(int id, InstanceChanges changes) {
    return jdbc.update(
            con -> {
              PreparedStatement ps = con.prepareStatement(APPLY_UPDATE);
              setText(ps, 1, SessionStore.storedInstanceName(changes.instanceName()));
              if (changes.virtualThreads() == null) {
                ps.setNull(2, Types.BOOLEAN);
              } else {
                ps.setBoolean(2, changes.virtualThreads());
              }
              setText(ps, 3, cut(changes.appVersion(), 128));
              setText(ps, 4, cut(changes.configHash(), 16));
              ps.setInt(5, id);
              return ps;
            })
        > 0;
  }

  private static int bindMetadata(
      Connection con, PreparedStatement ps, int first, InstanceMetadata m) throws SQLException {
    int i = first;
    setText(ps, i++, cut(m.host(), 255));
    setInt(ps, i++, m.pid());
    setText(ps, i++, cut(m.jvmVersion(), 64));
    setText(ps, i++, cut(m.jvmVendor(), 64));
    setText(ps, i++, cut(m.os(), 64));
    setText(ps, i++, cut(m.cgroup(), 8));
    if (m.virtualThreads() == null) {
      ps.setNull(i++, Types.BOOLEAN);
    } else {
      ps.setBoolean(i++, m.virtualThreads());
    }
    setText(ps, i++, cut(m.agentVersion(), 32));
    setInt(ps, i++, m.protocolVersion());
    setText(ps, i++, cut(m.versionTag(), 128));
    setText(ps, i++, cut(m.ipMode(), 8));
    setText(ps, i++, cut(m.userIdMode(), 8));
    setText(ps, i++, cut(m.queryStringMode(), 10));
    setText(ps, i++, cut(m.sqlParamMode(), 8));
    setText(ps, i++, cut(m.configHash(), 16));
    Array allow = con.createArrayOf("text", m.remoteConfigAllow().toArray(new String[0]));
    ps.setArray(i++, allow);
    if (m.startedAt() == null) {
      ps.setNull(i++, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      ps.setTimestamp(i++, Timestamp.from(m.startedAt()));
    }
    return i;
  }

  private static void setText(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
  }

  /**
   * {@code value} cut to at most {@code maxChars} characters (a surrogate pair is never split);
   * {@code null} stays {@code null}.
   */
  static String cut(String value, int maxChars) {
    return SessionStore.cut(value, maxChars);
  }
}
