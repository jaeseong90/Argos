/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.catalog;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The PostgreSQL rows the query API shows (14 §4.2, §4.3, §4.8, §4.12, §4.15): {@code apps}, {@code
 * instances}, {@code events} and {@code error_groups} (V001, V003), read as {@code argos_app}.
 *
 * <ul>
 *   <li>Values are JDBC parameters only; the statements are constants of this class (the optional
 *       filters choose between constant fragments).
 *   <li>Every list has a row limit chosen by the caller (14 §7.2) and a 5 s statement timeout.
 *   <li>A PostgreSQL failure is 503 {@code storage_unavailable}; the log line names the statement
 *       kind and the exception class, never a value.
 * </ul>
 *
 * The instance state is not read here (ADR-0010 C-128): the query API takes it from the server's
 * instance state machine ({@code api.apps.InstanceStates}). {@code events} rows are written by
 * others (state transitions by the instance state machine, deploy markers WO-26); this class only
 * reads them.
 */
@Component
public class CatalogRepository {

  /**
   * The statement timeout of every catalog query in seconds; handlers that read PostgreSQL only
   * declare it as their {@code timeout_s} limit.
   */
  public static final int STATEMENT_TIMEOUT_S = 5;

  private static final Logger LOG = LoggerFactory.getLogger(CatalogRepository.class);

  private static final String APP_COLUMNS =
      "a.id, a.name, a.env, a.app_group, a.apdex_t_ms, a.slow_threshold_ms, a.auto_register,"
          + " a.recommended_rules_applied, a.created_at, a.registered_from_app_id,"
          + " (SELECT count(*) FROM argos.instances i WHERE i.app_id = a.id"
          + " AND i.archived_at IS NULL) AS instances_total,"
          + " (SELECT max(i.last_seen_at) FROM argos.instances i WHERE i.app_id = a.id)"
          + " AS last_seen_at";

  private static final String INSTANCE_COLUMNS =
      "id, app_id, name, host, pid, jvm_version, jvm_vendor, os, cgroup, virtual_threads,"
          + " agent_version, protocol_version, version_tag, ip_mode, user_id_mode,"
          + " query_string_mode, sql_param_mode, config_hash, remote_config_allow,"
          + " clock_offset_ms, first_seen_at, last_seen_at, started_at, archived_at";

  private static final String EVENT_SELECT =
      "SELECT e.id, e.app_id, e.instance_id, e.type, e.title, e.detail::text, e.version, e.ts,"
          + " u.username FROM argos.events e LEFT JOIN argos.users u ON u.id = e.created_by"
          + " WHERE e.app_id = ? AND e.ts >= ? AND e.ts < ?";

  /** State transitions in the range, the last one of each instance before it, server stops. */
  private static final String STATE_EVENTS =
      "SELECT e.instance_id, e.ts, e.type, e.detail->>'to', e.detail->>'reason'"
          + " FROM argos.events e WHERE e.app_id = ? AND e.type = 'state'"
          + " AND e.instance_id IS NOT NULL AND e.ts >= ? AND e.ts < ?"
          + " UNION ALL"
          + " SELECT p.instance_id, p.ts, p.type, p.to_state, p.reason"
          + " FROM argos.instances i CROSS JOIN LATERAL ("
          + "SELECT e.instance_id, e.ts, e.type, e.detail->>'to' AS to_state,"
          + " e.detail->>'reason' AS reason FROM argos.events e WHERE e.app_id = i.app_id"
          + " AND e.instance_id = i.id AND e.type = 'state' AND e.ts < ?"
          + " ORDER BY e.ts DESC LIMIT 1) p WHERE i.app_id = ?"
          + " UNION ALL"
          + " SELECT NULL, e.ts, e.type, NULL, NULL FROM argos.events e"
          + " WHERE e.app_id IS NULL AND e.instance_id IS NULL AND e.type IN ('start', 'stop')"
          + " AND e.ts >= ? AND e.ts < ?"
          + " UNION ALL"
          + " SELECT * FROM (SELECT NULL::int, e.ts, e.type, NULL::text, NULL::text"
          + " FROM argos.events e WHERE e.app_id IS NULL AND e.instance_id IS NULL"
          + " AND e.type IN ('start', 'stop') AND e.ts < ? ORDER BY e.ts DESC LIMIT 1) s"
          + " LIMIT ?";

