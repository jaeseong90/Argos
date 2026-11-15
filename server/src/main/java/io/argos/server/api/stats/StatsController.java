/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

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
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service, SQL, outgoing call and error statistics (14 §4.8, F-U05 to F-U07). The lists also answer
 * {@code format=csv} (14 §4.17: at most 10,000 rows, {@code EXPORT_CSV} audit, the heavy rate-limit
 * bucket of 14 §2.7 / 16 §7.13).
 */
@RestController
@RequiresRole(Role.VIEWER)
public class StatsController {

  private static final String IDS =
      " 64-bit ids (service_id, sql_id, db_id, host_id, group_id, version_id) are unsigned decimal"
          + " strings.";

  private static final String RANGE =
      " meta.source follows the source rule of 14 §3 (at most 1 year).";

  private final ServiceStatsService services;
  private final SqlStatsService sql;
  private final ExternalStatsService external;
  private final ErrorStatsService errors;
  private final AppScope scope;
  private final CsvExport csv;

  public StatsController(
      ServiceStatsService services,
      SqlStatsService sql,
      ExternalStatsService external,
      ErrorStatsService errors,
      AppScope scope,
      CsvExport csv) {
    this.services = services;
    this.sql = sql;
    this.external = external;
    this.errors = errors;
    this.scope = scope;
    this.csv = csv;
  }

  // ---------------------------------------------------------------------------------- services

