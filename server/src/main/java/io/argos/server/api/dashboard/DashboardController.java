/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.apps.AppScope;
import io.argos.server.api.csv.CsvExport;
import io.argos.server.api.csv.CsvTable;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosCsv;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard and the instance comparison of an application (14 §4.4). */
@RestController
@RequiresRole(Role.VIEWER)
public class DashboardController {

  private static final String STATE_NOTE =
      " Instance states: UP with a live agent session, DOWN otherwise (STALE and WARN come with"
          + " WO-22, ADR-0010 C-160).";

  private final DashboardService dashboard;
  private final CompareService compare;
  private final AppScope scope;
  private final CsvExport csv;

  public DashboardController(
      DashboardService dashboard, CompareService compare, AppScope scope, CsvExport csv) {
    this.dashboard = dashboard;
    this.compare = compare;
    this.scope = scope;
    this.csv = csv;
  }

  /** The dashboard (14 §4.4). */
  @GetMapping("/api/v1/apps/{appId}/dashboard")
  @Operation(
      operationId = "dashboard_get",
      summary = "Dashboard of an application",
      description =
          "Tiles, series and baseline of [from, to) by the source rule of 14 §3 (meta.source; at"
              + " most 1 year and 720 buckets; step optional). Series values are null where"
              + " there is no data; series.count is 0 for a covered bucket without transactions,"
              + " so Σ count equals the transactions of the range (D-03). Percentiles merge the"
              + " DD states of the rollups (C-53). With instance[] the transactions come from"
              + " instance_stat_1m (minute level for every range). delta: basis prev (previous"
              + " range of the same length) or 7d (with baseline=7d); tps and p95_ms relative,"
              + " error_rate absolute. Live values (tiles.active, collection.clock_skew_instances,"
              + " collection.batch_running) only for a range ending in the last 2 minutes, else"
              + " null. series.active is null until the server records active-transaction gauges"
              + " (WO-22, ADR-0010 C-178). firing is"
              + " null until the alert engine exists (WO-25); collection.server_drops and the"
              + " historic ingest lag are null until the server records its own series (WO-28)."
              + STATE_NOTE)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({
    "xlog",
    "app_stat_1m",
    "app_stat_1h",
    "instance_stat_1m",
    "metric",
    "metric_1m",
    "metric_1h",
    "ext_stat_raw",
    "ext_stat_1m",
    "ext_stat_1h",
    "postgres:events",
    "memory:sessions"
  })
  public ResponseEnvelope<DashboardViews.Dashboard> dashboard(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "HTTP (default), BATCH, MESSAGE or ALL")
          @RequestParam(value = "kind", required = false)
          String kind,
      @Parameter(description = "10s, 30s, 1m, 5m, 15m, 30m, 1h, 3h, 6h, 12h or 1d")
          @RequestParam(value = "step", required = false)
          String step,
      @Parameter(description = "1d or 7d") @RequestParam(value = "baseline", required = false)
          String baseline,
      @Parameter(description = "Instance ids, at most 100")
          @RequestParam(value = "instance", required = false)
          List<Integer> instance,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    Set<Integer> instances =
        new LinkedHashSet<>(QueryLimits.array("instance", instance, QueryLimits.MAX_ARRAY_VALUES));
    AppRecord app = scope.app(appId);
    String base =
        baseline == null || baseline.isBlank() ? null : baseline.strip().toLowerCase(Locale.ROOT);
    DashboardService.Answer answer =
        dashboard.dashboard(
            new DashboardService.Request(app, f, t, k, step, base, instances, request));
    return ResponseEnvelope.builder(answer.view()).plan(answer.plan()).build();
  }

  /** The instance comparison (14 §4.4). */
  @GetMapping("/api/v1/apps/{appId}/instances/compare")
  @Operation(
      operationId = "instances_compare",
      summary = "Comparison of the instances of an application",
      description =
          "Per instance over [from, to) (at most 1 year): transactions from instance_stat_1m"
              + " (raw rows for the live tail and unaligned edges), heap ratio, CPU and pool"
              + " usage averages from metric*, and outlier flags (p95, error rate, CPU) by"
              + " leave-one-out (ADR-0010 C-177): for each instance, mean and sample standard"
              + " deviation of the other instances' values; the instance is an outlier when at"
              + " least 3 instances have the value and |value - mean| > 2 x max(standard"
              + " deviation, 0.05 x |mean|) (strict). With 2 or fewer instances with the value the"
              + " flag is false; null without a value. Also as CSV (format=csv, tz)."
              + STATE_NOTE)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({"xlog", "instance_stat_1m", "metric", "metric_1m", "metric_1h", "memory:sessions"})
  public ResponseEnvelope<List<DashboardViews.Comparison>> compare(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @Parameter(description = "json (default) or csv")
          @RequestParam(value = "format", required = false)
          String format,
      @Parameter(description = "IANA time zone of the CSV times")
          @RequestParam(value = "tz", required = false)
          String tz,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    CompareService.Answer answer = compare(appId, from, to, kind, request);
    return ResponseEnvelope.builder(answer.rows()).plan(answer.plan()).build();
  }

  /** The instance comparison as CSV (14 §4.17). */
  @GetMapping(value = "/api/v1/apps/{appId}/instances/compare", params = "format=csv")
  @Hidden
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  public void compareCsv(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    var zone = CsvExport.zone(tz);
    CompareService.Answer answer = compare(appId, from, to, kind, request);
    CsvTable table =
        new CsvTable(
            "instance_id",
            "name",
            "state",
            "count",
            "tps",
            "avg_ms",
            "p95_ms",
            "error_rate",
            "active",
            "heap_ratio",
            "cpu",
            "pool_usage_ratio",
            "uptime_s",
            "outlier_p95",
            "outlier_error_rate",
            "outlier_cpu");
    for (DashboardViews.Comparison c : answer.rows()) {
      table.add(
          c.instanceId(),
          c.name(),
          c.state(),
          c.count(),
          c.tps(),
          c.avgMs(),
          c.p95Ms(),
          c.errorRate(),
          c.active(),
          c.heapRatio(),
          c.cpu(),
          c.poolUsageRatio(),
          c.uptimeS(),
          c.outlier().p95(),
          c.outlier().errorRate(),
          c.outlier().cpu());
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("kind", QueryLimits.kind(kind).name());
    csv.write(
        request,
        response,
        table,
        "instances",
        answer.plan().from(),
        answer.plan().to(),
        zone,
        appId,
        filters);
  }

  private CompareService.Answer compare(
      int appId, String from, String to, String kind, HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    AppRecord app = scope.app(appId);
    return compare.compare(app, f, t, k, request);
  }
}