  private static final String LAST_DEPLOYS =
      "SELECT DISTINCT ON (app_id) app_id, ts, version FROM argos.events"
          + " WHERE type = 'deploy' AND app_id = ANY(?) ORDER BY app_id, ts DESC";

  private static final String ERROR_GROUPS =
      "SELECT group_id, error_class, message_pattern, top_frame, sample_stack, first_seen,"
          + " last_seen FROM argos.error_groups WHERE app_id = ? AND group_id = ANY(?)";

  private static final String NEW_ERROR_GROUPS =
      "SELECT group_id FROM argos.error_groups WHERE app_id = ? AND first_seen >= ?"
          + " AND first_seen < ? ORDER BY first_seen DESC, group_id LIMIT ?";

  /**
   * An application ({@code apps}) with its instance count.
   *
   * @param id {@code apps.id}, the ClickHouse {@code app_id}
   * @param name name (collected through the agent Hello for auto-registered apps)
   * @param env environment
   * @param group {@code app_group}, or null
   * @param apdexTMs Apdex T
   * @param slowThresholdMs the XLog slow threshold
   * @param autoRegister registration policy {@code auto} (true) or {@code approval}
   * @param recommendedRulesApplied whether the recommended alert rules were applied
   * @param createdAt creation time
   * @param registeredFromAppId the home app of an environment variant (C-155), or null
   * @param instancesTotal instances that are not archived
   * @param lastSeenAt the latest {@code last_seen_at} of its instances, or null
   */
  public record AppRecord(
      int id,
      String name,
      String env,
      String group,
      int apdexTMs,
      int slowThresholdMs,
      boolean autoRegister,
      boolean recommendedRulesApplied,
      Instant createdAt,
      Integer registeredFromAppId,
      int instancesTotal,
      Instant lastSeenAt) {}

  /**
   * The filters of the application list (14 §4.3 {@code GET /apps}).
   *
   * @param env exact environment, or null
   * @param group exact group, or null
   * @param nameContains a substring of the name (case-insensitive), or null
   */
  public record AppFilter(String env, String group, String nameContains) {}

  /**
   * An instance row ({@code instances}, V001): identity and metadata, no state (C-128).
   *
   * @param id {@code instances.id}, the ClickHouse {@code instance_id}
   * @param appId its application
   * @param name stored name (C-157 (4): long names are stored shortened)
   * @param host host name
   * @param pid process id
   * @param jvmVersion JVM version
   * @param jvmVendor JVM vendor
   * @param os operating system
   * @param cgroup {@code v1}, {@code v2} or {@code none}
   * @param virtualThreads whether virtual threads are used
   * @param agentVersion agent version
   * @param protocolVersion protocol version
   * @param versionTag application version (F-A21)
   * @param ipMode remote IP mode
   * @param userIdMode user id mode
   * @param queryStringMode query string mode
   * @param sqlParamMode bind parameter mode
   * @param configHash agent configuration hash
   * @param remoteConfigAllow remotely changeable keys (SEC-23)
   * @param clockOffsetMs stored clock offset, or null
   * @param firstSeenAt first connection
   * @param lastSeenAt last connection, or null
   * @param startedAt process start (agent clock), or null
   * @param archivedAt archive time, or null
   */
  public record InstanceRecord(
      int id,
      int appId,
      String name,
      String host,
      Integer pid,
      String jvmVersion,
      String jvmVendor,
      String os,
      String cgroup,
      Boolean virtualThreads,
      String agentVersion,
      Integer protocolVersion,
      String versionTag,
      String ipMode,
      String userIdMode,
      String queryStringMode,
      String sqlParamMode,
      String configHash,
      List<String> remoteConfigAllow,
      Integer clockOffsetMs,
      Instant firstSeenAt,
      Instant lastSeenAt,
      Instant startedAt,
      Instant archivedAt) {

    /** Copies the list. */
    public InstanceRecord {
      remoteConfigAllow = remoteConfigAllow == null ? List.of() : List.copyOf(remoteConfigAllow);
    }
  }

