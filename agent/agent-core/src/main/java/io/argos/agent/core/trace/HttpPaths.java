/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Request path handling shared by the exclusion rules, the recorded path and the fallback service
 * name (10 §6.1, §6.2, §7.2, F-A29). Paths never carry a query string here: the servlet helper
 * passes {@code getRequestURI()}.
 *
 * <p>The recorded URL ({@link #recorded}, 16 §6.4, SEC-34) is the only place a query string may
 * appear, by {@code capture.query-string}: {@code off} (default) keeps the path only, {@code
 * whitelist} re-assembles the listed parameters ({@code ?a=1&b=2}, sorted by name, values cut at
 * {@value #QUERY_VALUE_MAX_CHARS} characters), {@code full} keeps the raw query. The result is cut
 * at {@value #MAX_URL_CHARS} characters (11 {@code url_path} 2 KiB; the transport cuts the UTF-8
 * bytes again). Service names and dictionaries are built from the path alone.
 */
public final class HttpPaths {
  /** Placeholder of a normalized path segment (F-A29). */
  public static final String PLACEHOLDER = "{*}";

  /** Hex segments at least this long are identifiers (F-A29 "16진 32자 이상"). */
  static final int MIN_HEX_ID_LENGTH = 32;

  /** Upper bound of the recorded URL, path and query string (16 §6.4, 11 {@code url_path}). */
  public static final int MAX_URL_CHARS = 2048;

  /** Characters of one parameter value kept in {@code whitelist} mode (16 §6.4). */
  public static final int QUERY_VALUE_MAX_CHARS = 256;

  /** Parameters looked at in {@code whitelist} mode; the rest of a longer query is ignored. */
  static final int MAX_QUERY_PARAMETERS = 256;

  private HttpPaths() {}

  /**
   * The URL recorded for a request (16 §6.4): the clean {@code path} (path parameters removed),
   * plus {@code ?} and the query by {@code mode} ({@link CaptureConfig#QUERY_STRING_OFF}, {@link
   * CaptureConfig#QUERY_STRING_WHITELIST} with {@code whitelist}, {@link
   * CaptureConfig#QUERY_STRING_FULL}), cut at {@value #MAX_URL_CHARS} characters.
   */
  public static String recorded(String path, String query, int mode, String[] whitelist) {
    String clean = clean(path);
    String kept = null;
    if (query != null && !query.isEmpty()) {
      if (mode == CaptureConfig.QUERY_STRING_FULL) {
        kept = query;
      } else if (mode == CaptureConfig.QUERY_STRING_WHITELIST) {
        kept = whitelisted(query, whitelist);
      }
    }
    String url = kept == null || kept.isEmpty() ? clean : clean + "?" + kept;
    return url.length() > MAX_URL_CHARS ? url.substring(0, MAX_URL_CHARS) : url;
  }

  /**
   * The parameters of {@code query} whose (raw, not decoded) name is in {@code names}, sorted by
   * name (stable: repeated names keep their order), each value cut at {@value
   * #QUERY_VALUE_MAX_CHARS} characters, joined with {@code &}; null when none is kept. Values stay
   * as the client encoded them.
   */
  static String whitelisted(String query, String[] names) {
    if (names == null || names.length == 0) {
      return null;
    }
    List<String[]> kept = new ArrayList<String[]>();
    int start = 0;
    int seen = 0;
    int length = query.length();
    while (start <= length && seen < MAX_QUERY_PARAMETERS) {
      int end = query.indexOf('&', start);
      if (end < 0) {
        end = length;
      }
      if (end > start) {
        seen++;
        int equals = query.indexOf('=', start);
        boolean hasValue = equals >= 0 && equals < end;
        String name = query.substring(start, hasValue ? equals : end);
        if (contains(names, name)) {
          String value = hasValue ? query.substring(equals + 1, end) : null;
          if (value != null && value.length() > QUERY_VALUE_MAX_CHARS) {
            value = value.substring(0, QUERY_VALUE_MAX_CHARS);
          }
          kept.add(new String[] {name, value});
        }
      }
      start = end + 1;
    }
    if (kept.isEmpty()) {
      return null;
    }
    Collections.sort(
        kept,
        new Comparator<String[]>() {
          @Override
          public int compare(String[] a, String[] b) {
            return a[0].compareTo(b[0]);
          }
        });
    StringBuilder out = new StringBuilder();
    for (String[] parameter : kept) {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(parameter[0]);
      if (parameter[1] != null) {
        out.append('=').append(parameter[1]);
      }
    }
    return out.toString();
  }

  private static boolean contains(String[] names, String name) {
    for (String candidate : names) {
      if (candidate != null && candidate.equals(name)) {
        return true;
      }
    }
    return false;
  }

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
