/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import static io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code alert_events} (PostgreSQL V001, monthly partitions, 365 days; 12 §5.4): one row per state
 * transition of a rule unit, never per evaluation (WO-25), with its reason (V009, ADR-0010 C-227
 * (3)). The REST event list is WO-25 step A2's.
 */
@Repository
public class AlertEventRepository {

  /** {@code alert_events.ack_note VARCHAR(512)}. */
  public static final int MAX_NOTE_CHARS = 512;

  /** {@code alert_events.target_name VARCHAR(256)}. */
  public static final int MAX_TARGET_NAME_CHARS = 256;

  private static final String COLUMNS =
      "id, rule_id, app_id, env, target_kind, target_id, target_name, from_state, to_state,"
          + " severity, value, threshold, duration_s, acked_by, ack_note, ts, reason";

  private final JdbcTemplate jdbc;

  public AlertEventRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(AlertRuleRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Inserts one transition. */
  public void insert(EventRow e) {
    jdbc.update(
        "INSERT INTO argos.alert_events ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?)",
        e.id(),
        e.ruleId(),
        e.appId(),
        e.env(),
        e.targetKind(),
        e.targetId(),
        clip(e.targetName(), MAX_TARGET_NAME_CHARS),
        e.fromState(),
        e.toState(),
        e.severity(),
        e.value(),
        e.threshold(),
        e.durationS(),
        e.ackedBy(),
        clip(e.ackNote(), MAX_NOTE_CHARS),
        Timestamp.from(e.ts()),
        e.reason());
  }

  /** The event {@code id}. */
  public Optional<EventRow> find(UUID id) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM argos.alert_events WHERE id = ?",
            AlertEventRepository::row,
            id)
        .stream()
        .findFirst();
  }

  /** Events of a rule (all transitions). */
  public int countByRule(UUID ruleId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.alert_events WHERE rule_id = ?", Integer.class, ruleId);
    return n == null ? 0 : n;
  }

  static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static EventRow row(ResultSet rs, int i) throws SQLException {
    int app = rs.getInt(3);
    Integer appId = rs.wasNull() ? null : app;
    long target = rs.getLong(6);
    Long targetId = rs.wasNull() ? null : target;
    double v = rs.getDouble(11);
    Double value = rs.wasNull() ? null : v;
    double t = rs.getDouble(12);
    Double threshold = rs.wasNull() ? null : t;
    int d = rs.getInt(13);
    Integer duration = rs.wasNull() ? null : d;
    return new EventRow(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        appId,
        rs.getString(4),
        rs.getString(5),
        targetId,
        rs.getString(7),
        rs.getString(8),
        rs.getString(9),
        rs.getString(10),
        value,
        threshold,
        duration,
        rs.getObject(14, UUID.class),
        rs.getString(15),
        instant(rs.getTimestamp(16)),
        rs.getString(17));
  }

  /**
   * One {@code alert_events} row.
   *
   * @param id the event id
   * @param ruleId the rule
   * @param appId the application, null for server rules
   * @param env the environment, or null
   * @param targetKind app, instance, service, ext_host or server
   * @param targetId the unit's id, or null
   * @param targetName the unit's name, or null
   * @param fromState the previous state
   * @param toState the new state
   * @param severity warn or critical, or null
   * @param value the value at the transition, or null
   * @param threshold the threshold of the severity, or null
   * @param durationS seconds the condition held (FIRING) or the alert fired (RESOLVED), or null
   * @param ackedBy the acknowledging user (ACKED), or null
   * @param ackNote the acknowledgement note, or null
   * @param ts the transition time
   * @param reason why ({@code AlertReason} wire name), null for rows written before V009
   */
  public record EventRow(
      UUID id,
      UUID ruleId,
      Integer appId,
      String env,
      String targetKind,
      Long targetId,
      String targetName,
      String fromState,
      String toState,
      String severity,
      Double value,
      Double threshold,
      Integer durationS,
      UUID ackedBy,
      String ackNote,
      Instant ts,
      String reason) {}
}
