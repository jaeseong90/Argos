/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.csv.CsvExport;
import io.argos.server.api.csv.CsvTable;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosCsv;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosPii;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.Source;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction search, detail, baseline and export (14 §4.6; F-U02, F-U14).
 *
 * <ul>
 *   <li>{@code POST /api/v1/apps/{appId}/transactions/search}: heavy bucket (16 §7.13, ADR-0010
 *       C-136 (2)), {@code format=csv} with the {@code EXPORT_CSV} audit (14 §4.17).
 *   <li>{@code GET /api/v1/transactions/{txId}}: the C-50 lookup ({@link TransactionLookup}); bind
 *       parameters by mode and role, {@code SENSITIVE_VIEW} when {@code full} values are shown.
 *   <li>{@code GET /api/v1/transactions/{txId}/baseline}.
 *   <li>{@code GET /api/v1/transactions/{txId}/export}: the detail as a JSON attachment, heavy
 *       bucket, audited.
 * </ul>
 */
@RestController
@RequiresRole(Role.VIEWER)
public class TransactionsController {

  private final TransactionSearchService search;
  private final TransactionLookup lookup;
  private final TransactionDetailService details;
  private final TransactionBaselineService baselines;
  private final AppAccessPolicy access;
  private final AppDirectory directory;
  private final AuditService audit;
  private final CsvExport csvExport;

  public TransactionsController(
      TransactionSearchService search,
      TransactionLookup lookup,
      TransactionDetailService details,
      TransactionBaselineService baselines,
      AppAccessPolicy access,
      AppDirectory directory,
      AuditService audit,
      CsvExport csvExport) {
    this.search = search;
    this.lookup = lookup;
    this.details = details;
    this.baselines = baselines;
    this.access = access;
    this.directory = directory;
    this.audit = audit;
    this.csvExport = csvExport;
  }