  /**
   * An {@code events} row (14 §4.12).
   *
   * @param id event id
   * @param appId application, null for a server event
   * @param instanceId instance, or null
   * @param type stored type ({@code deploy}, {@code start}, {@code stop}, {@code config}, {@code
   *     state}, {@code apdex_t_change}, ...)
   * @param title title, or null
   * @param detailJson the {@code detail} JSON as text, or null
   * @param version version, or null
   * @param ts time
   * @param actor the user name of {@code created_by}, or null
   */
  public record EventRecord(
      UUID id,
      Integer appId,
      Integer instanceId,
      String type,
      String title,
      String detailJson,
      String version,
      Instant ts,
      String actor) {}

  /**
   * A state transition of an instance ({@code type = state}) or a server start or stop ({@code
   * instanceId} null), the input of the collection outages (D-18).
   *
   * @param instanceId instance, null for a server event
   * @param ts time
   * @param type {@code state}, {@code start} or {@code stop}
   * @param toState the {@code detail.to} of a state event ({@code UP}, {@code STALE}, {@code DOWN},
   *     {@code WARN}), or null
   * @param reason the {@code detail.reason} of a state event, or null
   */
  public record StateEventRecord(
      Integer instanceId, Instant ts, String type, String toState, String reason) {}

  /**
   * The last deploy marker of an application.
   *
   * @param appId application
   * @param ts time
   * @param version version, or null
   */
  public record DeployRecord(int appId, Instant ts, String version) {}

  /**
   * An error group ({@code error_groups}, F-S19).
   *
   * @param groupId the fingerprint (XXH3 bits, C-131)
   * @param errorClass exception class, or null
   * @param messagePattern masked message pattern, or null
   * @param topFrame top application frame, or null
   * @param sampleStack one masked stack, or null
   * @param firstSeen first occurrence
   * @param lastSeen last occurrence
   */
  public record ErrorGroupRecord(
      long groupId,
      String errorClass,
      String messagePattern,
      String topFrame,
      String sampleStack,
      Instant firstSeen,
      Instant lastSeen) {}

  private final JdbcTemplate jdbc;

