/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON writer for the summaries (maps, lists, strings, numbers, booleans, null). Doubles
 * are written with six significant digits in plain notation (a rate of 0.0004 stays 0.0004); NaN
 * and infinities become null.
 */
final class Json {

  private static final MathContext DIGITS = new MathContext(6, RoundingMode.HALF_EVEN);

  private Json() {}

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    return out.append('\n').toString();
  }

  private static void write(StringBuilder out, Object value, int depth) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String text) {
      string(out, text);
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        out.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        out.append((long) d);
      } else {
        out.append(new BigDecimal(d).round(DIGITS).stripTrailingZeros().toPlainString());
      }
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      object(out, map, depth);
    } else if (value instanceof List<?> list) {
      array(out, list, depth);
    } else {
      string(out, value.toString());
    }
  }

  private static void object(StringBuilder out, Map<?, ?> map, int depth) {
    if (map.isEmpty()) {
      out.append("{}");
      return;
    }
    out.append("{\n");
    Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<?, ?> entry = it.next();
      indent(out, depth + 1);
      string(out, String.valueOf(entry.getKey()));
      out.append(": ");
      write(out, entry.getValue(), depth + 1);
      out.append(it.hasNext() ? ",\n" : "\n");
    }
    indent(out, depth);
    out.append('}');
  }

  private static void array(StringBuilder out, List<?> list, int depth) {
    if (list.isEmpty()) {
      out.append("[]");
      return;
    }
    out.append("[\n");
    for (int i = 0; i < list.size(); i++) {
      indent(out, depth + 1);
      write(out, list.get(i), depth + 1);
      out.append(i + 1 < list.size() ? ",\n" : "\n");
    }
    indent(out, depth);
    out.append(']');
  }

  private static void indent(StringBuilder out, int depth) {
    out.append("  ".repeat(depth));
  }

  private static void string(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
