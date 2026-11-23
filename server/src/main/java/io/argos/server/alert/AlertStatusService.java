/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.alert.template.DeepLinks;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.DeliveryRow;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventFilter;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventKey;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventRow;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.RuleStatsRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The read side of the alerts for the REST API (14 §4.2, §4.4, §4.13; WO-25, ADR-0010 C-212 (2)):
 * the engine's active alerts (the firing panels of the dashboard and the overview, always real
 * values: the engine runs whenever the server runs), the event history with its deliveries, the
 * rule statistics and the failed deliveries of the last 24 hours.
 */
public class AlertStatusService {

  /** Events a page holds at most (14 §4.13 {@code limit ≤ 500}). */
  public static final int MAX_EVENTS_PAGE = 500;

  /** Events a page holds by default. */
  public static final int DEFAULT_EVENTS_PAGE = 100;

  /** The longest event range: the 365-day retention of {@code alert_events} (12 §5.4). */
  public static final Duration MAX_EVENTS_RANGE = Duration.ofDays(366);

  /** The window of {@code server.channel_failures_24h}. */
  public static final Duration CHANNEL_FAILURE_WINDOW = Duration.ofHours(24);

  private final AlertEngine engine;
  private final AlertQueryRepository queries;
  private final Clock clock;
  private final String publicUrl;

  public AlertStatusService(
      AlertEngine engine, AlertQueryRepository queries, Clock clock, String publicUrl) {
    this.engine = engine;
    this.queries = queries;
    this.clock = clock;
    this.publicUrl = publicUrl;
  }

  /**
   * Firing alerts of one application by severity.
   *
   * @param critical active critical alerts
   * @param warn active warn alerts
   */
  public record FiringCounts(int critical, int warn) {

    /** No active alert. */
    public static final FiringCounts NONE = new FiringCounts(0, 0);
  }

  /**
   * One page of the event history.
   *
   * @param events the events, newest first
   * @param deliveries the deliveries of those events by event id
   * @param nextCursor the cursor of the next page, or null on the last page
   */
  public record EventPage(
      List<EventRow> events, Map<UUID, List<DeliveryRow>> deliveries, String nextCursor) {}

  /** The current time of the server clock. */
  public Instant now() {
    return clock.instant();
  }

  /** Every active (FIRING or ACKED) alert, newest first (server self alerts included). */
  public List<ActiveAlert> active() {
    return engine.activeAlerts();
  }

  /** The active alerts of one application, newest first. */
  public List<ActiveAlert> activeOfApp(int appId) {
    List<ActiveAlert> out = new ArrayList<>();
    for (ActiveAlert a : engine.activeAlerts()) {
      if (a.appId() != null && a.appId() == appId) {
        out.add(a);
      }
    }
    return out;
  }

  /** The active alerts by application and severity (applications without any are absent). */
  public Map<Integer, FiringCounts> firingByApp() {
    return countByApp(engine.activeAlerts());
  }

  /** The counts of {@code alerts} by application and severity. */
  public static Map<Integer, FiringCounts> countByApp(List<ActiveAlert> alerts) {
    Map<Integer, int[]> counts = new HashMap<>();
    for (ActiveAlert a : alerts) {
      if (a.appId() == null) {
        continue;
      }
      int[] c = counts.computeIfAbsent(a.appId(), k -> new int[2]);
      if ("critical".equals(a.severity())) {
        c[0]++;
      } else {
        c[1]++;
      }
    }
    Map<Integer, FiringCounts> out = new HashMap<>();
    counts.forEach((app, c) -> out.put(app, new FiringCounts(c[0], c[1])));
    return out;
  }

  /** Deliveries that ended {@code failed} in the last 24 hours (14 §4.2). */
  public long channelFailures24h() {
    return queries.failedDeliveriesSince(clock.instant().minus(CHANNEL_FAILURE_WINDOW));
  }

  /**
   * One page of the event history.
   *
   * @param cursor the {@code meta.next_cursor} of the previous page, or null
   * @throws ApiException {@code invalid_request} for a cursor this server did not write
   */
  public EventPage events(EventFilter filter, String cursor, int limit) {
    EventKey after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
    List<EventRow> rows = queries.events(filter, after, limit + 1);
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      EventRow last = rows.get(limit - 1);
      next = encodeCursor(new EventKey(last.ts(), last.id()));
    }
    List<UUID> ids = rows.stream().map(EventRow::id).toList();
    return new EventPage(List.copyOf(rows), queries.deliveries(ids), next);
  }

  /** The statistics per rule of the last {@code days} days. */
  public List<RuleStatsRow> stats(int days, Integer appId) {
    Instant to = clock.instant();
    return queries.stats(to.minus(Duration.ofDays(days)), to, appId);
  }

  /** The 14 §6 deep link of a stored event (its time as the firing time). */
  public String deeplink(EventRow e) {
    return DeepLinks.alert(
        publicUrl, e.appId(), e.env(), targetType(e.targetKind()), e.targetId(), e.ts(), e.id());
  }

  static TargetType targetType(String kind) {
    if (kind == null) {
      return TargetType.SERVER;
    }
    try {
      return TargetType.valueOf(kind.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return TargetType.SERVER;
    }
  }

  static String encodeCursor(EventKey key) {
    String text = key.ts().toString() + "|" + key.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  static EventKey decodeCursor(String cursor) {
    try {
      if (cursor.length() > 128) {
        throw new IllegalArgumentException("too long");
      }
      String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int bar = text.indexOf('|');
      if (bar < 0) {
        throw new IllegalArgumentException("no separator");
      }
      return new EventKey(
          Instant.parse(text.substring(0, bar)), UUID.fromString(text.substring(bar + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The cursor is invalid")
          .detail("cursor", "invalid");
    }
  }
}
