/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * UTF-8 byte caps of the protocol (11 §4.4: "에이전트가 먼저 자르고 서버가 다시 검사한다"): strings are cut at a
 * character boundary so that their UTF-8 encoding fits, never in the middle of a surrogate pair.
 */
final class Utf8 {
  private Utf8() {}

  /**
   * {@code value} cut so that its UTF-8 encoding has at most {@code maxBytes} bytes; null as "".
   */
  static String cut(String value, int maxBytes) {
    if (value == null) {
      return "";
    }
    int length = value.length();
    if ((long) length * 3 <= maxBytes) {
      return value;
    }
    int bytes = 0;
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      int width;
      int chars = 1;
      if (c < 0x80) {
        width = 1;
      } else if (c < 0x800) {
        width = 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        width = 4;
        chars = 2;
      } else {
        width = 3;
      }
      if (bytes + width > maxBytes) {
        return value.substring(0, i);
      }
      bytes += width;
      i += chars - 1;
    }
    return value;
  }

  /** True when {@link #cut(String, int)} would shorten {@code value}. */
  static boolean exceeds(String value, int maxBytes) {
    return value != null && cut(value, maxBytes).length() < value.length();
  }
}
