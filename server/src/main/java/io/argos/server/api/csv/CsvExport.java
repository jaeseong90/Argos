/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.csv;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Writes a CSV export (14 §4.17, 16 §9.6, SEC-63) for the {@code format=csv} variant of a list
 * endpoint marked {@code @ArgosCsv}:
 *
 * <ul>
 *   <li>at most {@value #MAX_ROWS} rows: a larger result is 400 {@code limit_exceeded} (detail
 *       {@code format/too_many_rows}) before anything is written; the query asks for {@link
 *       #QUERY_ROWS} rows to know;
 *   <li>{@code text/csv; charset=utf-8}, {@code Content-Disposition: attachment;
 *       filename="argos-<resource>-<from>-<to>.csv"} (times as {@code yyyyMMdd'T'HHmmss'Z'});
 *   <li>times in the {@code tz} time zone (IANA id) with its offset, UTC without it;
 *   <li>the audit event {@code EXPORT_CSV} (16 §11.1: view, filters, rows) after the rows are
 *       built.
 * </ul>
 *
 * The handlers carry {@code @RateLimited(HEAVY)} (14 §2.7, 16 §7.13).
 */
@Component
public class CsvExport {

  /** The row limit of an export (14 §4.17). */
  public static final int MAX_ROWS = 10_000;

  /** Rows a CSV query asks for: one more than the limit, to detect an overflow. */
  public static final int QUERY_ROWS = MAX_ROWS + 1;

  /** The response type. */
  public static final String CONTENT_TYPE = "text/csv; charset=utf-8";

  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final AuditService audit;

  public CsvExport(AuditService audit) {
    this.audit = audit;
  }

  /**
   * The {@code tz} parameter: an IANA time zone id (or {@code UTC}); null when absent.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code tz/unsupported_value})
   */
  public static ZoneId zone(String tz) {
    if (tz == null || tz.isBlank()) {
      return null;
    }
    String id = tz.strip();
    if (id.equals("UTC") || id.equals("Z")) {
      return ZoneOffset.UTC;
    }
    if (id.length() > 64 || !ZoneId.getAvailableZoneIds().contains(id)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown time zone")
          .detail("tz", "unsupported_value");
    }
    return ZoneId.of(id);
  }

  /**
   * Checks the {@code format} parameter of a JSON handler: absent or {@code json}. ({@code csv} is
   * routed to the CSV variant of the handler.)
   *
   * @throws ApiException 400 {@code invalid_request} ({@code format/unsupported_value})
   */
  public static void requireJson(String format) {
    if (format == null || format.isBlank() || format.strip().equalsIgnoreCase("json")) {
      return;
    }
    throw new ApiException(ErrorCode.INVALID_REQUEST, "The format is not supported")
        .detail("format", "unsupported_value");
  }

  /**
   * The {@code limit} of a CSV export: the request's (at most {@value #MAX_ROWS}) or {@value
   * #MAX_ROWS}.
   *
   * @throws ApiException 400 {@code limit_exceeded} above {@value #MAX_ROWS}
   */
  public static int limit(Integer requested) {
    if (requested == null) {
      return MAX_ROWS;
    }
    if (requested < 1) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The value must be positive")
          .detail("limit", "must_be_positive");
    }
    if (requested > MAX_ROWS) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The value is above the limit")
          .detail("limit", "above_max");
    }
    return requested;
  }

  /** {@code argos-<resource>-<from>-<to>.csv}; without a range {@code argos-<resource>.csv}. */
  public static String fileName(String resource, Instant from, Instant to) {
    if (from == null || to == null) {
      return "argos-" + resource + ".csv";
    }
    return "argos-" + resource + "-" + FILE_TIME.format(from) + "-" + FILE_TIME.format(to) + ".csv";
  }

  /**
   * Writes {@code table} as the response and records {@code EXPORT_CSV}.
   *
   * @param resource the export name ({@code services}, {@code sql}, ...): file name and audit view
   * @param from range start, or null
   * @param to range end, or null
   * @param zone time zone of time cells, or null for UTC
   * @param appId the application
   * @param filters the request's filters for the audit record (no collected text)
   * @throws ApiException 400 {@code limit_exceeded} for more than {@value #MAX_ROWS} rows
   */
  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      CsvTable table,
      String resource,
      Instant from,
      Instant to,
      ZoneId zone,
      int appId,
      Map<String, ?> filters)
      throws IOException {
    if (table.size() > MAX_ROWS) {
      throw new ApiException(
              ErrorCode.LIMIT_EXCEEDED, "The export has more than 10,000 rows; narrow the range")
          .detail("format", "too_many_rows");
    }
    byte[] body = new CsvWriter(zone).write(table);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("view", resource);
    after.put("app_id", appId);
    if (from != null) {
      after.put("from", from.toString());
      after.put("to", to.toString());
    }
    after.put("filters", filters);
    after.put("rows", table.size());
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    audit.record(
        AuditEvent.EXPORT_CSV,
        Actor.user(principal, request),
        new Target("view", resource, "app " + appId),
        AuditResult.success(),
        null,
        after);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(CONTENT_TYPE);
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" + fileName(resource, from, to) + "\"");
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
    response.flushBuffer();
  }
}
