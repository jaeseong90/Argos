/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

/**
 * Request path handling shared by the exclusion rules, the recorded path and the fallback service
 * name (10 §6.1, §6.2, §7.2, F-A29). Paths never carry a query string here: the servlet helper
 * passes {@code getRequestURI()}.
 */
public final class HttpPaths {
  /** Placeholder of a normalized path segment (F-A29). */
  public static final String PLACEHOLDER = "{*}";

  /** Hex segments at least this long are identifiers (F-A29 "16진 32자 이상"). */
  static final int MIN_HEX_ID_LENGTH = 32;

  private HttpPaths() {}

  /**
   * Removes path parameters ({@code ;jsessionid=...}, matrix variables) from every segment; null or
   * empty becomes {@code /}.
   */
  public static String clean(String path) {
    if (path == null || path.isEmpty()) {
      return "/";
    }
    int semicolon = path.indexOf(';');
    if (semicolon < 0) {
      return path;
    }
    StringBuilder out = new StringBuilder(path.length());
    int i = 0;
    while (i < path.length()) {
      char c = path.charAt(i);
      if (c == ';') {
        int next = path.indexOf('/', i);
        if (next < 0) {
          break;
        }
        i = next;
        continue;
      }
      out.append(c);
      i++;
    }
    return out.length() == 0 ? "/" : out.toString();
  }

  /**
   * The lower-case extension of the last segment ({@code /static/app.JS} -> {@code js}), or null
   * when the last segment has no dot (or ends with one).
   */
  public static String extension(String path) {
    if (path == null) {
      return null;
    }
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    if (dot <= slash || dot == path.length() - 1) {
      return null;
    }
    return toLowerAscii(path.substring(dot + 1));
  }

  /**
   * F-A29 fallback normalization: every segment that is a number, a UUID or a hex string of at
   * least 32 characters becomes {@link #PLACEHOLDER} ({@code /api/orders/42} -> {@code
   * /api/orders/{*}}). Path parameters are removed first.
   */
  public static String normalize(String path) {
    String clean = clean(path);
    StringBuilder out = null;
    int start = 0;
    int length = clean.length();
    while (start <= length) {
      int end = clean.indexOf('/', start);
      if (end < 0) {
        end = length;
      }
      if (end > start && isIdentifier(clean, start, end)) {
        if (out == null) {
          out = new StringBuilder(length);
          out.append(clean, 0, start);
        }
        out.append(PLACEHOLDER);
      } else if (out != null) {
        out.append(clean, start, end);
      }
      if (end == length) {
        break;
      }
      if (out != null) {
        out.append('/');
      }
      start = end + 1;
    }
    return out == null ? clean : out.toString();
  }

  /** True when {@code s[start, end)} is a number, a UUID or a long hex identifier. */
  static boolean isIdentifier(String s, int start, int end) {
    int length = end - start;
    boolean digits = true;
    boolean hex = true;
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        digits = false;
      }
      if (!isHex(c)) {
        hex = false;
      }
    }
    if (digits || (hex && length >= MIN_HEX_ID_LENGTH)) {
      return true;
    }
    return length == 36 && isUuid(s, start);
  }

  private static boolean isUuid(String s, int start) {
    for (int i = 0; i < 36; i++) {
      char c = s.charAt(start + i);
      boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
      if (dash ? c != '-' : !isHex(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  static String toLowerAscii(String value) {
    StringBuilder out = null;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        if (out == null) {
          out = new StringBuilder(value);
        }
        out.setCharAt(i, (char) (c + ('a' - 'A')));
      }
    }
    return out == null ? value : out.toString();
  }
}
