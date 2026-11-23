/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import static io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code alert_states} (PostgreSQL V007, 12 §5.4): the state of each rule and evaluation unit,
 * written on transitions only and read when the engine starts (WO-25).
 */
@Repository
public class AlertStateRepository {

  private static final String COLUMNS =
      "rule_id, unit_key, app_id, env, target_kind, target_id, target_name, state, severity,"
          + " state_since, pending_since, firing_since, recover_since, last_notified_at,"
          + " deescalate_to, before_no_data, event_id, event_ts, value, threshold, acked_by,"
          + " acked_at, ack_note";

  private final JdbcTemplate jdbc;

  public AlertStateRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(AlertRuleRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Inserts or replaces the row of {@code (rule_id, unit_key)}. */
  public void upsert(StateRow r) {
    jdbc.update(
        "INSERT INTO argos.alert_states ("
            + COLUMNS
            + ", updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, now()) ON CONFLICT (rule_id, unit_key) DO UPDATE SET app_id ="
            + " EXCLUDED.app_id, env = EXCLUDED.env, target_kind = EXCLUDED.target_kind, target_id"
            + " = EXCLUDED.target_id, target_name = EXCLUDED.target_name, state = EXCLUDED.state,"
            + " severity = EXCLUDED.severity, state_since = EXCLUDED.state_since, pending_since ="
            + " EXCLUDED.pending_since, firing_since = EXCLUDED.firing_since, recover_since ="
            + " EXCLUDED.recover_since, last_notified_at = EXCLUDED.last_notified_at,"
            + " deescalate_to = EXCLUDED.deescalate_to, before_no_data = EXCLUDED.before_no_data,"
            + " event_id = EXCLUDED.event_id, event_ts = EXCLUDED.event_ts, value ="
            + " EXCLUDED.value, threshold = EXCLUDED.threshold, acked_by = EXCLUDED.acked_by,"
            + " acked_at = EXCLUDED.acked_at, ack_note = EXCLUDED.ack_note, updated_at = now()",
        r.ruleId(),
        r.unitKey(),
        r.appId(),
        r.env(),
        r.targetKind(),
        r.targetId(),
        r.targetName(),
        r.state(),
        r.severity(),
        ts(r.stateSince()),
        ts(r.pendingSince()),
        ts(r.firingSince()),
        ts(r.recoverSince()),
        ts(r.lastNotifiedAt()),
        r.deescalateTo(),
        r.beforeNoData(),
        r.eventId(),
        ts(r.eventTs()),
        r.value(),
        r.threshold(),
        r.ackedBy(),
        ts(r.ackedAt()),
        r.ackNote());
  }

  /** Every stored state (the engine's restore). */
  public List<StateRow> loadAll() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM argos.alert_states ORDER BY rule_id, unit_key",
        AlertStateRepository::row);
  }

  /** Removes the states of a rule (after the rule was deleted and its alerts resolved). */
  public void deleteRule(UUID ruleId) {
    jdbc.update("DELETE FROM argos.alert_states WHERE rule_id = ?", ruleId);
  }

  /** Removes one state. */
  public void delete(UUID ruleId, String unitKey) {
    jdbc.update(
        "DELETE FROM argos.alert_states WHERE rule_id = ? AND unit_key = ?", ruleId, unitKey);
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static StateRow row(ResultSet rs, int i) throws SQLException {
    int app = rs.getInt(3);
    Integer appId = rs.wasNull() ? null : app;
    long target = rs.getLong(6);
    Long targetId = rs.wasNull() ? null : target;
    double v = rs.getDouble(19);
    Double value = rs.wasNull() ? null : v;
    double t = rs.getDouble(20);
    Double threshold = rs.wasNull() ? null : t;
    return new StateRow(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        appId,
        rs.getString(4),
        rs.getString(5),
        targetId,
        rs.getString(7),
        rs.getString(8),
        rs.getString(9),
        instant(rs.getTimestamp(10)),
        instant(rs.getTimestamp(11)),
        instant(rs.getTimestamp(12)),
        instant(rs.getTimestamp(13)),
        instant(rs.getTimestamp(14)),
        rs.getString(15),
        rs.getString(16),
        rs.getObject(17, UUID.class),
        instant(rs.getTimestamp(18)),
        value,
        threshold,
        rs.getObject(21, UUID.class),
        instant(rs.getTimestamp(22)),
        rs.getString(23));
  }

  /**
   * One {@code alert_states} row (see V007 for the columns).
   *
   * @param ruleId the rule
   * @param unitKey the evaluation unit
   * @param appId the application, null for server rules
   * @param env the environment, or null
   * @param targetKind app, instance, service, ext_host or server
   * @param targetId the unit's id, or null
   * @param targetName the unit's name, or null
   * @param state the state
   * @param severity warn or critical, or null
   * @param stateSince when the state was entered
   * @param pendingSince when the condition was first true
   * @param firingSince the FIRING transition
   * @param recoverSince when the recovery condition was first true
   * @param lastNotifiedAt the last notification
   * @param deescalateTo the severity of the next renotify, or null
   * @param beforeNoData the state NO_DATA interrupted, or null
   * @param eventId the event of the current firing
   * @param eventTs its time
   * @param value the last value
   * @param threshold the threshold of its severity
   * @param ackedBy the acknowledging user
   * @param ackedAt the acknowledgement time
   * @param ackNote the acknowledgement note
   */
  public record StateRow(
      UUID ruleId,
      String unitKey,
      Integer appId,
      String env,
      String targetKind,
      Long targetId,
      String targetName,
      String state,
      String severity,
      Instant stateSince,
      Instant pendingSince,
      Instant firingSince,
      Instant recoverSince,
      Instant lastNotifiedAt,
      String deescalateTo,
      String beforeNoData,
      UUID eventId,
      Instant eventTs,
      Double value,
      Double threshold,
      UUID ackedBy,
      Instant ackedAt,
      String ackNote) {}
}
