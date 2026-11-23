/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.util.Locale;

/**
 * W3C Trace Context level 1 {@code traceparent} parsing and formatting (10 §8, F-A06, F-A32), the
 * HOST dictionary text of an outgoing call (ADR-0010 C-142) and the HTTP step detail (10 §6.5).
 * Pure functions on bounded input: nothing here allocates more than its result, nothing throws.
 *
 * <p>Accepted {@code traceparent} (anything else is ignored and a new trace begins):
 *
 * <ul>
 *   <li>{@code version-traceid-parentid-flags}: 2, 32, 16 and 2 lower-case hex digits joined by
 *       {@code -}; upper-case hex is rejected (W3C: "MUST be lowercase");
 *   <li>version {@code ff} is invalid; version {@code 00} is exactly {@value #TRACEPARENT_LENGTH}
 *       characters; a later version may carry more fields after a {@code -} at index 55 (W3C
 *       forward compatibility), at most {@value #MAX_TRACEPARENT_CHARS} characters;
 *   <li>a trace id or parent id of all zeros is invalid.
 * </ul>
 */
public final class Propagation {
  /** Length of a version {@code 00} {@code traceparent}. */
  public static final int TRACEPARENT_LENGTH = 55;

  /** Characters of a {@code traceparent} of a later version that are still read. */
  public static final int MAX_TRACEPARENT_CHARS = 256;

  /** The step detail is cut here (10 §9.1 detail bound, 11 {@code ProfileStep.detail} 4 KiB). */
  public static final int MAX_DETAIL_CHARS = 4096;

  /** Characters of a host name that are kept at most (DNS: 253; IPv6 text with zone: less). */
  public static final int MAX_HOST_CHARS = 255;

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Propagation() {}

  /**
   * The ids of a valid {@code traceparent}: {@code [traceIdHigh, traceIdLow, parentSpanId]}, or
   * null when the value is absent or invalid (see the class comment).
   */
  public static long[] parseTraceparent(String value) {
    if (value == null) {
      return null;
    }
    int length = value.length();
    if (length < TRACEPARENT_LENGTH || length > MAX_TRACEPARENT_CHARS) {
      return null;
    }
    int version = hexByte(value, 0);
    if (version < 0 || version == 0xff) {
      return null;
    }
    if (version == 0) {
      if (length != TRACEPARENT_LENGTH) {
        return null;
      }
    } else if (length > TRACEPARENT_LENGTH && value.charAt(TRACEPARENT_LENGTH) != '-') {
      return null;
    }
    if (value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
      return null;
    }
    if (hexByte(value, 53) < 0 || !isHex(value, 3, 32) || !isHex(value, 36, 16)) {
      return null;
    }
    long high = hex64(value, 3);
    long low = hex64(value, 19);
    long parent = hex64(value, 36);
    if ((high == 0 && low == 0) || parent == 0) {
      return null;
    }
    return new long[] {high, low, parent};
  }

  /** {@code 00-<trace id>-<span id>-01} (sampled flag set, 10 §8). */
  public static String formatTraceparent(long traceHigh, long traceLow, long spanId) {
    char[] out = new char[TRACEPARENT_LENGTH];
    out[0] = '0';
    out[1] = '0';
    out[2] = '-';
    write16(out, 3, traceHigh);
    write16(out, 19, traceLow);
    out[35] = '-';
    write16(out, 36, spanId);
    out[52] = '-';
    out[53] = '0';
    out[54] = '1';
    return new String(out);
  }

  /** Lower-case hex of {@code value} without leading zeros ({@code 0} for 0). */
  public static String hex(long value) {
    return Long.toHexString(value);
  }

  /**
   * The host as compared with the {@code propagation.*} globs and written in the HOST text:
   * trimmed, lower case ({@link Locale#ROOT}), without IPv6 brackets, user info and a trailing dot;
   * null when nothing is left or it is longer than {@value #MAX_HOST_CHARS} characters.
   */
  public static String normalizeHost(String host) {
    if (host == null || host.length() > 4 * MAX_HOST_CHARS) {
      return null;
    }
    String h = host.trim();
    int at = h.lastIndexOf('@');
    if (at >= 0) {
      h = h.substring(at + 1);
    }
    if (h.length() >= 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
      h = h.substring(1, h.length() - 1);
    }
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    if (h.isEmpty() || h.length() > MAX_HOST_CHARS) {
      return null;
    }
    for (int i = 0; i < h.length(); i++) {
      char c = h.charAt(i);
      if (c <= ' ' || c == '/' || c == '?' || c == '#' || c >= 0x7f) {
        return null;
      }
    }
    return h.toLowerCase(Locale.ROOT);
  }

