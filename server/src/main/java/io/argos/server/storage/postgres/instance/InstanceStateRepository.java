/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.instance;

import io.argos.server.session.InstanceStateStore;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link InstanceStateStore} over PostgreSQL (V001): the state columns of {@code instances} ({@code
 * status}, {@code warn_flags}, {@code clock_offset_ms}, {@code last_seen_at}; ADR-0010 C-128 gives
 * them to WO-22) and the {@code events} rows of transitions, {@code AgentBye} and the server's
 * start and stop. Called only from the state writer thread.
 *
 * <p>A state row of an instance that is up again ({@code UP}, {@code WARN}) also clears {@code
 * archived_at}: an archived instance that reconnects is listed again (12 §4.3).
 */
@Repository
public class InstanceStateRepository implements InstanceStateStore {

  /** Seconds a statement may take (the writer thread retries later). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String UPDATE_STATE =
      "UPDATE argos.instances SET status = ?, warn_flags = ?,"
          + " clock_offset_ms = COALESCE(?, clock_offset_ms),"
          + " last_seen_at = COALESCE(?, last_seen_at),"
          + " archived_at = CASE WHEN ? THEN NULL ELSE archived_at END WHERE id = ?";

  private static final String ARCHIVE =
      "UPDATE argos.instances SET status = 'DOWN', warn_flags = NULL, archived_at = ? WHERE id = ?";

  private static final String INSERT_EVENT =
      "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, ts)"
          + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT (id) DO NOTHING";

  private static final String OPEN =
      "SELECT i.id, i.app_id, a.env, i.status, i.warn_flags, i.clock_offset_ms"
          + " FROM argos.instances i JOIN argos.apps a ON a.id = i.app_id"
          + " WHERE i.archived_at IS NULL AND i.status <> 'DOWN'";

  private static final String COUNT_ACTIVE =
      "SELECT count(*) FROM argos.instances WHERE archived_at IS NULL";

  private final JdbcTemplate jdbc;

  public InstanceStateRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  @Override
  public void writeStates(List<StateRow> rows) {
    if (rows.isEmpty()) {
      return;
    }
    jdbc.execute(
        (Connection con) -> {
          try (PreparedStatement ps = con.prepareStatement(UPDATE_STATE)) {
            for (StateRow r : rows) {
              ps.setString(1, r.status());
              if (r.warnFlags().isEmpty()) {
                ps.setNull(2, Types.ARRAY);
              } else {
                Array flags = con.createArrayOf("text", r.warnFlags().toArray(new String[0]));
                ps.setArray(2, flags);
              }
              if (r.clockOffsetMs() == null) {
                ps.setNull(3, Types.INTEGER);
              } else {
                ps.setInt(3, r.clockOffsetMs());
              }
              if (r.lastSeenAt() == null) {
                ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
              } else {
                ps.setTimestamp(4, Timestamp.from(r.lastSeenAt()));
              }
              ps.setBoolean(5, r.status().equals("UP") || r.status().equals("WARN"));
              ps.setInt(6, r.instanceId());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        });
  }

  @Override
  public void archiveInstances(Map<Integer, Instant> archivedAt) {
    if (archivedAt.isEmpty()) {
      return;
    }
    List<Map.Entry<Integer, Instant>> entries = List.copyOf(archivedAt.entrySet());
    jdbc.batchUpdate(
        ARCHIVE,
        entries,
        entries.size(),
        (PreparedStatement ps, Map.Entry<Integer, Instant> e) -> {
          ps.setTimestamp(1, Timestamp.from(e.getValue()));
          ps.setInt(2, e.getKey());
        });
  }

  @Override
  public void writeEvents(List<EventRow> events) {
    if (events.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        INSERT_EVENT,
        events,
        events.size(),
        (PreparedStatement ps, EventRow e) -> {
          ps.setObject(1, e.id());
          setInt(ps, 2, e.appId());
          setText(ps, 3, e.env());
          setInt(ps, 4, e.instanceId());
          ps.setString(5, e.type());
          setText(ps, 6, e.title());
          setText(ps, 7, e.detailJson());
          ps.setTimestamp(8, Timestamp.from(e.ts()));
        });
  }

  @Override
  public List<OpenInstance> openInstances() {
    return jdbc.query(
        OPEN,
        (rs, row) -> {
          Array flags = rs.getArray(5);
          List<String> warn =
              flags == null ? List.of() : Arrays.asList((String[]) flags.getArray());
          int offset = rs.getInt(6);
          Integer clock = rs.wasNull() ? null : offset;
          return new OpenInstance(
              rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), warn, clock);
        });
  }

  @Override
  public int countActiveInstances() {
    Integer count = jdbc.queryForObject(COUNT_ACTIVE, Integer.class);
    return count == null ? 0 : count;
  }

  private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
  }

  private static void setText(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
