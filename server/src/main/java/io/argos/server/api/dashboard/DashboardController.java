/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.apps.AppScope;
import io.argos.server.api.csv.CsvExport;
import io.argos.server.api.csv.CsvTable;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
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
      " Instance states (12 §4.2): UP, STALE (60 s without a heartbeat), DOWN (180 s without"
          + " one, or 5 minutes after the agent's shutdown message) or WARN (up with a WARN"
          + " condition).";

  private static final String EXCLUDE_NOTE =
      "Instance ids to leave out (ADR-0010 C-212 (1)), at most 100.";

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
              + " DD states of the rollups (C-53). With instance[] or exclude_instance[] the"
              + " transactions come from instance_stat_1m/1h (ADR-0010 C-212 (1)); exclude_instance"
              + " leaves its instances out of the transactions, the JVM series, the live values,"
              + " the instance tiles, collection.late and the outages. delta: basis prev"
              + " (previous range of the same length) or 7d (with baseline=7d); tps, avg_ms,"
              + " p95_ms and p99_ms relative to the same tile over the basis range, error_rate"
              + " absolute, active relative to the mean of series.active over the basis range"
              + " (null when tiles.active is null or that mean is missing or 0). series.cpu and"
              + " series.system_cpu are per bucket the mean over the instances of their average"
              + " process_cpu_usage_ratio and system_cpu_usage_ratio. Live values (tiles.active,"
              + " collection.clock_skew_instances,"
              + " collection.batch_running) only for a range ending in the last 2 minutes, else"
              + " null. series.active is per bucket the sum over the instances of their average"
              + " argos_active_transactions gauge, which the server records from every active"
              + " snapshot (ADR-0010 C-178 (1)); null for a bucket without a snapshot. firing lists"
              + " the application's active alerts now (FIRING and ACKED, newest first, whatever"
              + " the range; ADR-0010 C-212 (2)); collection.server_drops and the"
              + " historic ingest lag are null until the server records its own series (WO-28)."
              + STATE_NOTE)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({
    "xlog",
    "app_stat_1m",
    "app_stat_1h",
    "instance_stat_1m",
    "instance_stat_1h",
    "metric",
    "metric_1m",
    "metric_1h",
    "ext_stat_raw",
    "ext_stat_1m",
    "ext_stat_1h",
    "postgres:events",
    "memory:sessions",
    "memory:alerts"
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
      @Parameter(description = EXCLUDE_NOTE + " Not together with the same id in instance.")
          @RequestParam(value = "exclude_instance", required = false)
          List<Integer> excludeInstance,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    Set<Integer> instances =
        new LinkedHashSet<>(QueryLimits.array("instance", instance, QueryLimits.MAX_ARRAY_VALUES));
    Set<Integer> excluded = excluded(excludeInstance, instances);
    AppRecord app = scope.app(appId);
    String base =
        baseline == null || baseline.isBlank() ? null : baseline.strip().toLowerCase(Locale.ROOT);
    DashboardService.Answer answer =
        dashboard.dashboard(
            new DashboardService.Request(app, f, t, k, step, base, instances, excluded, request));
    return ResponseEnvelope.builder(answer.view()).plan(answer.plan()).build();
  }

  /**
   * The {@code exclude_instance} values (ADR-0010 C-212 (1)): at most {@value
   * QueryLimits#MAX_ARRAY_VALUES} (400 {@code limit_exceeded}); an id that is also in {@code
   * instances} is a contradiction (400 {@code invalid_request}, {@code
   * exclude_instance/conflicts_with_instance}).
   */
  static Set<Integer> excluded(List<Integer> values, Set<Integer> instances) {
    Set<Integer> excluded =
        new LinkedHashSet<>(
            QueryLimits.array("exclude_instance", values, QueryLimits.MAX_ARRAY_VALUES));
    excluded.remove(null);
    for (Integer id : excluded) {
      if (instances.contains(id)) {
        throw new ApiException(
                ErrorCode.INVALID_REQUEST, "An instance is both selected and excluded")
            .detail("exclude_instance", "conflicts_with_instance");
      }
    }
    return Set.copyOf(excluded);
  }

  /** The instance comparison (14 §4.4). */
  @GetMapping("/api/v1/apps/{appId}/instances/compare")
  @Operation(
      operationId = "instances_compare",
      summary = "Comparison of the instances of an application",
      description =
          "Per instance over [from, to) (at most 1 year): transactions from instance_stat_1m/1h"
              + " (raw rows for the live tail and unaligned edges; ADR-0010 C-212 (1)), heap ratio, CPU and pool"
              + " usage averages from metric*, and outlier flags (p95, error rate, CPU) by"
              + " leave-one-out (ADR-0010 C-177): for each instance, mean and sample standard"
              + " deviation of the other instances' values; the instance is an outlier when at"
              + " least 3 instances have the value and |value - mean| > 2 x max(standard"
              + " deviation, 0.05 x |mean|) (strict). With 2 or fewer instances with the value the"
              + " flag is false; null without a value. exclude_instance leaves instances out (no"
              + " row, not part of the outlier statistics). Also as CSV (format=csv, tz)."
              + STATE_NOTE)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({
    "xlog",
    "instance_stat_1m",
    "instance_stat_1h",
    "metric",
    "metric_1m",
    "metric_1h",
    "memory:sessions"
  })
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
      @Parameter(description = EXCLUDE_NOTE)
          @RequestParam(value = "exclude_instance", required = false)
          List<Integer> excludeInstance,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    CompareService.Answer answer = compare(appId, from, to, kind, excludeInstance, request);
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
      @RequestParam(value = "exclude_instance", required = false) List<Integer> excludeInstance,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    var zone = CsvExport.zone(tz);
    CompareService.Answer answer = compare(appId, from, to, kind, excludeInstance, request);
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
    Set<Integer> excluded = excluded(excludeInstance, Set.of());
    if (!excluded.isEmpty()) {
      filters.put("exclude_instance", excluded.stream().sorted().toList());
    }
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
      int appId,
      String from,
      String to,
      String kind,
      List<Integer> excludeInstance,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    Set<Integer> excluded = excluded(excludeInstance, Set.of());
    AppRecord app = scope.app(appId);
    return compare.compare(app, f, t, k, excluded, request);
  }
}
