/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import static io.argos.server.api.alerts.AlertRulesController.actor;
import static io.argos.server.api.alerts.AlertRulesController.blankToNull;
import static io.argos.server.api.alerts.AlertRulesController.id;
import static io.argos.server.api.alerts.AlertRulesController.principal;

import io.argos.server.alert.AlertStatusService;
import io.argos.server.alert.MuteService;
import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventFilter;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alert events, acknowledgements, mutes and statistics: {@code /api/v1/alerts/events*}, {@code
 * /api/v1/alerts/mutes*} and {@code /api/v1/alerts/stats} (14 §4.13, F-S17, F-S26, F-S27). Viewers
 * read the history and the statistics; operators acknowledge and manage mutes (14 §7.1; the mute
 * list is the operators' as 14 §4.13 names it).
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertEventsController {

  /** UTF-8 bytes of a note or reason at most (14 §7.2 사유 1KB). */
  static final int MAX_TEXT_BYTES = 1024;

  /** Characters of a note or reason at most ({@code VARCHAR(512)} columns). */
  static final int MAX_TEXT_CHARS = 512;

  private static final Set<String> STATES =
      Set.of("OK", "PENDING", "FIRING", "ACKED", "RESOLVED", "NO_DATA");
  private static final Set<String> SEVERITIES = Set.of("warn", "critical");

  private final AlertStatusService status;
  private final AlertEngine engine;
  private final MuteService mutes;
  private final AuditService audit;

  public AlertEventsController(
      AlertStatusService status, AlertEngine engine, MuteService mutes, AuditService audit) {
    this.status = status;
    this.engine = engine;
    this.mutes = mutes;
    this.audit = audit;
  }

  /** The event history. */
  @GetMapping("/events")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "alerts_events_list",
      summary = "Alert events",
      description =
          "The state transitions of [from, to) (at most 366 days, the 365-day retention), newest"
              + " first, with the delivery of each event per channel (F-S27; last_error_code is an"
              + " error code, never a response body). delivery=failed keeps the events with a"
              + " failed delivery. Pages of limit (default 100, at most 500) continue with"
              + " cursor = meta.next_cursor. Every application's active alerts are GET"
              + " /overview/alerts.")
  @ArgosLimits(rangeMax = "366d", rowsMax = AlertStatusService.MAX_EVENTS_PAGE, timeoutS = 10)
  @ArgosSource({
    "postgres:alert_events",
    "postgres:alert_rules",
    "postgres:notification_deliveries"
  })
  public ResponseEnvelope<List<AlertEventDtos.Event>> events(
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "Application id") @RequestParam(value = "app", required = false)
          Integer app,
      @RequestParam(value = "env", required = false) String env,
      @Parameter(description = "warn or critical")
          @RequestParam(value = "severity", required = false)
          String severity,
      @Parameter(description = "OK, PENDING, FIRING, ACKED, RESOLVED or NO_DATA")
          @RequestParam(value = "state", required = false)
          String state,
      @Parameter(description = "Rule id") @RequestParam(value = "rule", required = false)
          String rule,
      @Parameter(description = "failed: only events with a failed delivery")
          @RequestParam(value = "delivery", required = false)
          String delivery,
      @RequestParam(value = "cursor", required = false) String cursor,
      @Parameter(description = "Rows of a page, at most 500 (default 100)")
          @RequestParam(value = "limit", required = false)
          Integer limit) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.range(f, t, AlertStatusService.MAX_EVENTS_RANGE);
    int n =
        QueryLimits.limit(
            "limit",
            limit,
            AlertStatusService.DEFAULT_EVENTS_PAGE,
            AlertStatusService.MAX_EVENTS_PAGE);
    boolean failed = false;
    if (delivery != null && !delivery.isBlank()) {
      if (!"failed".equals(delivery.strip().toLowerCase(Locale.ROOT))) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("delivery", "unsupported_value");
      }
      failed = true;
    }
    UUID ruleId = rule == null || rule.isBlank() ? null : id(rule.strip(), "rule");
    EventFilter filter =
        new EventFilter(
            f,
            t,
            app,
            blankToNull(QueryLimits.text("env", env)),
            severity(severity),
            state(state, STATES),
            ruleId,
            failed);
    AlertStatusService.EventPage page =
        status.events(filter, QueryLimits.text("cursor", cursor), n);
    List<AlertEventDtos.Event> out = new ArrayList<>(page.events().size());
    for (var e : page.events()) {
      out.add(
          AlertEventDtos.Event.of(
              e, page.deliveries().getOrDefault(e.id(), List.of()), status.deeplink(e)));
    }
    return ResponseEnvelope.builder(out)
        .range(f, t)
        .truncated(page.nextCursor() != null)
        .nextCursor(page.nextCursor())
        .build();
  }

  /** Acknowledges a firing alert. */
  @PostMapping("/events/{eventId}/ack")
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_ACKED)
  @Operation(
      operationId = "alerts_events_ack",
      summary = "Acknowledge a firing alert",
      description =
          "eventId is the FIRING event of the current firing (event_id of the active alert). The"
              + " alert becomes ACKED: renotifications stop, the RESOLVED notification is still"
              + " sent (F-S26). 409 invalid_state when the alert is not FIRING (already"
              + " acknowledged or resolved), 404 for an unknown event. note at most 1 KB.")
  public Envelope<AlertEventDtos.Acked> ack(
      @PathVariable("eventId") String eventId,
      @RequestBody(required = false) AlertEventDtos.AckRequest body,
      HttpServletRequest request) {
    UUID id = id(eventId, "eventId");
    String note = body == null ? null : text("note", body.note());
    ArgosPrincipal p = principal();
    AlertEngine.AckResult r;
    try {
      r = engine.ack(id, p.userId(), p.username(), note);
    } catch (IllegalStateException e) {
      throw new ApiException(ErrorCode.STARTING);
    }
    switch (r) {
      case NOT_FOUND -> throw new ApiException(ErrorCode.NOT_FOUND);
      case NOT_FIRING ->
          throw new ApiException(ErrorCode.INVALID_STATE, "The alert is not firing")
              .detail("state", "not_firing");
      default -> {}
    }
    ActiveAlert acked =
        status.active().stream().filter(a -> id.equals(a.eventId())).findFirst().orElse(null);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("state", "ACKED");
    after.put("note_chars", note == null ? 0 : note.length());
    audit.record(
        AuditEvent.RULE_ACKED,
        actor(request),
        new Target("alert_event", id.toString(), acked == null ? null : acked.ruleName()),
        AuditResult.success(),
        null,
        after);
    Instant ackedAt = acked != null && acked.ackedAt() != null ? acked.ackedAt() : status.now();
    return Envelope.of(
        new AlertEventDtos.Acked(
            id.toString(), "ACKED", p.username(), AlertViews.millis(ackedAt), note));
  }

  /** The mutes that have not ended. */
  @GetMapping("/mutes")
  @RequiresRole(Role.OPERATOR)
  @Operation(
      operationId = "alerts_mutes_list",
      summary = "Alert mutes",
      description = "The one-off mutes that have not ended, by start, at most 1,000.")
  @ArgosLimits(rowsMax = 1_000)
  @ArgosSource({"postgres:mutes"})
  public Envelope<List<AlertEventDtos.Mute>> mutesList() {
    List<MuteRow> rows = mutes.current();
    return Envelope.of(rows.stream().map(AlertEventDtos.Mute::of).toList(), rows.size() >= 1_000);
  }

  /** Creates a mute. */
  @PostMapping("/mutes")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.MUTE_CREATED)
  @Operation(
      operationId = "alerts_mutes_create",
      summary = "Create an alert mute",
      description =
          "No rule matching the target (every target field optional: absent matches any) is"
              + " evaluated between from and to. At most 30 days, ending in the future (400"
              + " invalid_request otherwise); reason at most 1 KB.")
  public Envelope<AlertEventDtos.Mute> mutesCreate(
      @RequestBody(required = false) AlertEventDtos.MuteRequest body, HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("from", "required");
    }
    Instant from = QueryLimits.time("from", body.from());
    Instant to = QueryLimits.time("to", body.to());
    AlertEventDtos.MuteTargetRequest target = body.target();
    Integer app = target == null ? null : target.app();
    String env = target == null ? null : blankToNull(QueryLimits.text("target.env", target.env()));
    Integer instance = target == null ? null : target.instance();
    UUID rule =
        target == null || target.rule() == null || target.rule().isBlank()
            ? null
            : id(target.rule().strip(), "target.rule");
    if (env != null && env.length() > 32) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("target.env", "too_long");
    }
    String reason = text("reason", body.reason());
    MuteRow m = mutes.create(app, env, instance, rule, from, to, reason, principal().userId());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("from", m.startsAt().toString());
    after.put("to", m.endsAt().toString());
    if (app != null) {
      after.put("app_id", app);
    }
    if (env != null) {
      after.put("env", env);
    }
    if (instance != null) {
      after.put("instance_id", instance);
    }
    if (rule != null) {
      after.put("rule_id", rule.toString());
    }
    after.put("reason_chars", reason == null ? 0 : reason.length());
    audit.record(
        AuditEvent.MUTE_CREATED,
        actor(request),
        new Target("mute", m.id().toString(), null),
        AuditResult.success(),
        null,
        after);
    return Envelope.of(AlertEventDtos.Mute.of(m));
  }

  /** Deletes a mute. */
  @DeleteMapping("/mutes/{muteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.MUTE_DELETED)
  @Operation(operationId = "alerts_mutes_delete", summary = "Delete an alert mute")
  public void mutesDelete(@PathVariable("muteId") String muteId, HttpServletRequest request) {
    UUID id = id(muteId, "muteId");
    mutes.delete(id);
    audit.record(
        AuditEvent.MUTE_DELETED,
        actor(request),
        new Target("mute", id.toString(), null),
        AuditResult.success());
  }

  /** The statistics per rule. */
  @GetMapping("/stats")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "alerts_stats_get",
      summary = "Alert statistics per rule",
      description =
          "Per rule over the last days (7 or 30): firings (FIRING transitions from a state that"
              + " was not firing), the average firing duration of the resolved alerts, the average"
              + " time from the firing to the acknowledgement and to the resolution, most firings"
              + " first, at most 1,000 rules (finding noisy rules).")
  @ArgosLimits(rangeMax = "30d", rowsMax = 1_000, timeoutS = 10)
  @ArgosSource({"postgres:alert_events", "postgres:alert_rules"})
  public Envelope<List<AlertEventDtos.RuleStats>> stats(
      @Parameter(description = "7 (default) or 30") @RequestParam(value = "days", required = false)
          Integer days,
      @Parameter(description = "Application id") @RequestParam(value = "app", required = false)
          Integer app) {
    int d = days == null ? 7 : days;
    if (d != 7 && d != 30) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "days is 7 or 30")
          .detail("days", "unsupported_value");
    }
    var rows = status.stats(d, app);
    return Envelope.of(
        rows.stream().map(AlertEventDtos.RuleStats::of).toList(), rows.size() >= 1_000);
  }

  /** A severity filter value, or null. */
  static String severity(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String v = value.strip().toLowerCase(Locale.ROOT);
    if (!SEVERITIES.contains(v)) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("severity", "unsupported_value");
    }
    return v;
  }

  /** A state filter value, or null. */
  static String state(String value, Set<String> allowed) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String v = value.strip().toUpperCase(Locale.ROOT);
    if (!allowed.contains(v)) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("state", "unsupported_value");
    }
    return v;
  }

  /** A note or reason: at most 1 KB and 512 characters; blank is none. */
  static String text(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.length() > MAX_TEXT_CHARS
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The text is longer than allowed")
          .detail(field, "too_long");
    }
    return value;
  }
}
