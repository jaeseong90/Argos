/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import static io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read side of the alert REST API (14 §4.13, WO-25): the event history with its deliveries
 * ({@code alert_events} joined with {@code alert_rules}, {@code apps}, {@code users} and {@code
 * notification_deliveries}), the rule statistics and the failed deliveries of the last day (14 §4.2
 * {@code server.channel_failures_24h}).
 *
 * <p>The event list pages by the key {@code (ts, id)} newest first; {@code ts} keeps the
 * microseconds PostgreSQL stores, so a cursor never skips or repeats a row.
 */
@Repository
public class AlertQueryRepository {

  /** Statistics rows at most (one per rule). */
  public static final int MAX_STATS = 1_000;

  private final JdbcTemplate jdbc;

  public AlertQueryRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(AlertRuleRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The filters of the event list; null fields match anything.
   *
   * @param from range start (inclusive)
   * @param to range end (exclusive)
   * @param appId the application
   * @param env the environment
   * @param severity {@code warn} or {@code critical}
   * @param state the state the transition led to
   * @param ruleId the rule
   * @param failedDelivery only events with a failed delivery
   */
  public record EventFilter(
      Instant from,
      Instant to,
      Integer appId,
      String env,
      String severity,
      String state,
      UUID ruleId,
      boolean failedDelivery) {}

  /**
   * The position after the last row of a page.
   *
   * @param ts that row's time
   * @param id that row's id
   */
  public record EventKey(Instant ts, UUID id) {}

  /**
   * One event of the history.
   *
   * @param id the event id
   * @param ts the transition time
   * @param ruleId the rule
   * @param ruleName its name
   * @param appId the application, null for a server self rule
   * @param appName its name, or null
   * @param env the environment, or null
   * @param targetKind app, instance, service, ext_host, server or channel
   * @param targetId the unit's id, or null
   * @param targetName the unit's name, or null
   * @param fromState the state before
   * @param toState the state after
   * @param severity warn, critical or null
   * @param value the value at the transition, or null
   * @param threshold the threshold, or null
   * @param durationS seconds the condition held or the alert fired, or null
   * @param ackedBy the acknowledging user (ACKED), or null
   * @param ackedByName that user's name, or null
   * @param ackNote the acknowledgement note, or null
   * @param reason why the transition happened (V009 {@code alert_events.reason}), null for older
   *     rows
   * @param ruleMetric the rule's {@code metric} (wire name of its spec)
   */
  public record EventRow(
      UUID id,
      Instant ts,
      UUID ruleId,
      String ruleName,
      Integer appId,
      String appName,
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
      String ackedByName,
      String ackNote,
      String reason,
      String ruleMetric) {}

  /**
   * The delivery of one event to one channel.
   *
   * @param eventId the event
   * @param channelId the channel
   * @param status pending, sent, failed or dropped
   * @param attempts attempts so far
   * @param lastErrorCode the error code of the last attempt, or null
   */
  public record DeliveryRow(
      UUID eventId, UUID channelId, String status, int attempts, String lastErrorCode) {}

  /**
   * The statistics of one rule over a range.
   *
   * @param ruleId the rule
   * @param name its name
   * @param firings FIRING transitions from a state that was not firing
   * @param avgDurationS average seconds a resolved alert fired, or null
   * @param avgTimeToAckS average seconds from the firing to the acknowledgement, or null
   * @param avgTimeToResolveS average seconds from the firing to the resolution, or null
   */
  public record RuleStatsRow(
      UUID ruleId,
      String name,
      long firings,
      Double avgDurationS,
      Double avgTimeToAckS,
      Double avgTimeToResolveS) {}

  /**
   * One page of events, newest first.
   *
   * @param after the key of the last row of the previous page, or null for the first page
   * @param limit rows at most
   */
  public List<EventRow> events(EventFilter f, EventKey after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT e.id, e.ts, e.rule_id, r.name, e.app_id, a.name, e.env, e.target_kind,"
                + " e.target_id, e.target_name, e.from_state, e.to_state, e.severity, e.value,"
                + " e.threshold, e.duration_s, e.acked_by, u.username, e.ack_note, e.reason,"
                + " r.spec->>'metric'"
                + " FROM argos.alert_events e"
                + " JOIN argos.alert_rules r ON r.id = e.rule_id"
                + " LEFT JOIN argos.apps a ON a.id = e.app_id"
                + " LEFT JOIN argos.users u ON u.id = e.acked_by"
                + " WHERE e.ts >= ? AND e.ts < ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(f.from()));
    args.add(Timestamp.from(f.to()));
    if (f.appId() != null) {
      sql.append(" AND e.app_id = ?");
      args.add(f.appId());
    }
    if (f.env() != null) {
      sql.append(" AND e.env = ?");
      args.add(f.env());
    }
    if (f.severity() != null) {
      sql.append(" AND e.severity = ?");
      args.add(f.severity());
    }
    if (f.state() != null) {
      sql.append(" AND e.to_state = ?");
      args.add(f.state());
    }
    if (f.ruleId() != null) {
      sql.append(" AND e.rule_id = ?");
      args.add(f.ruleId());
    }
    if (f.failedDelivery()) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM argos.notification_deliveries d"
              + " WHERE d.alert_event_id = e.id AND d.status = 'failed')");
    }
    if (after != null) {
      sql.append(" AND (e.ts < ? OR (e.ts = ? AND e.id < ?))");
      args.add(Timestamp.from(after.ts()));
      args.add(Timestamp.from(after.ts()));
      args.add(after.id());
    }
    sql.append(" ORDER BY e.ts DESC, e.id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), AlertQueryRepository::eventRow, args.toArray());
  }

