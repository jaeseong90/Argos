/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.proto.ProtocolLimits;

/**
 * Request headers and the request id of a transaction (16 §6.1, 02 §5.2, ADR-0010 C-124): what the
 * agent keeps of the values the servlet helper read for {@code capture.http-headers} and {@code
 * capture.request-id-header}. Nothing is read unless configured (both are empty by default).
 *
 * <ul>
 *   <li>Headers: at most {@value #MAX_HEADERS} name, value pairs (11 {@code headers} cap), names
 *       lower case and at most {@value #MAX_NAME_CHARS} characters, values cut at {@value
 *       #MAX_VALUE_CHARS} characters (1 KiB, 10 §14); pairs without a value are dropped. The
 *       transport cuts the UTF-8 bytes again ({@code ProtocolLimits}).
 *   <li>Request id: one value, cut at {@value #MAX_REQUEST_ID_CHARS} characters (128 B on the
 *       wire), sent only as {@code TransactionRecord.request_id}.
 * </ul>
 */
public final class HeaderCapture {
  /** Header pairs kept per transaction (11 {@code headers}, {@code ProtocolLimits}). */
  public static final int MAX_HEADERS = ProtocolLimits.MAX_HEADERS;

  /** Characters of a header name. */
  public static final int MAX_NAME_CHARS = ProtocolLimits.MAX_KEY_BYTES;

  /** Characters of a header value (1 KiB, 10 §14). */
  public static final int MAX_VALUE_CHARS = ProtocolLimits.MAX_VALUE_BYTES;

  /** Characters of the request id (128 B, C-124). */
  public static final int MAX_REQUEST_ID_CHARS = ProtocolLimits.MAX_REQUEST_ID_BYTES;

  private static final String[] NONE = new String[0];

  private HeaderCapture() {}

  /**
   * The pairs of {@code namesAndValues} to keep: names lower case, values cut, pairs without a name
   * or value dropped, at most {@value #MAX_HEADERS} pairs; an empty array when none is left.
   */
  public static String[] headers(String[] namesAndValues) {
    if (namesAndValues == null || namesAndValues.length < 2) {
      return NONE;
    }
    int pairs = Math.min(namesAndValues.length / 2, MAX_HEADERS);
    String[] kept = new String[pairs * 2];
    int count = 0;
    for (int i = 0; i + 1 < namesAndValues.length && count < pairs; i += 2) {
      String name = namesAndValues[i];
      String value = namesAndValues[i + 1];
      if (name == null || value == null) {
        continue;
      }
      String key = HttpPaths.toLowerAscii(name.trim());
      if (key.isEmpty()) {
        continue;
      }
      kept[2 * count] = cut(key, MAX_NAME_CHARS);
      kept[2 * count + 1] = cut(value, MAX_VALUE_CHARS);
      count++;
    }
    if (count == 0) {
      return NONE;
    }
    if (count == pairs) {
      return kept;
    }
    String[] trimmed = new String[count * 2];
    System.arraycopy(kept, 0, trimmed, 0, trimmed.length);
    return trimmed;
  }

  /** {@code value} trimmed and cut at {@value #MAX_REQUEST_ID_CHARS} characters, or null. */
  public static String requestId(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : cut(trimmed, MAX_REQUEST_ID_CHARS);
  }

  /** {@code value} cut at {@code max} characters, never inside a surrogate pair. */
  static String cut(String value, int max) {
    if (value.length() <= max) {
      return value;
    }
    int end = max;
    if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }
}