  public CatalogRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(STATEMENT_TIMEOUT_S);
    this.jdbc = t;
  }

  /** Applications matching {@code filter}, by name, at most {@code limit}. */
  public List<AppRecord> apps(AppFilter filter, int limit) {
    StringBuilder sql = new StringBuilder("SELECT ").append(APP_COLUMNS);
    sql.append(" FROM argos.apps a WHERE TRUE");
    List<Object> values = new ArrayList<>();
    if (filter.env() != null) {
      sql.append(" AND a.env = ?");
      values.add(filter.env());
    }
    if (filter.group() != null) {
      sql.append(" AND a.app_group = ?");
      values.add(filter.group());
    }
    if (filter.nameContains() != null) {
      sql.append(" AND a.name ILIKE ? ESCAPE '\\'");
      values.add("%" + likeEscape(filter.nameContains()) + "%");
    }
    sql.append(" ORDER BY a.name, a.env, a.id LIMIT ?");
    values.add(limit);
    return query("apps", sql.toString(), setter(values), CatalogRepository::app);
  }

  /** The application {@code id}. */
  public Optional<AppRecord> app(int id) {
    List<AppRecord> rows =
        query(
            "app",
            "SELECT " + APP_COLUMNS + " FROM argos.apps a WHERE a.id = ?",
            ps -> ps.setInt(1, id),
            CatalogRepository::app);
    return rows.stream().findFirst();
  }

  /** Instances of {@code appId} by name, archived ones only when asked, at most {@code limit}. */
  public List<InstanceRecord> instances(int appId, boolean includeArchived, int limit) {
    String sql =
        "SELECT "
            + INSTANCE_COLUMNS
            + " FROM argos.instances WHERE app_id = ?"
            + (includeArchived ? "" : " AND archived_at IS NULL")
            + " ORDER BY name, id LIMIT ?";
    return query(
        "instances",
        sql,
        ps -> {
          ps.setInt(1, appId);
          ps.setInt(2, limit);
        },
        CatalogRepository::instance);
  }

  /**
   * The ids of the instances that are not archived of each of {@code appIds} (an application
   * without one is absent), ascending, at most {@code limit} ids in all.
   */
  public Map<Integer, List<Integer>> instanceIdsByApp(Collection<Integer> appIds, int limit) {
    Map<Integer, List<Integer>> out = new LinkedHashMap<>();
    if (appIds.isEmpty()) {
      return out;
    }
    query(
            "instance-ids",
            "SELECT app_id, id FROM argos.instances WHERE app_id = ANY(?)"
                + " AND archived_at IS NULL ORDER BY app_id, id LIMIT ?",
            ps -> {
              ps.setArray(1, ps.getConnection().createArrayOf("int4", appIds.toArray()));
              ps.setInt(2, limit);
            },
            (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)})
        .forEach(r -> out.computeIfAbsent(r[0], a -> new ArrayList<>()).add(r[1]));
    return out;
  }

  /** The instance {@code id}. */
  public Optional<InstanceRecord> instance(int id) {
    return query(
            "instance",
            "SELECT " + INSTANCE_COLUMNS + " FROM argos.instances WHERE id = ?",
            ps -> ps.setInt(1, id),
            CatalogRepository::instance)
        .stream()
        .findFirst();
  }

  /**
   * Events of {@code appId} in {@code [from, to)}, newest first, of the stored {@code types} (all
   * when empty), at most {@code limit}.
   */
  public List<EventRecord> events(
      int appId, Instant from, Instant to, Collection<String> types, int limit) {
    String sql =
        EVENT_SELECT
            + (types.isEmpty() ? "" : " AND e.type = ANY(?)")
            + " ORDER BY e.ts DESC, e.id LIMIT ?";
    return query(
        "events",
        sql,
        ps -> {
          int i = 1;
          ps.setInt(i++, appId);
          ps.setTimestamp(i++, Timestamp.from(from));
          ps.setTimestamp(i++, Timestamp.from(to));
          if (!types.isEmpty()) {
            ps.setArray(i++, ps.getConnection().createArrayOf("text", types.toArray()));
          }
          ps.setInt(i, limit);
        },
        (rs, n) ->
            new EventRecord(
                rs.getObject(1, UUID.class),
                (Integer) rs.getObject(2),
                (Integer) rs.getObject(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                instant(rs, 8),
                rs.getString(9)));
  }

  /**
   * The state transitions of the instances of {@code appId} in {@code [from, to)} with the last
   * transition of each instance before {@code from}, and the server's start and stop events in the
   * range with the last one before it; at most {@code limit} rows, unordered.
   */
  public List<StateEventRecord> stateEvents(int appId, Instant from, Instant to, int limit) {
    return query(
        "state-events",
        STATE_EVENTS,
        ps -> {
          Timestamp f = Timestamp.from(from);
          Timestamp t = Timestamp.from(to);
          ps.setInt(1, appId);
          ps.setTimestamp(2, f);
          ps.setTimestamp(3, t);
          ps.setTimestamp(4, f);
          ps.setInt(5, appId);
          ps.setTimestamp(6, f);
          ps.setTimestamp(7, t);
          ps.setTimestamp(8, f);
          ps.setInt(9, limit);
        },
        (rs, n) ->
            new StateEventRecord(
                (Integer) rs.getObject(1),
                instant(rs, 2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5)));
  }

  /** The latest deploy marker of each of {@code appIds} that has one. */
  public Map<Integer, DeployRecord> lastDeploys(Collection<Integer> appIds) {
    Map<Integer, DeployRecord> out = new LinkedHashMap<>();
    if (appIds.isEmpty()) {
      return out;
    }
    query(
            "last-deploys",
            LAST_DEPLOYS,
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("int4", appIds.toArray())),
            (rs, n) -> new DeployRecord(rs.getInt(1), instant(rs, 2), rs.getString(3)))
        .forEach(d -> out.put(d.appId(), d));
    return out;
  }

  /** The error groups {@code groupIds} of {@code appId} that exist. */
  public Map<Long, ErrorGroupRecord> errorGroups(int appId, Collection<Long> groupIds) {
    Map<Long, ErrorGroupRecord> out = new LinkedHashMap<>();
    if (groupIds.isEmpty()) {
      return out;
    }
    query(
            "error-groups",
            ERROR_GROUPS,
            ps -> {
              ps.setInt(1, appId);
              Array array = ps.getConnection().createArrayOf("bigint", groupIds.toArray());
              ps.setArray(2, array);
            },
            (rs, n) ->
                new ErrorGroupRecord(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    instant(rs, 6),
                    instant(rs, 7)))
        .forEach(g -> out.put(g.groupId(), g));
    return out;
  }

  /**
   * The groups of {@code appId} first seen in {@code [from, to)}, newest first, at most {@code
   * limit}.
   */
  public List<Long> newErrorGroups(int appId, Instant from, Instant to, int limit) {
    return query(
        "new-error-groups",
        NEW_ERROR_GROUPS,
        ps -> {
          ps.setInt(1, appId);
          ps.setTimestamp(2, Timestamp.from(from));
          ps.setTimestamp(3, Timestamp.from(to));
          ps.setInt(4, limit);
        },
        (rs, n) -> rs.getLong(1));
  }

  private <T> List<T> query(
      String what, String sql, PreparedStatementSetter setter, RowMapper<T> mapper) {
    try {
      return jdbc.query(sql, setter, mapper);
    } catch (DataAccessException e) {
      LOG.warn("Cannot read {} from PostgreSQL: {}", what, e.getClass().getSimpleName());
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }

  private static PreparedStatementSetter setter(List<Object> values) {
    return ps -> {
      for (int i = 0; i < values.size(); i++) {
        ps.setObject(i + 1, values.get(i));
      }
    };
  }

  private static AppRecord app(ResultSet rs, int n) throws SQLException {
    return new AppRecord(
        rs.getInt(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getInt(5),
        rs.getInt(6),
        rs.getBoolean(7),
        rs.getBoolean(8),
        instant(rs, 9),
        (Integer) rs.getObject(10),
        rs.getInt(11),
        instant(rs, 12));
  }

  private static InstanceRecord instance(ResultSet rs, int n) throws SQLException {
    Array allow = rs.getArray(19);
    List<String> keys = List.of();
    if (allow != null) {
      Object[] values = (Object[]) allow.getArray();
      keys = Arrays.stream(values).map(String::valueOf).toList();
    }
    return new InstanceRecord(
        rs.getInt(1),
        rs.getInt(2),
        rs.getString(3),
        rs.getString(4),
        (Integer) rs.getObject(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        rs.getString(9),
        (Boolean) rs.getObject(10),
        rs.getString(11),
        (Integer) rs.getObject(12),
        rs.getString(13),
        rs.getString(14),
        rs.getString(15),
        rs.getString(16),
        rs.getString(17),
        trimmed(rs.getString(18)),
        keys,
        (Integer) rs.getObject(20),
        instant(rs, 21),
        instant(rs, 22),
        instant(rs, 23),
        instant(rs, 24));
  }

  private static String trimmed(String value) {
    return value == null ? null : value.strip();
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  /** Escapes {@code \}, {@code %} and {@code _} for {@code LIKE ... ESCAPE '\'}. */
  static String likeEscape(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }
}
