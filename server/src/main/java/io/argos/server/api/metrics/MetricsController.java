/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.metrics;

import io.argos.server.api.apps.AppScope;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The metrics of an instance (14 §4.10, F-U08). */
@RestController
@RequiresRole(Role.VIEWER)
public class MetricsController {

  private final MetricsService metrics;
  private final AppScope scope;

  public MetricsController(MetricsService metrics, AppScope scope) {
    this.metrics = metrics;
    this.scope = scope;
  }

  /** Named series of an instance (14 §4.10). */
  @GetMapping("/api/v1/instances/{instanceId}/metrics")
  @Operation(
      operationId = "metrics_get",
      summary = "Metric series of an instance",
      description =
          "The series named by names (stored names with labels, as metrics/names lists them; 1 to"
              + " 50, each at most 256 bytes) on the time grid of [from, to) by the source rule of"
              + " 14 §3 (meta.source; at most 1 year, 720 buckets, so at most 2,000 points per"
              + " series). Gauges: bucket average, min and max; counters: the increase in the"
              + " bucket. A bucket without samples is null, never 0 (D-09).")
  @ArgosLimits(rangeMax = "1y", rowsMax = MetricsService.MAX_POINTS, timeoutS = 10)
  @ArgosSource({"metric", "metric_1m", "metric_1h"})
  public ResponseEnvelope<MetricsViews.Metrics> metrics(
      @PathVariable("instanceId") int instanceId,
      @Parameter(description = "Series names with labels, 1 to 50")
          @RequestParam(value = "names", required = false)
          List<String> names,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "10s, 30s, 1m, 5m, 15m, 30m, 1h, 3h, 6h, 12h or 1d")
          @RequestParam(value = "step", required = false)
          String step,
      HttpServletRequest request) {
    List<String> checked = MetricsService.names(names);
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    AppScope.Scoped s = scope.instance(instanceId);
    MetricsService.Answer a =
        metrics.series(s.app().id(), s.instance().id(), checked, f, t, step, request);
    return ResponseEnvelope.builder(a.metrics()).plan(a.plan()).build();
  }

  /** The series names of an instance (14 §4.10). */
  @GetMapping("/api/v1/instances/{instanceId}/metrics/names")
  @Operation(
      operationId = "metric_names_list",
      summary = "Metric series of an instance",
      description =
          "The series (names with labels, kind, unit) the instance reported in the last 30 days,"
              + " by name, at most 2,000 (meta.truncated when cut).")
  @ArgosLimits(rowsMax = MetricsService.MAX_NAMES, timeoutS = 10)
  @ArgosSource({"metric_1h"})
  public ResponseEnvelope<List<MetricsViews.Name>> names(
      @PathVariable("instanceId") int instanceId) {
    AppScope.Scoped s = scope.instance(instanceId);
    List<MetricsViews.Name> names = metrics.names(s.app().id(), s.instance().id());
    return ResponseEnvelope.builder(names)
        .truncated(names.size() >= MetricsService.MAX_NAMES)
        .build();
  }
}
