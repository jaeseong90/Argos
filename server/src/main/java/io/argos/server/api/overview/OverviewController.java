/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.overview;

import io.argos.server.api.alerts.AlertViews;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The overview of every application (14 §4.2, F-U17). */
@RestController
@RequiresRole(Role.VIEWER)
public class OverviewController {

  private static final Set<String> SEVERITIES = Set.of("critical", "warn");
  private static final Set<String> ALERT_STATES = Set.of("FIRING", "ACKED");

  private final OverviewService overview;

  public OverviewController(OverviewService overview) {
    this.overview = overview;
  }

  /** The overview (14 §4.2). */
  @GetMapping("/api/v1/overview")
  @Operation(
      operationId = "overview_get",
      summary = "Overview of every application",
      description =
          "At most 500 applications (meta.truncated when there are more) with instance counts,"
              + " status, the TPS, p95 and error rate of the last 5 minutes, the last hour per"
              + " minute (app_stat_1m with the raw live tail), live active and batch counts, the"
              + " last deploy marker and the server summary. Cached for 5 s per filter. status is"
              + " the worst state of the application's instances that are not archived (DOWN, then"
              + " STALE, then WARN, then UP; DOWN without instances), each state as in GET"
              + " /apps/{appId}/instances (12 §4.2); instances.up counts the instances in UP or"
              + " WARN, instances.total those that are not archived. firing counts the"
              + " application's active alerts (FIRING and ACKED) by severity and"
              + " alerts_firing_total the rows of GET /overview/alerts with the same env (server"
              + " self alerts included), both from the alert engine now (ADR-0010 C-212 (2));"
              + " server.channel_failures_24h counts the deliveries that failed in the last 24 h;"
              + " server.disk_usage_ratio and server.cert_expiry_days are null until WO-28.")
  @ArgosLimits(rowsMax = OverviewService.MAX_APPS, timeoutS = 10)
  @ArgosSource({
    "xlog",
    "app_stat_1m",
    "postgres:apps",
    "postgres:instances",
    "postgres:events",
    "postgres:notification_deliveries",
    "memory:sessions",
    "memory:alerts"
  })
  public ResponseEnvelope<OverviewViews.Overview> overview(
      @RequestParam(value = "env", required = false) String env,
      @RequestParam(value = "group", required = false) String group,
      @Parameter(description = "HTTP (default), BATCH, MESSAGE or ALL")
          @RequestParam(value = "kind", required = false)
          String kind) {
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    OverviewService.Result r =
        overview.overview(
            blankToNull(QueryLimits.text("env", env)),
            blankToNull(QueryLimits.text("group", group)),
            k);
    return ResponseEnvelope.builder(r.overview()).truncated(r.truncated()).build();
  }

  /** The firing alerts of every application (14 §4.2). */
  @GetMapping("/api/v1/overview/alerts")
  @Operation(
      operationId = "overview_alerts_list",
      summary = "Firing and acknowledged alerts of every application",
      description =
          "The active (FIRING and ACKED) alerts of every application and of the server itself,"
              + " newest first, at most 1,000 (meta.truncated beyond): the 14 §4.13 event schema"
              + " subset of the alert engine's current state (ADR-0010 C-212 (2)). event_id is the"
              + " FIRING event of the current firing (the acknowledgement target and the deep"
              + " link's alert); duration_s counts from the firing to now. env keeps that"
              + " environment's application alerts and the server self alerts.")
  @ArgosLimits(rowsMax = OverviewService.MAX_ALERTS)
  @ArgosSource({"memory:alerts"})
  public ResponseEnvelope<List<AlertViews.AlertEventItem>> alerts(
      @RequestParam(value = "env", required = false) String env,
      @Parameter(description = "critical or warn")
          @RequestParam(value = "severity", required = false)
          String severity,
      @Parameter(description = "FIRING or ACKED") @RequestParam(value = "state", required = false)
          String state) {
    String e = blankToNull(QueryLimits.text("env", env));
    String sev = null;
    if (severity != null && !severity.isBlank()) {
      sev = severity.strip().toLowerCase(Locale.ROOT);
      if (!SEVERITIES.contains(sev)) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("severity", "unsupported_value");
      }
    }
    String st = null;
    if (state != null && !state.isBlank()) {
      st = state.strip().toUpperCase(Locale.ROOT);
      if (!ALERT_STATES.contains(st)) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("state", "unsupported_value");
      }
    }
    OverviewService.Alerts a = overview.alerts(e, sev, st);
    return ResponseEnvelope.builder(a.rows()).truncated(a.truncated()).build();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