  /** The transaction search (14 §4.6), JSON pages or a CSV export. */
  @PostMapping(
      value = "/api/v1/apps/{appId}/transactions/search",
      produces = {MediaType.APPLICATION_JSON_VALUE, "text/csv"})
  @Operation(
      operationId = "transactions_search",
      summary = "Search the transactions of an application",
      description =
          "Every filter of 14 §4.6; sort elapsed_ms:desc (default) or ts:desc; keyset paging with"
              + " meta.next_cursor; at most 1,000 rows a page, ranges up to 24 h (user, request and"
              + " trace id searches without from and to default to the last hour; that 1 h range is"
              + " fixed by the first page and carried by its cursor, so later pages read the same"
              + " hour, and meta.from/meta.to give the range used; any other search without from or"
              + " to answers 400 range_invalid, detail missing). format=csv exports up to 10,000"
              + " rows"
              + " (more: 400 limit_exceeded) and records EXPORT_CSV. A user_id filter on an"
              + " application in hashed mode answers 422 filter_invalid (user_id_hash_unavailable)"
              + " until application salts exist.",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "A page (JSON) or the export (CSV)",
              content = {
                @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TransactionSearch.Response.class)),
                @Content(
                    mediaType = "text/csv",
                    schema = @Schema(type = "string", format = "binary"))
              }))
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosCsv
  @ArgosAudit(AuditEvent.EXPORT_CSV)
  @ArgosLimits(rangeMax = "24h", rowsMax = TransactionSearchService.MAX_LIMIT, timeoutS = 10)
  @ArgosSource({"xlog"})
  public ResponseEntity<Object> search(
      @PathVariable("appId") int appId,
      @RequestBody(required = false) TransactionSearch.Request body,
      @Parameter(description = "json (default) or csv")
          @RequestParam(value = "format", required = false)
          String format,
      @Parameter(description = "IANA time zone of the CSV times (UTC by default)")
          @RequestParam(value = "tz", required = false)
          String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    access.check(principal, appId);
    requireApp(appId);
    boolean asCsv = format != null && format.strip().equalsIgnoreCase("csv");
    if (!asCsv) {
      CsvExport.requireJson(format);
      TransactionSearchService.Plan plan =
          search.plan(appId, body, TransactionSearchService.MAX_LIMIT);
      TransactionSearchService.Page page = search.page(plan);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              ResponseEnvelope.builder(page.rows())
                  .range(plan.from(), plan.to())
                  .source(Source.RAW)
                  .truncated(false)
                  .counts(page.rows().size(), page.total())
                  .nextCursor(page.nextCursor())
                  .retentionExceeded(plan.retentionExceeded())
                  .build());
    }
    ZoneId zone = CsvExport.zone(tz);
    TransactionSearchService.Plan plan =
        search.plan(appId, withoutPaging(body), TransactionSearchService.MAX_LIMIT);
    // count() first: an export of more than 10,000 rows is refused before any row is read.
    if (search.count(plan) > CsvExport.MAX_ROWS) {
      throw new ApiException(
              ErrorCode.LIMIT_EXCEEDED, "The export has more than 10,000 rows; narrow the filters")
          .detail("format", "too_many_rows");
    }
    List<QueryRow> rows = search.rows(plan, CsvExport.QUERY_ROWS);
    CsvTable table = new CsvTable(CSV_HEADER.toArray(String[]::new));
    for (TransactionSearch.Row r : search.convert(appId, rows)) {
      table.add(
          r.txId(),
          r.ts(),
          r.service(),
          r.elapsedMs(),
          r.httpStatus(),
          r.error(),
          r.errorClass(),
          r.instance(),
          r.sqlCount(),
          r.sqlTimeMs(),
          r.extCount(),
          r.extTimeMs(),
          r.userId(),
          r.userIdMode(),
          r.hasProfile(),
          r.kind(),
          r.version());
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("kind", plan.kind().name());
    filters.put("sort", plan.byElapsed() ? "elapsed_ms:desc" : "ts:desc");
    filters.put("set", plan.filterNames());
    csvExport.write(
        request, response, table, "transactions", plan.from(), plan.to(), zone, appId, filters);
    return null;
  }

  /** The detail of a transaction (14 §4.6). */
  @GetMapping(value = "/api/v1/transactions/{txId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "transactions_get",
      summary = "A transaction with its profile",
      description =
          "Lookup order (ADR-0007 C-50, ADR-0010 C-150): ?ts +-2 s, then the time in the id +-2 s,"
              + " then the day of that time and the day before; otherwise 404 not_found with"
              + " details [{field: tx_id, reason: clock_skew_possible}]: open the transaction from"
              + " a list or with ?ts. Bind parameters of a full-mode instance are shown only to an"
              + " administrator with pii.view (audited as SENSITIVE_VIEW); everyone else gets their"
              + " type and length (params_mode masked_by_role).")
  @ArgosPii
  @ArgosAudit(AuditEvent.SENSITIVE_VIEW)
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({
    "xlog",
    "profile_step",
    "postgres:dictionary",
    "postgres:apps",
    "postgres:instances"
  })
  public ResponseEnvelope<TransactionDetail> detail(
      @PathVariable("txId") String txId,
      @Parameter(description = "The transaction's start (ISO-8601 UTC), e.g. from a list")
          @RequestParam(value = "ts", required = false)
          String ts,
      @Parameter(description = "Application hint") @RequestParam(value = "app", required = false)
          Integer app,
      HttpServletRequest request) {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    TransactionLookup.Found found = find(txId, ts, app, principal);
    return ResponseEnvelope.of(details.detail(found, principal, request));
  }

  /** The baseline of a transaction (14 §4.6). */
  @GetMapping(
      value = "/api/v1/transactions/{txId}/baseline",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "transactions_baseline",
      summary = "The service baseline around a transaction",
      description =
          "service_stat_1m within +-30 minutes (p50, p95), the transaction's rank among the"
              + " service's transactions and a normal sample of the same service (the same"
              + " instance first). The transaction is found as by transactions_get.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"xlog", "service_stat_1m"})
  public ResponseEnvelope<TransactionBaseline> baseline(
      @PathVariable("txId") String txId,
      @RequestParam(value = "ts", required = false) String ts,
      @RequestParam(value = "app", required = false) Integer app) {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    TransactionLookup.Found found = find(txId, ts, app, principal);
    return ResponseEnvelope.of(baselines.baseline(found));
  }

  /** The detail as a file (14 §4.6). */
  @GetMapping(
      value = "/api/v1/transactions/{txId}/export",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "transactions_export",
      summary = "Download a transaction as JSON",
      description =
          "The response of transactions_get as an attachment (argos-transaction-<txId>.json);"
              + " heavy bucket; recorded as EXPORT_CSV (view transaction_detail, format json) and,"
              + " when full bind parameters are included, SENSITIVE_VIEW.")
  @ArgosPii
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit({AuditEvent.EXPORT_CSV, AuditEvent.SENSITIVE_VIEW})
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"xlog", "profile_step", "postgres:dictionary"})
  public ResponseEntity<ResponseEnvelope<TransactionDetail>> export(
      @PathVariable("txId") String txId,
      @RequestParam(value = "ts", required = false) String ts,
      @RequestParam(value = "app", required = false) Integer app,
      HttpServletRequest request) {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    TransactionLookup.Found found = find(txId, ts, app, principal);
    TransactionDetail detail = details.detail(found, principal, request);
    if (principal != null) {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("view", "transaction_detail");
      after.put("format", "json");
      after.put("tx_id", detail.txId());
      after.put("app_id", detail.app().appId());
      after.put("rows", 1);
      audit.record(
          AuditEvent.EXPORT_CSV,
          Actor.user(principal, request),
          new Target("transaction", detail.txId(), null),
          AuditResult.success(),
          null,
          after);
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"argos-transaction-" + detail.txId() + ".json\"")
        .body(ResponseEnvelope.of(detail));
  }

  private TransactionLookup.Found find(
      String txId, String ts, Integer app, ArgosPrincipal principal) {
    long id = Ids.parseUnsigned("tx_id", txId);
    Instant hint = QueryLimits.optionalTime("ts", ts);
    if (app != null && app < 0) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("app", "invalid_value");
    }
    TransactionLookup.Found found = lookup.find(id, hint, app == null ? 0 : app);
    access.check(principal, found.first().getLong("app_id"));
    return found;
  }

  private void requireApp(int appId) {
    if (appId <= 0 || directory.app(appId).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND).detail("appId", "unknown");
    }
  }

  private static TransactionSearch.Request withoutPaging(TransactionSearch.Request body) {
    if (body == null) {
      return null;
    }
    return new TransactionSearch.Request(
        body.from(), body.to(), body.kind(), body.filters(), body.sort(), null, null);
  }

  private static final List<String> CSV_HEADER =
      List.of(
          "tx_id",
          "ts",
          "service",
          "elapsed_ms",
          "http_status",
          "error",
          "error_class",
          "instance",
          "sql_count",
          "sql_time_ms",
          "ext_count",
          "ext_time_ms",
          "user_id",
          "user_id_mode",
          "has_profile",
          "kind",
          "version");
}
