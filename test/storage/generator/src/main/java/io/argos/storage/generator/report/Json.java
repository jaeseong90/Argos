/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON serializer for the manifest (maps, collections, strings, numbers, booleans, null).
 * Maps keep their iteration order; output is indented with two spaces.
 */
public final class Json {

  private Json() {}

  /** Serializes {@code value}. */
  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value, 0);
    sb.append('\n');
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object value, int indent) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      string(sb, s);
    } else if (value instanceof Double d) {
      sb.append(number(d));
    } else if (value instanceof Float f) {
      sb.append(number(f.doubleValue()));
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        pad(sb, indent + 2);
        string(sb, String.valueOf(e.getKey()));
        sb.append(": ");
        write(sb, e.getValue(), indent + 2);
        sb.append(++i < map.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append('}');
    } else if (value instanceof Collection<?> list) {
      if (list.isEmpty()) {
        sb.append("[]");
        return;
      }
      boolean scalars = list.stream().noneMatch(v -> v instanceof Map || v instanceof Collection);
      if (scalars) {
        sb.append('[');
        int i = 0;
        for (Object v : list) {
          write(sb, v, indent);
          if (++i < list.size()) {
            sb.append(", ");
          }
        }
        sb.append(']');
        return;
      }
      sb.append("[\n");
      int i = 0;
      for (Object v : list) {
        pad(sb, indent + 2);
        write(sb, v, indent + 2);
        sb.append(++i < list.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append(']');
    } else {
      string(sb, value.toString());
    }
  }

  private static String number(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return "null";
    }
    if (d == Math.rint(d) && Math.abs(d) < 1e15) {
      return Long.toString((long) d);
    }
    return String.format(Locale.ROOT, "%.6g", d).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  private static void pad(StringBuilder sb, int n) {
    sb.append(" ".repeat(n));
  }

  private static void string(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