  /** The deliveries of the given events, oldest first per event. */
  public Map<UUID, List<DeliveryRow>> deliveries(Collection<UUID> eventIds) {
    Map<UUID, List<DeliveryRow>> out = new LinkedHashMap<>();
    if (eventIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT alert_event_id, channel_id, status, attempt, error"
            + " FROM argos.notification_deliveries"
            + " WHERE alert_event_id = ANY(CAST(? AS uuid[]))"
            + " ORDER BY alert_event_id, sent_at, id",
        rs -> {
          DeliveryRow d =
              new DeliveryRow(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getString(3),
                  rs.getInt(4),
                  rs.getString(5));
          out.computeIfAbsent(d.eventId(), k -> new ArrayList<>()).add(d);
        },
        (Object) eventIds.stream().map(UUID::toString).toArray(String[]::new));
    return out;
  }

  /**
   * The statistics per rule of {@code [from, to)}: firings, average firing duration, time to
   * acknowledge and time to resolve (14 §4.13 {@code /alerts/stats}). The firing of an
   * acknowledgement or resolution is the last FIRING transition of the same rule unit before it.
   *
   * @param appId the application, or null for every rule
   */
  public List<RuleStatsRow> stats(Instant from, Instant to, Integer appId) {
    StringBuilder sql =
        new StringBuilder(
            "WITH e AS (SELECT rule_id, to_state, ts, duration_s,"
                + " CASE WHEN to_state = 'FIRING' AND from_state NOT IN ('FIRING', 'ACKED')"
                + " THEN 1 ELSE 0 END AS firing,"
                + " max(CASE WHEN to_state = 'FIRING' AND from_state NOT IN ('FIRING', 'ACKED')"
                + " THEN ts END) OVER (PARTITION BY rule_id, target_kind,"
                + " coalesce(target_id, -1), coalesce(target_name, '') ORDER BY ts, id"
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS last_firing"
                + " FROM argos.alert_events WHERE ts >= ? AND ts < ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(to));
    if (appId != null) {
      sql.append(" AND app_id = ?");
      args.add(appId);
    }
    sql.append(
        ") SELECT e.rule_id, r.name, sum(e.firing) AS firings,"
            + " avg(e.duration_s) FILTER (WHERE e.to_state = 'RESOLVED') AS avg_duration,"
            + " avg(extract(epoch FROM e.ts - e.last_firing))"
            + " FILTER (WHERE e.to_state = 'ACKED' AND e.last_firing IS NOT NULL) AS avg_ack,"
            + " avg(extract(epoch FROM e.ts - e.last_firing))"
            + " FILTER (WHERE e.to_state = 'RESOLVED' AND e.last_firing IS NOT NULL)"
            + " AS avg_resolve"
            + " FROM e JOIN argos.alert_rules r ON r.id = e.rule_id"
            + " GROUP BY e.rule_id, r.name"
            + " ORDER BY firings DESC, r.name, e.rule_id LIMIT "
            + MAX_STATS);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new RuleStatsRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getLong(3),
                nullableDouble(rs, 4),
                nullableDouble(rs, 5),
                nullableDouble(rs, 6)),
        args.toArray());
  }

  /** Deliveries that ended {@code failed} since {@code since} (all channels). */
  public long failedDeliveriesSince(Instant since) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.notification_deliveries"
                + " WHERE status = 'failed' AND sent_at >= ?",
            Long.class,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
    double v = rs.getDouble(column);
    return rs.wasNull() ? null : v;
  }

  private static EventRow eventRow(ResultSet rs, int i) throws SQLException {
    int app = rs.getInt(5);
    Integer appId = rs.wasNull() ? null : app;
    long target = rs.getLong(9);
    Long targetId = rs.wasNull() ? null : target;
    int d = rs.getInt(16);
    Integer duration = rs.wasNull() ? null : d;
    return new EventRow(
        rs.getObject(1, UUID.class),
        instant(rs.getTimestamp(2)),
        rs.getObject(3, UUID.class),
        rs.getString(4),
        appId,
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        targetId,
        rs.getString(10),
        rs.getString(11),
        rs.getString(12),
        rs.getString(13),
        nullableDouble(rs, 14),
        nullableDouble(rs, 15),
        duration,
        rs.getObject(17, UUID.class),
        rs.getString(18),
        rs.getString(19),
        rs.getString(20),
        rs.getString(21));
  }
}
