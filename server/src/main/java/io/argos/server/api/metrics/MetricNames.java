/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parts of a stored metric series name (13 §3.3: the OpenMetrics name with its labels, e.g.
 * {@code jvm_memory_used_bytes{area="heap"}}; 02 §7, DSN-02-20): the base name, the labels and the
 * unit its suffix names.
 */
public final class MetricNames {

  /** Longest series name (11 §4.4 {@code MetricSeries.name} ≤ 256 B). */
  public static final int MAX_NAME_BYTES = 256;

  private MetricNames() {}

  /** The name before the label part (the first opening brace). */
  public static String base(String series) {
    int brace = series.indexOf('{');
    return brace < 0 ? series : series.substring(0, brace);
  }

  /**
   * The labels of {@code name{a="x",b="y"}} in order; a value's {@code \"} and {@code \\} are
   * unescaped. Empty without labels or for a malformed label part.
   */
  public static Map<String, String> labels(String series) {
    Map<String, String> out = new LinkedHashMap<>();
    int open = series.indexOf('{');
    if (open < 0 || !series.endsWith("}")) {
      return out;
    }
    String body = series.substring(open + 1, series.length() - 1);
    int i = 0;
    while (i < body.length()) {
      int eq = body.indexOf('=', i);
      if (eq < 0 || eq + 1 >= body.length() || body.charAt(eq + 1) != '"') {
        return new LinkedHashMap<>();
      }
      String key = body.substring(i, eq).strip();
      StringBuilder value = new StringBuilder();
      int j = eq + 2;
      boolean closed = false;
      while (j < body.length()) {
        char c = body.charAt(j);
        if (c == '\\' && j + 1 < body.length()) {
          value.append(body.charAt(j + 1));
          j += 2;
          continue;
        }
        if (c == '"') {
          closed = true;
          j++;
          break;
        }
        value.append(c);
        j++;
      }
      if (!closed || key.isEmpty()) {
        return new LinkedHashMap<>();
      }
      out.put(key, value.toString());
      while (j < body.length() && (body.charAt(j) == ',' || body.charAt(j) == ' ')) {
        j++;
      }
      i = j;
    }
    return out;
  }

  /**
   * The unit of a base name by its OpenMetrics suffix: {@code bytes}, {@code seconds}, {@code
   * ratio}, {@code ms}; null when the name does not say ({@code _total} counters without a unit).
   */
  public static String unit(String base) {
    String b =
        base.endsWith("_total") ? base.substring(0, base.length() - "_total".length()) : base;
    if (b.endsWith("_bytes")) {
      return "bytes";
    }
    if (b.endsWith("_seconds")) {
      return "seconds";
    }
    if (b.endsWith("_ratio") || b.endsWith("_usage")) {
      return "ratio";
    }
    if (b.endsWith("_ms")) {
      return "ms";
    }
    return null;
  }
}
