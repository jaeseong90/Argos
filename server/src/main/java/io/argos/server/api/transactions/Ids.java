/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The 64-bit identifiers of the transaction API and their text forms (14 §4.5, §4.6, §4.7):
 *
 * <ul>
 *   <li>{@code tx_id} and dictionary keys ({@code service_id}, {@code sql_id}, {@code
 *       error_class_id}, {@code error_group_id}, {@code version_id}): unsigned decimal strings.
 *       JSON numbers cannot carry 64 bits in JavaScript (2^53), and a dictionary key is a uniform
 *       xxh3 hash, so almost every key is above 2^53.
 *   <li>{@code trace_id}: 32 lower-case hex digits; {@code span_id}: 16.
 *   <li>The start time in a {@code tx_id}: {@code (tx_id >> 23) + 2026-01-01T00:00:00Z} in ms (10
 *       §9.5, D-11, ADR-0010 C-121).
 * </ul>
 *
 * Malformed input is 400 {@code invalid_request} with the field and {@code invalid_format}; the
 * value is never echoed.
 */
public final class Ids {

  /** The epoch of the time prefix of a {@code tx_id}: 2026-01-01T00:00:00Z. */
  public static final long TX_EPOCH_MS = 1_767_225_600_000L;

  private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,20}");
  private static final Pattern TRACE = Pattern.compile("[0-9a-fA-F]{32}");

  private Ids() {}

  /**
   * An unsigned 64-bit decimal ({@code tx_id}, a dictionary key) as its 64 bits.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code <field>/invalid_format})
   */
  public static long parseUnsigned(String field, String text) {
    if (text == null || !DECIMAL.matcher(text).matches()) {
      throw invalid(field);
    }
    try {
      return Long.parseUnsignedLong(text);
    } catch (NumberFormatException e) {
      throw invalid(field);
    }
  }

  /** {@link #parseUnsigned} of every element; empty for null. */
  public static List<Long> parseUnsigned(String field, List<String> texts) {
    List<Long> out = new ArrayList<>();
    if (texts != null) {
      for (String t : texts) {
        out.add(parseUnsigned(field, t));
      }
    }
    return out;
  }

  /** The unsigned decimal text of 64 bits. */
  public static String text(long bits) {
    return Long.toUnsignedString(bits);
  }

  /** The text of a dictionary key, null for key 0 ("none"). */
  public static String keyOrNull(long bits) {
    return bits == 0 ? null : Long.toUnsignedString(bits);
  }

  /** The start time restored from a {@code tx_id} (its 41-bit ms prefix). */
  public static Instant restoredTime(long txId) {
    return Instant.ofEpochMilli((txId >>> 23) + TX_EPOCH_MS);
  }

  /**
   * A trace id: 32 hex digits, not all zero (W3C), returned in lower case.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code <field>/invalid_format})
   */
  public static String traceId(String field, String text) {
    if (text == null || !TRACE.matcher(text).matches() || text.chars().allMatch(c -> c == '0')) {
      throw invalid(field);
    }
    return text.toLowerCase(Locale.ROOT);
  }

  /** Whether {@code text} has the form of a trace id. */
  public static boolean isTraceId(String text) {
    return text != null && TRACE.matcher(text).matches();
  }

  /** A span id as 16 hex digits, null for 0 (no parent). */
  public static String spanHex(long span) {
    return span == 0 ? null : HexFormat.of().toHexDigits(span);
  }

  /**
   * The Unix seconds in the first 4 bytes of an Argos-generated trace id (10 §9.5), or null when
   * the value is not a plausible time (an external id: its first bytes are random): at or after
   * {@code earliest} and before {@code latest}.
   */
  public static Instant traceTimeHint(String traceHex, Instant earliest, Instant latest) {
    long seconds = Long.parseLong(traceHex.substring(0, 8), 16);
    Instant t = Instant.ofEpochSecond(seconds);
    return t.isBefore(earliest) || !t.isBefore(latest) ? null : t;
  }

  private static ApiException invalid(String field) {
    return new ApiException(ErrorCode.INVALID_REQUEST, "An identifier is malformed")
        .detail(field, "invalid_format");
  }
}