  /**
   * The HOST dictionary text of ADR-0010 C-142: {@code scheme://host:port}, scheme and host lower
   * case, IPv6 addresses in brackets, no user info, path or query. A {@code port} below 1 is the
   * scheme's default port ({@code http}/{@code ws} 80, {@code https}/{@code wss} 443), written out;
   * a scheme without a known default port and no port is written {@code scheme://host}. A null or
   * empty scheme is {@code http}. Null when the host is not usable ({@link #normalizeHost}).
   */
  public static String hostText(String scheme, String host, int port) {
    String h = normalizeHost(host);
    if (h == null) {
      return null;
    }
    String s = scheme == null ? "" : scheme.trim().toLowerCase(Locale.ROOT);
    if (s.isEmpty() || s.length() > 32 || !schemeChars(s)) {
      s = "http";
    }
    int p = port > 0 && port <= 65_535 ? port : defaultPort(s);
    StringBuilder out = new StringBuilder(s.length() + h.length() + 12);
    out.append(s).append("://");
    if (h.indexOf(':') >= 0) {
      out.append('[').append(h).append(']');
    } else {
      out.append(h);
    }
    if (p > 0) {
      out.append(':').append(p);
    }
    return out.toString();
  }

  /** 80 for {@code http} and {@code ws}, 443 for {@code https} and {@code wss}, else 0. */
  static int defaultPort(String scheme) {
    if ("http".equals(scheme) || "ws".equals(scheme)) {
      return 80;
    }
    if ("https".equals(scheme) || "wss".equals(scheme)) {
      return 443;
    }
    return 0;
  }

  private static boolean schemeChars(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /**
   * The HTTP step detail of 10 §6.5 and §8: {@code METHOD hostPort/path}, where the path loses its
   * query string, fragment and {@code ;} path parameters, then {@code " traceparent=<value>"} when
   * the application's own {@code traceparent} was kept; cut at {@value #MAX_DETAIL_CHARS}
   * characters. Control characters become {@code ?}. The caller passes {@code hostPort} as the URL
   * shows it ({@code host[:port]}, no user info).
   */
  public static String detail(
      String method, String hostPort, String rawPath, String preservedTraceparent) {
    StringBuilder out = new StringBuilder(64);
    String m = method == null || method.isEmpty() ? "GET" : method;
    appendClean(out, m, 32);
    out.append(' ');
    if (hostPort != null) {
      String hp = hostPort;
      int at = hp.lastIndexOf('@');
      if (at >= 0) {
        hp = hp.substring(at + 1);
      }
      appendClean(out, hp, 300);
    }
    String path = cleanPath(rawPath);
    if (path.isEmpty() || path.charAt(0) != '/') {
      out.append('/');
    }
    appendClean(out, path, MAX_DETAIL_CHARS);
    if (preservedTraceparent != null && !preservedTraceparent.isEmpty()) {
      out.append(" traceparent=");
      appendClean(out, preservedTraceparent, MAX_TRACEPARENT_CHARS);
    }
    return out.length() > MAX_DETAIL_CHARS ? out.substring(0, MAX_DETAIL_CHARS) : out.toString();
  }

  /** The path without query, fragment and {@code ;} parameters of each segment. */
  static String cleanPath(String rawPath) {
    if (rawPath == null) {
      return "";
    }
    int end = rawPath.length();
    int q = rawPath.indexOf('?');
    if (q >= 0) {
      end = q;
    }
    int f = rawPath.indexOf('#');
    if (f >= 0 && f < end) {
      end = f;
    }
    if (end > MAX_DETAIL_CHARS) {
      end = MAX_DETAIL_CHARS;
    }
    StringBuilder out = null;
    int i = 0;
    while (i < end) {
      char c = rawPath.charAt(i);
      if (c == ';') {
        if (out == null) {
          out = new StringBuilder(end);
          out.append(rawPath, 0, i);
        }
        int next = rawPath.indexOf('/', i);
        i = next < 0 || next > end ? end : next;
        continue;
      }
      if (out != null) {
        out.append(c);
      }
      i++;
    }
    return out != null ? out.toString() : rawPath.substring(0, end);
  }

  private static void appendClean(StringBuilder out, String text, int max) {
    int n = Math.min(text.length(), max);
    for (int i = 0; i < n; i++) {
      char c = text.charAt(i);
      out.append(c < ' ' || c == 0x7f ? '?' : c);
    }
  }

  /** Two lower-case hex digits at {@code index} as 0..255, or -1. */
  private static int hexByte(String s, int index) {
    int hi = digit(s.charAt(index));
    int lo = digit(s.charAt(index + 1));
    return hi < 0 || lo < 0 ? -1 : (hi << 4) | lo;
  }

  private static boolean isHex(String s, int offset, int count) {
    for (int i = 0; i < count; i++) {
      if (digit(s.charAt(offset + i)) < 0) {
        return false;
      }
    }
    return true;
  }

  /** 16 hex digits at {@code offset}, already checked with {@link #isHex}. */
  private static long hex64(String s, int offset) {
    long value = 0;
    for (int i = 0; i < 16; i++) {
      value = (value << 4) | digit(s.charAt(offset + i));
    }
    return value;
  }

  /** A lower-case hex digit's value, or -1 (upper case is not accepted, W3C). */
  static int digit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return -1;
  }

  private static void write16(char[] out, int offset, long value) {
    long rest = value;
    for (int i = 15; i >= 0; i--) {
      out[offset + i] = HEX[(int) (rest & 0xF)];
      rest >>>= 4;
    }
  }
}
