/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.configchange;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code config_changes}, {@code config_change_results} and {@code app_config} (V001, V011) for
 * {@code ConfigChangeService} and {@code KillSwitchService} (WO-26 lane B step 2). Keys and values
 * are the agent's dotted keys and string values; the remote allow list never covers secrets (16
 * §5.5), so nothing here is encrypted. Status values are the closed lists of V011.
 */
@Repository
public class ConfigChangeRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** {@code config_changes.status}: proposed, not applied. */
  public static final String PREVIEWED = "previewed";

  /** Pushed; results arriving. */
  public static final String APPLYING = "applying";

  /** Every target answered or is pending/offline. */
  public static final String APPLIED = "applied";

  /** A newer change of the app was applied before this preview. */
  public static final String SUPERSEDED = "superseded";

  /** {@code config_change_results.status}: every pushed key applied. */
  public static final String RESULT_APPLIED = "applied";

  /** Every key rejected (by the agent or, outside its allow list, by the server). */
  public static final String RESULT_REJECTED = "rejected";

  /** Some keys applied, some rejected. */
  public static final String RESULT_PARTIAL = "partial";

  /** Pushed without an answer in time, or the session ended before the answer. */
  public static final String RESULT_OFFLINE = "offline";

  /** Not connected (next ServerHello.config), or pushed and waiting ({@code pushed_at} set). */
  public static final String PENDING = "pending";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};

  private static final String CHANGE_COLUMNS =
      "id, app_id, kind, status, target_all, target_instance_ids, reason, before::text,"
          + " after::text, preview::text, requested_by, requested_at, applied_at, applied_by,"
          + " reverted_from";

  private static final String RESULT_COLUMNS =
      "change_id, instance_id, status, applied_keys, rejected_keys, rejected::text,"
          + " effective_config_hash, duration_ms, applied_at, error, pushed_at";

  /**
   * A new change ({@code previewed}).
   *
   * @param kind {@code config} or {@code kill_switch}
   * @param targetInstanceIds the targets when not {@code targetAll}, else empty
   * @param before the server-held values of the changed keys before (absent keys: not held)
   * @param after the new values
   * @param previewJson the 201 preview ({@code {diff, rejected_keys_by_instance}}), or null
   * @param revertedFrom the change this one reverts, or null
   */
  public record NewChange(
      UUID id,
      int appId,
      String kind,
      boolean targetAll,
      List<Integer> targetInstanceIds,
      String reason,
      Map<String, String> before,
      Map<String, String> after,
      @Nullable String previewJson,
      UUID requestedBy,
      Instant requestedAt,
      @Nullable UUID revertedFrom) {}

  /** A stored change. */
  public record ChangeRow(
      UUID id,
      int appId,
      String kind,
      String status,
      boolean targetAll,
      List<Integer> targetInstanceIds,
      String reason,
      Map<String, String> before,
      Map<String, String> after,
      @Nullable String previewJson,
      UUID requestedBy,
      Instant requestedAt,
      @Nullable Instant appliedAt,
      @Nullable UUID appliedBy,
      @Nullable UUID revertedFrom) {}

  /**
   * One instance's result of a change.
   *
   * @param status {@code applied}, {@code rejected}, {@code partial}, {@code offline} or {@code
   *     pending}
   * @param rejectedJson the agent's {@code [{key, code}]} list (no values), or null
   */
  public record ResultRow(
      UUID changeId,
      int instanceId,
      String status,
      List<String> appliedKeys,
      List<String> rejectedKeys,
      @Nullable String rejectedJson,
      @Nullable String effectiveConfigHash,
      @Nullable Integer durationMs,
      @Nullable Instant appliedAt,
      @Nullable String error,
      @Nullable Instant pushedAt) {

    /** Whether the row waits for the agent's {@code ConfigApplyResult} (pushed, no answer yet). */
    public boolean awaiting() {
      return PENDING.equals(status) && pushedAt != null;
    }
  }

  /**
   * An instance of an application.
   *
   * @param remoteConfigAllow the {@code remote_config_allow} of its last Hello (empty when none)
   */
  public record InstanceRow(int id, int appId, String name, List<String> remoteConfigAllow) {}

  /** An application. */
  public record AppRow(int id, String name, String env) {}

  /** A value the server holds for an application. */
  public record AppConfigValue(
      String key,
      String value,
      @Nullable UUID changeId,
      @Nullable UUID updatedBy,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public ConfigChangeRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(QUERY_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /** Inserts a {@code previewed} change. */
  public void insert(NewChange c) {
    jdbc.update(
        con -> {
          PreparedStatement st =
              con.prepareStatement(
                  "INSERT INTO argos.config_changes (id, app_id, kind, status, target_all,"
                      + " target_instance_ids, reason, before, after, preview, requested_by,"
                      + " requested_at, reverted_from) VALUES (?, ?, ?, '"
                      + PREVIEWED
                      + "', ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)");
          st.setObject(1, c.id());
          st.setInt(2, c.appId());
          st.setString(3, c.kind());
          st.setBoolean(4, c.targetAll());
          if (c.targetAll()) {
            st.setNull(5, Types.ARRAY);
          } else {
            st.setArray(5, ints(con, c.targetInstanceIds()));
          }
          st.setString(6, c.reason());
          st.setString(7, JSON.writeValueAsString(c.before()));
          st.setString(8, JSON.writeValueAsString(c.after()));
          st.setString(9, c.previewJson());
          st.setObject(10, c.requestedBy());
          st.setTimestamp(11, Timestamp.from(c.requestedAt()));
          st.setObject(12, c.revertedFrom());
          return st;
        });
  }

  /** The change. */
  public Optional<ChangeRow> find(UUID id) {
    return jdbc
        .query("SELECT " + CHANGE_COLUMNS + " FROM argos.config_changes WHERE id = ?", CHANGE, id)
        .stream()
        .findFirst();
  }

  /**
   * The changes of an app, newest first ({@code requested_at DESC, id}).
   *
   * @param afterTs with {@code afterId}: only changes after this keyset position (the cursor), or
   *     null for the first page
   */
  public List<ChangeRow> listByApp(
      int appId, @Nullable Instant afterTs, @Nullable UUID afterId, int limit) {
    if (afterTs == null || afterId == null) {
      return jdbc.query(
          "SELECT "
              + CHANGE_COLUMNS
              + " FROM argos.config_changes WHERE app_id = ? ORDER BY requested_at DESC, id"
              + " LIMIT ?",
          CHANGE,
          appId,
          limit);
    }
    Timestamp ts = Timestamp.from(afterTs);
    return jdbc.query(
        "SELECT "
            + CHANGE_COLUMNS
            + " FROM argos.config_changes WHERE app_id = ? AND (requested_at < ? OR"
            + " (requested_at = ? AND id > ?)) ORDER BY requested_at DESC, id LIMIT ?",
        CHANGE,
        appId,
        ts,
        ts,
        afterId,
        limit);
  }

  /**
   * {@code previewed} → {@code applying} by {@code appliedBy} at {@code at}; false when the change
   * is not a preview anymore (applied twice, superseded).
   */
  public boolean markApplying(UUID id, UUID appliedBy, Instant at) {
    return jdbc.update(
            "UPDATE argos.config_changes SET status = '"
                + APPLYING
                + "', applied_by = ?, applied_at = ? WHERE id = ? AND status = '"
                + PREVIEWED
                + "'",
            appliedBy,
            Timestamp.from(at),
            id)
        == 1;
  }

  /** Sets the status of a change ({@link #APPLIED}, {@link #SUPERSEDED}). */
  public boolean setStatus(UUID id, String status) {
    return jdbc.update("UPDATE argos.config_changes SET status = ? WHERE id = ?", status, id) == 1;
  }

  /** Writes (inserts or replaces) one instance's result of a change. */
  public void upsertResult(ResultRow r) {
    jdbc.update(
        con -> {
          PreparedStatement st =
              con.prepareStatement(
                  "INSERT INTO argos.config_change_results (change_id, instance_id, status,"
                      + " applied_keys, rejected_keys, rejected, effective_config_hash,"
                      + " duration_ms, applied_at, error, pushed_at) VALUES (?, ?, ?, ?, ?, ?::jsonb,"
                      + " ?, ?, ?, ?, ?) ON CONFLICT (change_id, instance_id) DO UPDATE SET status ="
                      + " EXCLUDED.status, applied_keys = EXCLUDED.applied_keys, rejected_keys ="
                      + " EXCLUDED.rejected_keys, rejected = EXCLUDED.rejected,"
                      + " effective_config_hash = EXCLUDED.effective_config_hash, duration_ms ="
                      + " EXCLUDED.duration_ms, applied_at = EXCLUDED.applied_at, error ="
                      + " EXCLUDED.error, pushed_at = EXCLUDED.pushed_at");
          st.setObject(1, r.changeId());
          st.setInt(2, r.instanceId());
          st.setString(3, r.status());
          st.setArray(4, con.createArrayOf("text", r.appliedKeys().toArray()));
          st.setArray(5, con.createArrayOf("text", r.rejectedKeys().toArray()));
          st.setString(6, r.rejectedJson());
          st.setString(7, r.effectiveConfigHash());
          if (r.durationMs() == null) {
            st.setNull(8, Types.INTEGER);
          } else {
            st.setInt(8, r.durationMs());
          }
          st.setTimestamp(9, r.appliedAt() == null ? null : Timestamp.from(r.appliedAt()));
          st.setString(10, r.error());
          st.setTimestamp(11, r.pushedAt() == null ? null : Timestamp.from(r.pushedAt()));
          return st;
        });
  }

  /** The results of a change, by instance. */
  public List<ResultRow> results(UUID changeId) {
    return jdbc.query(
        "SELECT "
            + RESULT_COLUMNS
            + " FROM argos.config_change_results WHERE change_id = ? ORDER BY instance_id",
        RESULT,
        changeId);
  }

  /** The {@code pending} results of an instance, oldest change first (the next Hello's push). */
  public List<ResultRow> pendingOf(int instanceId) {
    return openOf(instanceId, List.of(PENDING));
  }

  /**
   * The results of an instance whose values it may not run yet ({@code pending}, {@code offline}),
   * oldest change first: what its next {@code ServerHello.config} delivers.
   */
  public List<ResultRow> undeliveredOf(int instanceId) {
    return openOf(instanceId, List.of(PENDING, RESULT_OFFLINE));
  }

  private List<ResultRow> openOf(int instanceId, List<String> statuses) {
    return jdbc.query(
        con -> {
          PreparedStatement st =
              con.prepareStatement(
                  "SELECT r."
                      + RESULT_COLUMNS.replace(", ", ", r.")
                      + " FROM argos.config_change_results r JOIN argos.config_changes c ON c.id"
                      + " = r.change_id WHERE r.instance_id = ? AND r.status = ANY(?) ORDER BY"
                      + " c.requested_at, c.id");
          st.setInt(1, instanceId);
          st.setArray(2, con.createArrayOf("text", statuses.toArray()));
          return st;
        },
        RESULT);
  }

  /** One instance's result of a change. */
  public Optional<ResultRow> result(UUID changeId, int instanceId) {
    return jdbc
        .query(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM argos.config_change_results WHERE change_id = ? AND instance_id = ?",
            RESULT,
            changeId,
            instanceId)
        .stream()
        .findFirst();
  }

  /** The results of an instance for the given changes (its state of each server-held key). */
  public List<ResultRow> resultsOf(int instanceId, Collection<UUID> changeIds) {
    if (changeIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          PreparedStatement st =
              con.prepareStatement(
                  "SELECT "
                      + RESULT_COLUMNS
                      + " FROM argos.config_change_results WHERE instance_id = ? AND change_id ="
                      + " ANY(?)");
          st.setInt(1, instanceId);
          st.setArray(2, con.createArrayOf("uuid", changeIds.toArray()));
          return st;
        },
        RESULT);
  }

  /** Every result of the given changes (the state of the server-held keys on each instance). */
  public List<ResultRow> resultsOfChanges(Collection<UUID> changeIds) {
    if (changeIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          PreparedStatement st =
              con.prepareStatement(
                  "SELECT "
                      + RESULT_COLUMNS
                      + " FROM argos.config_change_results WHERE change_id = ANY(?)");
          st.setArray(1, con.createArrayOf("uuid", changeIds.toArray()));
          return st;
        },
        RESULT);
  }

  /** Rows of a change that wait for the agent's answer. */
  public int awaitingCount(UUID changeId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.config_change_results WHERE change_id = ? AND status ="
                + " 'pending' AND pushed_at IS NOT NULL",
            Integer.class,
            changeId);
    return n == null ? 0 : n;
  }

  /**
   * Rows that waited for an answer since before {@code pushedBefore} become {@code offline} with
   * {@code error}; returns the changes they belong to.
   */
  public List<UUID> expireAwaiting(Instant pushedBefore, String error) {
    return jdbc.queryForList(
        "UPDATE argos.config_change_results SET status = 'offline', error = ? WHERE status ="
            + " 'pending' AND pushed_at IS NOT NULL AND pushed_at < ? RETURNING change_id",
        UUID.class,
        error,
        Timestamp.from(pushedBefore));
  }

  /**
   * The rows of {@code instanceId} that wait for an answer become {@code offline} with {@code
   * error} (its session ended); returns the changes they belong to.
   */
  public List<UUID> expireAwaitingOf(int instanceId, String error) {
    return jdbc.queryForList(
        "UPDATE argos.config_change_results SET status = 'offline', error = ? WHERE instance_id ="
            + " ? AND status = 'pending' AND pushed_at IS NOT NULL RETURNING change_id",
        UUID.class,
        error,
        instanceId);
  }

  /** The ids of the changes still {@code applying} (a restart resumes their result timeout). */
  public List<UUID> applyingChanges() {
    return jdbc.queryForList(
        "SELECT id FROM argos.config_changes WHERE status = '" + APPLYING + "'", UUID.class);
  }

  /**
   * {@code applying} → {@code applied} when no row of the change waits for an answer anymore; true
   * when this call made the transition.
   */
  public boolean completeIfDone(UUID changeId) {
    return jdbc.update(
            "UPDATE argos.config_changes SET status = '"
                + APPLIED
                + "' WHERE id = ? AND status = '"
                + APPLYING
                + "' AND NOT EXISTS (SELECT 1 FROM argos.config_change_results WHERE change_id ="
                + " ? AND status = 'pending' AND pushed_at IS NOT NULL)",
            changeId,
            changeId)
        == 1;
  }

  /** The other previews of an app become {@code superseded} (a change of the app was applied). */
  public int supersedePreviews(int appId, UUID applied) {
    return jdbc.update(
        "UPDATE argos.config_changes SET status = '"
            + SUPERSEDED
            + "' WHERE app_id = ? AND status = '"
            + PREVIEWED
            + "' AND id <> ?",
        appId,
        applied);
  }

  /** The application. */
  public Optional<AppRow> app(int appId) {
    return jdbc
        .query(
            "SELECT id, name, env FROM argos.apps WHERE id = ?",
            (rs, n) -> new AppRow(rs.getInt(1), rs.getString(2), rs.getString(3)),
            appId)
        .stream()
        .findFirst();
  }

  /**
   * The user name of {@code userId}, empty for a deleted user (the audit actor of an outcome that
   * arrives after the server forgot who applied the change, ADR-0010 C-239 (3)).
   */
  public Optional<String> username(UUID userId) {
    return jdbc
        .query("SELECT username FROM argos.users WHERE id = ?", (rs, n) -> rs.getString(1), userId)
        .stream()
        .findFirst();
  }

  /** The instances of an app that are not archived, by id. */
  public List<InstanceRow> instancesOf(int appId) {
    return jdbc.query(
        "SELECT id, app_id, name, remote_config_allow FROM argos.instances WHERE app_id = ? AND"
            + " archived_at IS NULL ORDER BY id",
        INSTANCE,
        appId);
  }

  /** The instance, archived or not. */
  public Optional<InstanceRow> instance(int instanceId) {
    return jdbc
        .query(
            "SELECT id, app_id, name, remote_config_allow FROM argos.instances WHERE id = ?",
            INSTANCE,
            instanceId)
        .stream()
        .findFirst();
  }

  private static final RowMapper<InstanceRow> INSTANCE =
      (ResultSet rs, int n) ->
          new InstanceRow(rs.getInt(1), rs.getInt(2), rs.getString(3), textList(rs.getArray(4)));

  /** The values the server holds for an app, by key. */
  public Map<String, AppConfigValue> appConfig(int appId) {
    Map<String, AppConfigValue> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT key, value, change_id, updated_by, updated_at FROM argos.app_config WHERE app_id"
            + " = ? ORDER BY key",
        rs -> {
          AppConfigValue v =
              new AppConfigValue(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getObject(3, UUID.class),
                  rs.getObject(4, UUID.class),
                  rs.getTimestamp(5).toInstant());
          out.put(v.key(), v);
        },
        appId);
    return out;
  }

  /**
   * Sets server-held values of an app in one transaction: a non-null value is written, a null value
   * removes the key (back to the agent's local value).
   */
  public void putAppConfig(
      int appId, Map<String, @Nullable String> values, UUID changeId, UUID updatedBy, Instant at) {
    transactions.executeWithoutResult(
        s -> {
          for (Map.Entry<String, @Nullable String> e : values.entrySet()) {
            if (e.getValue() == null) {
              jdbc.update(
                  "DELETE FROM argos.app_config WHERE app_id = ? AND key = ?", appId, e.getKey());
            } else {
              jdbc.update(
                  "INSERT INTO argos.app_config (app_id, key, value, change_id, updated_by,"
                      + " updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (app_id, key) DO"
                      + " UPDATE SET value = EXCLUDED.value, change_id = EXCLUDED.change_id,"
                      + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                  appId,
                  e.getKey(),
                  e.getValue(),
                  changeId,
                  updatedBy,
                  Timestamp.from(at));
            }
          }
        });
  }

  private static Array ints(Connection con, List<Integer> values) throws SQLException {
    return con.createArrayOf("integer", values.toArray());
  }

  private static final RowMapper<ChangeRow> CHANGE =
      (ResultSet rs, int n) ->
          new ChangeRow(
              rs.getObject(1, UUID.class),
              rs.getInt(2),
              rs.getString(3),
              rs.getString(4),
              rs.getBoolean(5),
              intList(rs.getArray(6)),
              rs.getString(7),
              map(rs.getString(8)),
              map(rs.getString(9)),
              rs.getString(10),
              rs.getObject(11, UUID.class),
              rs.getTimestamp(12).toInstant(),
              instant(rs.getTimestamp(13)),
              rs.getObject(14, UUID.class),
              rs.getObject(15, UUID.class));

  private static final RowMapper<ResultRow> RESULT =
      (ResultSet rs, int n) ->
          new ResultRow(
              rs.getObject(1, UUID.class),
              rs.getInt(2),
              rs.getString(3),
              textList(rs.getArray(4)),
              textList(rs.getArray(5)),
              rs.getString(6),
              rs.getString(7),
              (Integer) rs.getObject(8),
              instant(rs.getTimestamp(9)),
              rs.getString(10),
              instant(rs.getTimestamp(11)));

  private static Map<String, String> map(@Nullable String json) {
    return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, MAP);
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static List<Integer> intList(@Nullable Array a) throws SQLException {
    if (a == null) {
      return List.of();
    }
    List<Integer> out = new ArrayList<>();
    for (Object o : (Object[]) a.getArray()) {
      if (o instanceof Number n) {
        out.add(n.intValue());
      }
    }
    return List.copyOf(out);
  }

  private static List<String> textList(@Nullable Array a) throws SQLException {
    if (a == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : (Object[]) a.getArray()) {
      if (o != null) {
        out.add(o.toString());
      }
    }
    return List.copyOf(out);
  }
}
