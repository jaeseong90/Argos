/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.audit;

import io.argos.server.api.csv.CsvExport;
import io.argos.server.api.csv.CsvTable;
import io.argos.server.api.csv.CsvWriter;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosCsv;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.postgres.audit.AuditQueryRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log API (14 §4.14, 16 §11.5, SEC-80..83): {@code GET /api/v1/admin/audit} (pages of
 * records, newest first) and {@code GET /api/v1/admin/audit/export} (CSV). Administrators only; no
 * endpoint writes, changes or deletes a record (SEC-82, the OpenAPI lint). Reading is itself
 * audited: {@code AUDIT_LOG_VIEWED} for every page, {@code AUDIT_LOG_EXPORTED} for every export
 * (filters and row count, 16 §11.1).
 */
@RestController
public class AuditController {

  /** Rows of a page by default. */
  static final int DEFAULT_LIMIT = 100;

  /** Rows of a page at most (14 §4.14). */
  static final int MAX_LIMIT = 500;

  /** The longest range (the 365-day retention and a day). */
  static final Duration MAX_RANGE = Duration.ofDays(366);

  /** The operation id, the scope of its cursors. */
  static final String OPERATION = "admin_audit_list";

  private static final Set<String> EVENTS =
      Arrays.stream(AuditEvent.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  private final AuditQueryRepository repository;
  private final AuditService audit;
  private final CursorCodec cursors;

  public AuditController(AuditQueryRepository repository, AuditService audit, CursorCodec cursors) {
    this.repository = repository;
    this.audit = audit;
    this.cursors = cursors;
  }

  /** A page of audit records. */
  @GetMapping("/api/v1/admin/audit")
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.AUDIT_LOG_VIEWED)
  @Operation(
      operationId = "admin_audit_list",
      summary = "Audit log",
      description =
          "The audit records of [from, to) (at most 366 days), newest first. actor matches the"
              + " actor id or name exactly; action is a 16 §11.1 event name; target_type and"
              + " target_id match exactly. Pages of limit (default 100, at most 500) continue with"
              + " cursor = meta.next_cursor. Read only: the log cannot be changed through the API."
              + " Every call is recorded as AUDIT_LOG_VIEWED.")
  @ArgosLimits(
      rangeMax = "366d",
      rowsMax = MAX_LIMIT,
      timeoutS = AuditQueryRepository.QUERY_TIMEOUT_SECONDS)
  @ArgosSource({"postgres:audit_logs"})
  public ResponseEnvelope<List<AuditDtos.Entry>> list(
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "Actor id or name (exact)")
          @RequestParam(value = "actor", required = false)
          String actor,
      @Parameter(description = "Event name (16 §11.1)")
          @RequestParam(value = "action", required = false)
          String action,
      @RequestParam(value = "target_type", required = false) String targetType,
      @RequestParam(value = "target_id", required = false) String targetId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @Parameter(description = "Rows of a page, at most 500 (default 100)")
          @RequestParam(value = "limit", required = false)
          Integer limit,
      HttpServletRequest request) {
    AuditQueryRepository.Filter filter = filter(from, to, actor, action, targetType, targetId);
    int n = QueryLimits.limit("limit", limit, DEFAULT_LIMIT, MAX_LIMIT);
    String scope = scope(filter);
    AuditQueryRepository.Key after = null;
    String c = QueryLimits.text("cursor", cursor);
    if (c != null && !c.isEmpty()) {
      after = decode(cursors.decode(scope, c));
    }
    List<AuditQueryRepository.Row> rows = repository.find(filter, after, n + 1);
    boolean more = rows.size() > n;
    List<AuditQueryRepository.Row> page = more ? rows.subList(0, n) : rows;
    String next = more ? encode(scope, page.get(page.size() - 1).key()) : null;
    List<AuditDtos.Entry> out = new ArrayList<>(page.size());
    page.forEach(r -> out.add(AuditDtos.Entry.of(r)));
    Map<String, Object> details = details(filter);
    details.put("page", after != null);
    details.put("rows", page.size());
    audit.record(
        AuditEvent.AUDIT_LOG_VIEWED,
        actor(request),
        new Target("audit_log", null, null),
        AuditResult.success(),
        null,
        details);
    return ResponseEnvelope.builder(out)
        .range(filter.from(), filter.to())
        .truncated(more)
        .nextCursor(next)
        .build();
  }

  /** The audit records as CSV. */
  @GetMapping("/api/v1/admin/audit/export")
  @RequiresRole(Role.ADMIN)
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosCsv
  @ArgosAudit(AuditEvent.AUDIT_LOG_EXPORTED)
  @Operation(
      operationId = "admin_audit_export",
      summary = "Export the audit log",
      description =
          "The records of the same filters as GET /admin/audit as CSV (16 §9.6: UTF-8 with BOM,"
              + " every cell quoted, formula prefix), newest first, at most 10,000 rows (400"
              + " limit_exceeded with format/too_many_rows above: narrow the range). format is csv"
              + " (the default). tz: IANA time zone of the times (UTC without). At most 10 exports"
              + " a minute per user. Recorded as AUDIT_LOG_EXPORTED.",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "The CSV file",
              content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
  @ArgosLimits(
      rangeMax = "366d",
      rowsMax = CsvExport.MAX_ROWS,
      timeoutS = AuditQueryRepository.QUERY_TIMEOUT_SECONDS)
  @ArgosSource({"postgres:audit_logs"})
  public void export(
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "actor", required = false) String actor,
      @RequestParam(value = "action", required = false) String action,
      @RequestParam(value = "target_type", required = false) String targetType,
      @RequestParam(value = "target_id", required = false) String targetId,
      @Parameter(description = "csv (the default)")
          @RequestParam(value = "format", required = false)
          String format,
      @Parameter(description = "IANA time zone of the CSV times")
          @RequestParam(value = "tz", required = false)
          String tz,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (format != null && !format.isBlank() && !format.strip().equalsIgnoreCase("csv")) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The format is not supported")
          .detail("format", "unsupported_value");
    }
    ZoneId zone = CsvExport.zone(tz);
    AuditQueryRepository.Filter filter = filter(from, to, actor, action, targetType, targetId);
    List<AuditQueryRepository.Row> rows = repository.find(filter, null, CsvExport.QUERY_ROWS);
    if (rows.size() > CsvExport.MAX_ROWS) {
      throw new ApiException(
              ErrorCode.LIMIT_EXCEEDED, "The export has more than 10,000 rows; narrow the range")
          .detail("format", "too_many_rows");
    }
    CsvTable table =
        new CsvTable(
            "id",
            "ts",
            "actor_type",
            "actor_id",
            "actor_name",
            "ip",
            "user_agent",
            "action",
            "target_type",
            "target_id",
            "target_name",
            "result",
            "reason",
            "before",
            "after",
            "request_id",
            "subject_user_id",
            "subject_tx_id");
    for (AuditQueryRepository.Row r : rows) {
      table.add(
          Long.toString(r.id()),
          r.ts(),
          r.actorType(),
          r.actorId(),
          r.actorName(),
          r.ip(),
          r.userAgent(),
          r.action(),
          r.targetType(),
          r.targetId(),
          r.targetName(),
          r.result(),
          r.reason(),
          r.before(),
          r.after(),
          r.requestId(),
          r.subjectUserId(),
          r.subjectTxId() == null ? null : Long.toUnsignedString(r.subjectTxId()));
    }
    byte[] body = new CsvWriter(zone).write(table);
    Map<String, Object> details = details(filter);
    details.put("format", "csv");
    details.put("rows", rows.size());
    audit.record(
        AuditEvent.AUDIT_LOG_EXPORTED,
        actor(request),
        new Target("audit_log", null, null),
        AuditResult.success(),
        null,
        details);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(CsvExport.CONTENT_TYPE);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" + CsvExport.fileName("audit", filter.from(), filter.to()) + "\"");
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
    response.flushBuffer();
  }

  private static AuditQueryRepository.Filter filter(
      String from, String to, String actor, String action, String targetType, String targetId) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.range(f, t, MAX_RANGE);
    String a = exact("action", action, 64);
    if (a != null && !EVENTS.contains(a)) {
      throw new ApiException(ErrorCode.FILTER_INVALID, "Unknown audit event")
          .detail("action", "unsupported_value");
    }
    return new AuditQueryRepository.Filter(
        f,
        t,
        exact("actor", actor, 128),
        a,
        exact("target_type", targetType, 32),
        exact("target_id", targetId, 64));
  }

  /** A text filter: trimmed, null when blank, at most {@code max} characters (the column size). */
  private static String exact(String field, String value, int max) {
    String v = QueryLimits.text(field, value);
    if (v == null || v.isBlank()) {
      return null;
    }
    v = v.strip();
    if (v.length() > max) {
      throw new ApiException(ErrorCode.FILTER_INVALID, "The value is too long")
          .detail(field, "too_long");
    }
    return v;
  }

  private static Map<String, Object> details(AuditQueryRepository.Filter filter) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("from", filter.from().toString());
    details.put("to", filter.to().toString());
    Map<String, Object> filters = new LinkedHashMap<>();
    if (filter.actor() != null) {
      filters.put("actor", filter.actor());
    }
    if (filter.action() != null) {
      filters.put("action", filter.action());
    }
    if (filter.targetType() != null) {
      filters.put("target_type", filter.targetType());
    }
    if (filter.targetId() != null) {
      filters.put("target_id", filter.targetId());
    }
    details.put("filters", filters);
    return details;
  }

  /** {@code admin_audit_list|<SHA-256 of the filters>}: a cursor of other filters is refused. */
  static String scope(AuditQueryRepository.Filter f) {
    String canonical =
        String.join(
            "\u0000",
            f.from().toString(),
            f.to().toString(),
            String.valueOf(f.actor()),
            String.valueOf(f.action()),
            String.valueOf(f.targetType()),
            String.valueOf(f.targetId()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return OPERATION + "|" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String encode(String scope, AuditQueryRepository.Key key) {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, key.ts());
    return cursors.encode(scope, List.of(Long.toString(micros), Long.toString(key.id())));
  }

  private static AuditQueryRepository.Key decode(List<String> values) {
    if (values.size() != 2) {
      throw invalidCursor();
    }
    try {
      long micros = Long.parseLong(values.get(0));
      long id = Long.parseLong(values.get(1));
      return new AuditQueryRepository.Key(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
    } catch (NumberFormatException | ArithmeticException e) {
      throw invalidCursor();
    }
  }

  private static ApiException invalidCursor() {
    return new ApiException(ErrorCode.FILTER_INVALID, "The cursor is invalid")
        .detail("cursor", "invalid");
  }

  private static Actor actor(HttpServletRequest request) {
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    return Actor.user(principal, request);
  }
}
