/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The input limits of the query API (14 §7.2, §2.3, §2.6) and the parsing of the common parameters,
 * each failure a 400 with a field detail:
 *
 * <table>
 *   <caption>Limits</caption>
 *   <tr><th>Input</th><th>Limit</th><th>Error</th></tr>
 *   <tr><td>URL</td><td>{@value #MAX_URL_BYTES} bytes ({@code RequestUrlLimitFilter})</td>
 *       <td>{@code limit_exceeded}</td></tr>
 *   <tr><td>JSON body</td><td>{@value #MAX_JSON_BODY_BYTES} bytes ({@link QueryBodyLimitAdvice})</td>
 *       <td>{@code payload_too_large}</td></tr>
 *   <tr><td>text filter</td><td>{@value #MAX_TEXT_FILTER_BYTES} bytes</td>
 *       <td>{@code limit_exceeded} ({@code too_long})</td></tr>
 *   <tr><td>array parameter</td><td>{@value #MAX_ARRAY_VALUES} ({@code names[]}: {@value
 *       #MAX_METRIC_NAMES})</td><td>{@code limit_exceeded} ({@code too_many_values})</td></tr>
 *   <tr><td>{@code limit}</td><td>per endpoint</td><td>{@code limit_exceeded} ({@code above_max})</td></tr>
 *   <tr><td>time range</td><td>raw 24 h, series 1 year</td><td>{@code range_invalid}</td></tr>
 *   <tr><td>{@code sort}</td><td>allowed fields</td><td>{@code unsupported_sort}</td></tr>
 *   <tr><td>{@code kind}</td><td>{@code HTTP|BATCH|MESSAGE|ALL}</td><td>{@code filter_invalid}</td></tr>
 * </table>
 *
 * Messages are fixed texts; request values are never echoed (16 §7.14).
 */
public final class QueryLimits {

  /** 14 §7.2: URL (enforced by {@code RequestUrlLimitFilter} for every request). */
  public static final int MAX_URL_BYTES = 8 * 1024;

  /** 14 §7.2: JSON body of the query API (search filters). */
  public static final int MAX_JSON_BODY_BYTES = 256 * 1024;

  /** 14 §7.2, §4.6: a free-text filter value (UTF-8 bytes). */
  public static final int MAX_TEXT_FILTER_BYTES = 1024;

  /** 14 §7.2: values of an array parameter ({@code instance[]}, {@code service[]}). */
  public static final int MAX_ARRAY_VALUES = 100;

  /** 14 §7.2, §4.10: metric names per request ({@code names[]}). */
  public static final int MAX_METRIC_NAMES = 50;

  /** 14 §7.2, DSN-14-15: ranges read from raw rows (XLog, search, traces by default). */
  public static final Duration RAW_RANGE_MAX = Duration.ofHours(24);

  /** 14 §7.2, DSN-14-15: series and statistics ranges (1 year, a calendar year fits). */
  public static final Duration SERIES_RANGE_MAX = SourceSelector.SERIES_MAX;

  /** Earliest accepted time (before every Argos epoch; rejects typos such as year 0202). */
  public static final Instant MIN_TIME = Instant.parse("2000-01-01T00:00:00Z");

  /** Latest accepted time. */
  public static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");

  /** Longest plain {@code user_id} (16 §6.3, ADR-0010 C-119, C-163). */
  public static final int USER_ID_MAX_CHARS = 128;

  private QueryLimits() {}

  /**
   * A required time parameter: ISO-8601 in UTC with {@code Z} (14 §2.4: no local times),
   * milliseconds allowed (finer digits are cut to milliseconds), between {@link #MIN_TIME} and
   * {@link #MAX_TIME}.
   *
   * @throws ApiException 400 {@code range_invalid} ({@code missing}, {@code invalid_format}, {@code
   *     out_of_bounds})
   */
  public static Instant time(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "A time parameter is missing")
          .detail(field, "missing");
    }
    return parseTime(field, value);
  }

  /** An optional time parameter; null when absent. */
  public static Instant optionalTime(String field, String value) {
    return value == null || value.isBlank() ? null : parseTime(field, value);
  }

  private static Instant parseTime(String field, String value) {
    String v = value.strip();
    if (v.length() > 40 || !(v.endsWith("Z") || v.endsWith("z"))) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "Times are ISO-8601 UTC (…Z)")
          .detail(field, "invalid_format");
    }
    Instant t;
    try {
      t = Instant.parse(v.substring(0, v.length() - 1) + "Z");
    } catch (DateTimeParseException e) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "Times are ISO-8601 UTC (…Z)")
          .detail(field, "invalid_format");
    }
    if (t.isBefore(MIN_TIME) || !t.isBefore(MAX_TIME)) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "The time is out of bounds")
          .detail(field, "out_of_bounds");
    }
    return t.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * Checks a half-open range {@code [from, to)} (14 §2.3).
   *
   * @throws ApiException 400 {@code range_invalid}: {@code to/before_from} unless {@code to} is
   *     after {@code from}, {@code to/too_long} beyond {@code max}
   */
  public static void range(Instant from, Instant to, Duration max) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!to.isAfter(from)) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "to must be after from")
          .detail("to", "before_from");
    }
    if (Duration.between(from, to).compareTo(max) > 0) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "The time range is longer than allowed")
          .detail("to", "too_long");
    }
  }

  /**
   * A {@code limit}-like parameter: {@code defaultValue} when absent.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code must_be_positive}) below 1, {@code
   *     limit_exceeded} ({@code above_max}) above {@code max}
   */
  public static int limit(String field, Integer value, int defaultValue, int max) {
    if (value == null) {
      return defaultValue;
    }
    if (value < 1) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The value must be positive")
          .detail(field, "must_be_positive");
    }
    if (value > max) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The value is above the limit")
          .detail(field, "above_max");
    }
    return value;
  }

  /**
   * A free-text filter: null stays null, otherwise at most {@value #MAX_TEXT_FILTER_BYTES} UTF-8
   * bytes.
   *
   * @throws ApiException 400 {@code limit_exceeded} ({@code too_long})
   */
  public static String text(String field, String value) {
    if (value == null) {
      return null;
    }
    if (value.length() > MAX_TEXT_FILTER_BYTES
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_FILTER_BYTES) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "A text filter is longer than 1 KB")
          .detail(field, "too_long");
    }
    return value;
  }

  /**
   * An array parameter: empty when absent, at most {@code max} values.
   *
   * @throws ApiException 400 {@code limit_exceeded} ({@code too_many_values})
   */
  public static <T> List<T> array(String field, List<T> values, int max) {
    if (values == null) {
      return List.of();
    }
    if (values.size() > max) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "Too many values")
          .detail(field, "too_many_values");
    }
    return values;
  }

  /**
   * A plain {@code user_id} filter normalized as the agent normalizes the collected value (ADR-0010
   * C-163 (2); agent {@code UserIds.sanitize}): ISO control characters removed, cut at {@value
   * #USER_ID_MAX_CHARS} characters (never inside a surrogate pair), trimmed. Null when nothing is
   * left.
   */
  public static String userIdFilter(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), USER_ID_MAX_CHARS));
    for (int i = 0; i < value.length() && out.length() < USER_ID_MAX_CHARS; i++) {
      char c = value.charAt(i);
      if (!Character.isISOControl(c)) {
        out.append(c);
      }
    }
    int length = out.length();
    if (length > 0 && Character.isHighSurrogate(out.charAt(length - 1))) {
      out.setLength(length - 1);
    }
    String v = out.toString().trim();
    return v.isEmpty() ? null : v;
  }

  /**
   * A {@code sort} parameter {@code field:asc|desc} (or {@code field}, descending); {@code
   * defaultSort} when absent.
   *
   * @throws ApiException 400 {@code unsupported_sort} ({@code unknown_field}, {@code
   *     unknown_direction})
   */
  public static Sort sort(String value, Set<String> allowed, Sort defaultSort) {
    if (value == null || value.isBlank()) {
      return defaultSort;
    }
    String v = value.strip();
    int colon = v.indexOf(':');
    String field = colon < 0 ? v : v.substring(0, colon);
    String direction = colon < 0 ? "desc" : v.substring(colon + 1).toLowerCase(Locale.ROOT);
    if (!allowed.contains(field)) {
      throw new ApiException(ErrorCode.UNSUPPORTED_SORT).detail("sort", "unknown_field");
    }
    if (!direction.equals("asc") && !direction.equals("desc")) {
      throw new ApiException(ErrorCode.UNSUPPORTED_SORT).detail("sort", "unknown_direction");
    }
    return new Sort(field, direction.equals("desc"));
  }

  /**
   * The {@code kind} parameter (D-20): {@code HTTP} by default.
   *
   * @throws ApiException 400 {@code filter_invalid} ({@code kind/unsupported_value})
   */
  public static TxKind kind(String value) {
    if (value == null || value.isBlank()) {
      return TxKind.HTTP;
    }
    try {
      return TxKind.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("kind", "unsupported_value");
    }
  }

  /**
   * A sort order.
   *
   * @param field the API field name (one of the endpoint's allowed fields)
   * @param descending whether descending
   */
  public record Sort(String field, boolean descending) {}

  /** The transaction kinds of {@code kind} (14 §2.3, D-20). */
  public enum TxKind {
    /** HTTP requests (the default). */
    HTTP,
    /** Batch jobs. */
    BATCH,
    /** Messages. */
    MESSAGE,
    /** Every kind. */
    ALL;

    /** The {@code Enum8} values of the ClickHouse {@code kind} column for this choice. */
    public List<String> storedValues() {
      return this == ALL ? List.of("HTTP", "BATCH", "MESSAGE") : List.of(name());
    }
  }
}
