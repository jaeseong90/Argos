/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.overview;

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
              + " last deploy marker and the server summary. Cached for 5 s per filter. Status:"
              + " UP when every instance has a live agent session, DOWN otherwise (STALE and WARN"
              + " come with WO-22, ADR-0010 C-160). firing and alerts_firing_total are null until"
              + " the alert engine exists (WO-25); server.disk_usage_ratio and"
              + " server.cert_expiry_days until WO-28, server.channel_failures_24h until WO-25.")
  @ArgosLimits(rowsMax = OverviewService.MAX_APPS, timeoutS = 10)
  @ArgosSource({"xlog", "app_stat_1m", "postgres:apps", "postgres:events", "memory:sessions"})
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
          "The FIRING and ACKED alert events of every application (14 §4.13 event schema), at"
              + " most 1,000. Always an empty list until the alert engine exists (WO-25); the"
              + " filters are checked already.")
  @ArgosLimits(rowsMax = 1_000)
  @ArgosSource({"postgres:alert_events"})
  public ResponseEnvelope<List<Object>> alerts(
      @RequestParam(value = "env", required = false) String env,
      @Parameter(description = "critical or warn")
          @RequestParam(value = "severity", required = false)
          String severity,
      @Parameter(description = "FIRING or ACKED") @RequestParam(value = "state", required = false)
          String state) {
    QueryLimits.text("env", env);
    if (severity != null
        && !severity.isBlank()
        && !SEVERITIES.contains(severity.strip().toLowerCase(Locale.ROOT))) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("severity", "unsupported_value");
    }
    if (state != null
        && !state.isBlank()
        && !ALERT_STATES.contains(state.strip().toUpperCase(Locale.ROOT))) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("state", "unsupported_value");
    }
    return ResponseEnvelope.builder(List.<Object>of()).truncated(false).build();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