  /** The services (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/services/stats")
  @Operation(
      operationId = "service_stats_list",
      summary = "Service statistics",
      description =
          "Per service of [from, to) from service_stat_1m/1h with raw rows for the live tail and"
              + " unaligned edges; percentiles merge the DD states (C-53). sort: count, error_rate,"
              + " avg_ms, p95_ms, p99_ms, max_ms, apdex or sum_ms (default sum_ms:desc). limit at"
              + " most 500 (default 100; meta.truncated when more exist). split_version=true: one"
              + " row per service and application version. compare_prev=true adds delta against the"
              + " previous range of the same length (count and p95_ms relative, error_rate"
              + " absolute). Also as CSV (format=csv, tz)."
              + RANGE
              + IDS)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = StatsSupport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({"xlog", "service_stat_1m", "service_stat_1h", "postgres:dictionary"})
  public ResponseEnvelope<List<StatsViews.ServiceRow>> servicesStats(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "HTTP (default), BATCH, MESSAGE or ALL")
          @RequestParam(value = "kind", required = false)
          String kind,
      @Parameter(description = "field:asc|desc") @RequestParam(value = "sort", required = false)
          String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "split_version", required = false, defaultValue = "false")
          boolean splitVersion,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @Parameter(description = "json (default) or csv")
          @RequestParam(value = "format", required = false)
          String format,
      @Parameter(description = "IANA time zone of the CSV times")
          @RequestParam(value = "tz", required = false)
          String tz,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    int rows = QueryLimits.limit("limit", limit, StatsSupport.DEFAULT_ROWS, StatsSupport.MAX_ROWS);
    ServiceStatsService.ListAnswer a =
        servicesList(appId, from, to, kind, sort, rows, splitVersion, comparePrev, request);
    return ResponseEnvelope.builder(a.rows()).plan(a.plan()).truncated(a.truncated()).build();
  }

  /** The services as CSV (14 §4.17). */
  @GetMapping(value = "/api/v1/apps/{appId}/services/stats", params = "format=csv")
  @Hidden
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  public void servicesStatsCsv(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "split_version", required = false, defaultValue = "false")
          boolean splitVersion,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ZoneId zone = CsvExport.zone(tz);
    int rows = CsvExport.limit(limit);
    ServiceStatsService.ListAnswer a =
        servicesList(appId, from, to, kind, sort, rows, splitVersion, comparePrev, request);
    overflow(a.truncated(), limit);
    CsvTable table =
        new CsvTable(
            "service_id",
            "service",
            "version_id",
            "version",
            "count",
            "errors",
            "error_rate",
            "avg_ms",
            "p50_ms",
            "p95_ms",
            "p99_ms",
            "max_ms",
            "apdex",
            "sum_ms",
            "sql_time_ratio",
            "ext_time_ratio",
            "delta_count",
            "delta_p95_ms",
            "delta_error_rate");
    for (StatsViews.ServiceRow r : a.rows()) {
      table.add(
          r.serviceId(),
          r.service(),
          r.versionId(),
          r.version(),
          r.count(),
          r.errors(),
          r.errorRate(),
          r.avgMs(),
          r.p50Ms(),
          r.p95Ms(),
          r.p99Ms(),
          r.maxMs(),
          r.apdex(),
          r.sumMs(),
          r.sqlTimeRatio(),
          r.extTimeRatio(),
          r.delta() == null ? null : r.delta().count(),
          r.delta() == null ? null : r.delta().p95Ms(),
          r.delta() == null ? null : r.delta().errorRate());
    }
    Map<String, Object> filters = filters(kind, sort);
    filters.put("split_version", splitVersion);
    filters.put("compare_prev", comparePrev);
    csv.write(
        request, response, table, "services", a.plan().from(), a.plan().to(), zone, appId, filters);
  }

  private ServiceStatsService.ListAnswer servicesList(
      int appId,
      String from,
      String to,
      String kind,
      String sort,
      int rows,
      boolean splitVersion,
      boolean comparePrev,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    StatsSupport.Sort s = StatsSupport.sort(sort, ServiceStatsService.SORT, "sum_ms");
    int app = scope.app(appId).id();
    return services.list(
        new ServiceStatsService.ListRequest(
            app, f, t, k, s, rows, splitVersion, comparePrev, request));
  }

  /** One service (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/services/{serviceId}")
  @Operation(
      operationId = "services_get",
      summary = "One service",
      description =
          "The service's series (count, error rate, p95, p99), versions and error groups of [from,"
              + " to) from the rollups; its instances, SQL statements (xlog.sql_ids, A-10) and"
              + " outgoing calls (HTTP steps of profiled transactions, a sample) from the raw rows"
              + " of raw_window (the last 24 h of the range at most). 404 for an unknown key."
              + RANGE
              + IDS)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({
    "xlog",
    "service_stat_1m",
    "service_stat_1h",
    "error_stat_1m",
    "profile_step",
    "postgres:dictionary",
    "postgres:error_groups"
  })
  public ResponseEnvelope<StatsViews.ServiceDetail> service(
      @PathVariable("appId") int appId,
      @Parameter(description = "Service key (unsigned decimal)") @PathVariable("serviceId")
          String serviceId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    long id = StatsSupport.key(serviceId);
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    int app = scope.app(appId).id();
    ServiceStatsService.DetailAnswer a = services.detail(app, id, f, t, k, step, request);
    return ResponseEnvelope.builder(a.detail()).plan(a.plan()).build();
  }

  // --------------------------------------------------------------------------------------- SQL

  /** The SQL statements (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/sql/stats")
  @Operation(
      operationId = "sql_stats_list",
      summary = "SQL statistics",
      description =
          "Per SQL statement and JDBC target of [from, to) (complete counts, D-12) from"
              + " sql_stat_raw/1m/1h. Percentiles are estimated from the 15 latency buckets"
              + " (estimated: true, C-14). sort: sum_ms (default), count, avg_ms, max_ms or errors."
              + " db_id filters one target. limit at most 500 (default 100). compare_prev=true adds"
              + " delta. Also as CSV (format=csv, tz)."
              + RANGE
              + IDS)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = StatsSupport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({"sql_stat_raw", "sql_stat_1m", "sql_stat_1h", "postgres:dictionary"})
  public ResponseEnvelope<List<StatsViews.SqlRow>> sqlStats(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "db_id", required = false) String dbId,
      @Parameter(description = "field:asc|desc") @RequestParam(value = "sort", required = false)
          String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @RequestParam(value = "format", required = false) String format,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    int rows = QueryLimits.limit("limit", limit, StatsSupport.DEFAULT_ROWS, StatsSupport.MAX_ROWS);
    SqlStatsService.ListAnswer a =
        sqlList(appId, from, to, kind, dbId, sort, rows, comparePrev, request);
    return ResponseEnvelope.builder(a.rows()).plan(a.plan()).truncated(a.truncated()).build();
  }

  /** The SQL statements as CSV (14 §4.17). */
  @GetMapping(value = "/api/v1/apps/{appId}/sql/stats", params = "format=csv")
  @Hidden
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  public void sqlStatsCsv(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "db_id", required = false) String dbId,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ZoneId zone = CsvExport.zone(tz);
    int rows = CsvExport.limit(limit);
    SqlStatsService.ListAnswer a =
        sqlList(appId, from, to, kind, dbId, sort, rows, comparePrev, request);
    overflow(a.truncated(), limit);
    CsvTable table =
        new CsvTable(
            "sql_id",
            "sql_normalized",
            "db_id",
            "db_target",
            "count",
            "errors",
            "sum_ms",
            "exec_ms",
            "fetch_ms",
            "avg_ms",
            "max_ms",
            "p50_ms_estimated",
            "p95_ms_estimated",
            "p99_ms_estimated",
            "fetch_rows",
            "delta_count",
            "delta_p95_ms",
            "delta_error_rate");
    for (StatsViews.SqlRow r : a.rows()) {
      table.add(
          r.sqlId(),
          r.sqlNormalized(),
          r.db().dbId(),
          r.db().target(),
          r.count(),
          r.errors(),
          r.sumMs(),
          r.execMs(),
          r.fetchMs(),
          r.avgMs(),
          r.maxMs(),
          r.p50Ms(),
          r.p95Ms(),
          r.p99Ms(),
          r.fetchRows(),
          r.delta() == null ? null : r.delta().count(),
          r.delta() == null ? null : r.delta().p95Ms(),
          r.delta() == null ? null : r.delta().errorRate());
    }
    Map<String, Object> filters = filters(kind, sort);
    filters.put("db_id", dbId);
    filters.put("compare_prev", comparePrev);
    csv.write(
        request, response, table, "sql", a.plan().from(), a.plan().to(), zone, appId, filters);
  }

  private SqlStatsService.ListAnswer sqlList(
      int appId,
      String from,
      String to,
      String kind,
      String dbId,
      String sort,
      int rows,
      boolean comparePrev,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    StatsSupport.Sort s = StatsSupport.sort(sort, SqlStatsService.SORT, "sum_ms");
    Long db = null;
    if (dbId != null && !dbId.isBlank()) {
      try {
        db = Long.parseUnsignedLong(dbId.strip());
      } catch (NumberFormatException e) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("db_id", "invalid_format");
      }
    }
    int app = scope.app(appId).id();
    return sql.list(app, f, t, k, db, s, rows, comparePrev, request);
  }

  /** One SQL statement (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/sql/{sqlId}")
  @Operation(
      operationId = "sql_get",
      summary = "One SQL statement",
      description =
          "The statement's text (sql_normalized at most 2 KB, sql_text at most 64 KB), series"
              + " (percentiles estimated, C-14) and targets of [from, to) from sql_stat_*; the"
              + " services and related transactions (xlog.sql_ids, A-10) and instances"
              + " (sql_stat_raw) of raw_window. wait_ms_total is not measured (no per-statement"
              + " attribution of pool wait yet; ADR-0010 C-178), always null. 404 for an unknown"
              + " key."
              + RANGE
              + IDS)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({"sql_stat_raw", "sql_stat_1m", "sql_stat_1h", "xlog", "postgres:dictionary"})
  public ResponseEnvelope<StatsViews.SqlDetail> sqlDetail(
      @PathVariable("appId") int appId,
      @Parameter(description = "SQL key (unsigned decimal)") @PathVariable("sqlId") String sqlId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    long id = StatsSupport.key(sqlId);
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    int app = scope.app(appId).id();
    SqlStatsService.DetailAnswer a = sql.detail(app, id, f, t, k, step, request);
    return ResponseEnvelope.builder(a.detail()).plan(a.plan()).build();
  }

  // ----------------------------------------------------------------------------------- external

  /** The outgoing call hosts (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/external/stats")
  @Operation(
      operationId = "external_stats_list",
      summary = "Outgoing call statistics",
      description =
          "Per host of [from, to) from ext_stat_raw/1m/1h; percentiles estimated from the 15"
              + " latency buckets (estimated: true, C-14). path_pattern is not stored yet; decided"
              + " with HTTP client instrumentation (WO-14, ADR-0010 C-178), always null. sort:"
              + " count, errors, timeouts, sum_ms (default), avg_ms or max_ms. limit at most 500"
              + " (default 100). compare_prev=true adds delta. Also as CSV (format=csv, tz)."
              + RANGE
              + IDS)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = StatsSupport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({"ext_stat_raw", "ext_stat_1m", "ext_stat_1h", "postgres:dictionary"})
  public ResponseEnvelope<List<StatsViews.ExternalRow>> externalStats(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @Parameter(description = "field:asc|desc") @RequestParam(value = "sort", required = false)
          String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @RequestParam(value = "format", required = false) String format,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    int rows = QueryLimits.limit("limit", limit, StatsSupport.DEFAULT_ROWS, StatsSupport.MAX_ROWS);
    ExternalStatsService.ListAnswer a =
        externalList(appId, from, to, kind, sort, rows, comparePrev, request);
    return ResponseEnvelope.builder(a.rows()).plan(a.plan()).truncated(a.truncated()).build();
  }

  /** The outgoing call hosts as CSV (14 §4.17). */
  @GetMapping(value = "/api/v1/apps/{appId}/external/stats", params = "format=csv")
  @Hidden
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  public void externalStatsCsv(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "compare_prev", required = false, defaultValue = "false")
          boolean comparePrev,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ZoneId zone = CsvExport.zone(tz);
    int rows = CsvExport.limit(limit);
    ExternalStatsService.ListAnswer a =
        externalList(appId, from, to, kind, sort, rows, comparePrev, request);
    overflow(a.truncated(), limit);
    CsvTable table =
        new CsvTable(
            "host_id",
            "host",
            "count",
            "errors",
            "timeouts",
            "connect_failures",
            "sum_ms",
            "avg_ms",
            "max_ms",
            "p50_ms_estimated",
            "p95_ms_estimated",
            "p99_ms_estimated",
            "delta_count",
            "delta_p95_ms",
            "delta_error_rate");
    for (StatsViews.ExternalRow r : a.rows()) {
      table.add(
          r.hostId(),
          r.host(),
          r.count(),
          r.errors(),
          r.timeouts(),
          r.connectFailures(),
          r.sumMs(),
          r.avgMs(),
          r.maxMs(),
          r.p50Ms(),
          r.p95Ms(),
          r.p99Ms(),
          r.delta() == null ? null : r.delta().count(),
          r.delta() == null ? null : r.delta().p95Ms(),
          r.delta() == null ? null : r.delta().errorRate());
    }
    Map<String, Object> filters = filters(kind, sort);
    filters.put("compare_prev", comparePrev);
    csv.write(
        request, response, table, "external", a.plan().from(), a.plan().to(), zone, appId, filters);
  }

  private ExternalStatsService.ListAnswer externalList(
      int appId,
      String from,
      String to,
      String kind,
      String sort,
      int rows,
      boolean comparePrev,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    StatsSupport.Sort s = StatsSupport.sort(sort, ExternalStatsService.SORT, "sum_ms");
    int app = scope.app(appId).id();
    return external.list(app, f, t, k, s, rows, comparePrev, request);
  }

  /** One host (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/external/{hostId}")
  @Operation(
      operationId = "external_get",
      summary = "One outgoing call host",
      description =
          "The host's series of [from, to) (percentiles estimated, C-14), and the services and"
              + " related transactions of its profiled callers (HTTP steps of profile_step, a"
              + " sample) in raw_window. 404 for an unknown key."
              + RANGE
              + IDS)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({"ext_stat_raw", "ext_stat_1m", "ext_stat_1h", "profile_step", "xlog"})
  public ResponseEnvelope<StatsViews.ExternalDetail> externalDetail(
      @PathVariable("appId") int appId,
      @Parameter(description = "Host key (unsigned decimal)") @PathVariable("hostId") String hostId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    long id = StatsSupport.key(hostId);
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    int app = scope.app(appId).id();
    ExternalStatsService.DetailAnswer a = external.detail(app, id, f, t, k, step, request);
    return ResponseEnvelope.builder(a.detail()).plan(a.plan()).build();
  }

  // ------------------------------------------------------------------------------------- errors

  /** The error groups (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/errors/groups")
  @Operation(
      operationId = "error_groups_list",
      summary = "Error groups",
      description =
          "Per error group of [from, to) from error_stat_1m (raw rows for the tail and edges):"
              + " count, first and last occurrence in the range, top 5 services and versions,"
              + " with the class, masked message pattern (at most 1 KB) and top frame of"
              + " error_groups. is_new: first seen ever inside the range; new_only=true lists only"
              + " those. sort: count (default), first_seen or last_seen. limit at most 500"
              + " (default 100). Also as CSV (format=csv, tz)."
              + RANGE
              + IDS)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = StatsSupport.MAX_ROWS, timeoutS = 10)
  @ArgosSource({"xlog", "error_stat_1m", "postgres:error_groups", "postgres:dictionary"})
  public ResponseEnvelope<List<StatsViews.ErrorGroupRow>> errorGroups(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "new_only", required = false, defaultValue = "false") boolean newOnly,
      @Parameter(description = "field:asc|desc") @RequestParam(value = "sort", required = false)
          String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "format", required = false) String format,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request) {
    CsvExport.requireJson(format);
    CsvExport.zone(tz);
    int rows = QueryLimits.limit("limit", limit, StatsSupport.DEFAULT_ROWS, StatsSupport.MAX_ROWS);
    ErrorStatsService.ListAnswer a = errorList(appId, from, to, kind, newOnly, sort, rows, request);
    return ResponseEnvelope.builder(a.rows()).plan(a.plan()).truncated(a.truncated()).build();
  }

  /** The error groups as CSV (14 §4.17). */
  @GetMapping(value = "/api/v1/apps/{appId}/errors/groups", params = "format=csv")
  @Hidden
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "1y", rowsMax = CsvExport.MAX_ROWS, timeoutS = 10)
  public void errorGroupsCsv(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "new_only", required = false, defaultValue = "false") boolean newOnly,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "tz", required = false) String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ZoneId zone = CsvExport.zone(tz);
    int rows = CsvExport.limit(limit);
    ErrorStatsService.ListAnswer a = errorList(appId, from, to, kind, newOnly, sort, rows, request);
    overflow(a.truncated(), limit);
    CsvTable table =
        new CsvTable(
            "group_id",
            "error_class",
            "message_pattern",
            "top_frame",
            "count",
            "first_seen",
            "last_seen",
            "first_seen_ever",
            "is_new",
            "services",
            "versions");
    for (StatsViews.ErrorGroupRow r : a.rows()) {
      table.add(
          r.groupId(),
          r.errorClass(),
          r.messagePattern(),
          r.topFrame(),
          r.count(),
          r.firstSeen(),
          r.lastSeen(),
          r.firstSeenEver(),
          r.isNew(),
          r.services().stream()
              .map(s -> s.name() == null ? s.id() : s.name())
              .collect(Collectors.joining("; ")),
          r.versions().stream()
              .map(v -> v.version() == null ? v.versionId() : v.version())
              .collect(Collectors.joining("; ")));
    }
    Map<String, Object> filters = filters(kind, sort);
    filters.put("new_only", newOnly);
    csv.write(
        request, response, table, "errors", a.plan().from(), a.plan().to(), zone, appId, filters);
  }

  private ErrorStatsService.ListAnswer errorList(
      int appId,
      String from,
      String to,
      String kind,
      boolean newOnly,
      String sort,
      int rows,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    StatsSupport.Sort s = StatsSupport.sort(sort, ErrorStatsService.SORT, "count");
    int app = scope.app(appId).id();
    return errors.list(app, f, t, k, newOnly, s, rows, request);
  }

  /** One error group (14 §4.8). */
  @GetMapping("/api/v1/apps/{appId}/errors/groups/{groupId}")
  @Operation(
      operationId = "error_groups_get",
      summary = "One error group",
      description =
          "The group with its sample stack (masked, one frame per element), series, versions and"
              + " services of [from, to) (error_stat_1m), instances and the latest 10 transactions"
              + " (xlog.error_group_id) of raw_window. 404 for a group that is neither known nor"
              + " in the range."
              + RANGE
              + IDS)
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({"xlog", "error_stat_1m", "postgres:error_groups", "postgres:dictionary"})
  public ResponseEnvelope<StatsViews.ErrorGroupDetail> errorGroup(
      @PathVariable("appId") int appId,
      @Parameter(description = "Group key (unsigned decimal)") @PathVariable("groupId")
          String groupId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    long id = StatsSupport.key(groupId);
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.TxKind k = QueryLimits.kind(kind);
    int app = scope.app(appId).id();
    ErrorStatsService.DetailAnswer a = errors.detail(app, id, f, t, k, step, request);
    return ResponseEnvelope.builder(a.detail()).plan(a.plan()).build();
  }

  /** 400 {@code limit_exceeded} for an export of every row that has more than 10,000. */
  private static void overflow(boolean truncated, Integer requestedLimit) {
    if (truncated && requestedLimit == null) {
      throw new ApiException(
              ErrorCode.LIMIT_EXCEEDED, "The export has more than 10,000 rows; narrow the range")
          .detail("format", "too_many_rows");
    }
  }

  private static Map<String, Object> filters(String kind, String sort) {
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("kind", QueryLimits.kind(kind).name());
    filters.put("sort", sort == null ? null : sort.strip());
    return filters;
  }
}
