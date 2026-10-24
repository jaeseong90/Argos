/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer for ext-stub answers (strings, numbers, booleans, maps, lists, null). */
final class Json {

  private Json() {}

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  private static void append(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      string(out, s);
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<?, ?> entry = it.next();
        string(out, String.valueOf(entry.getKey()));
        out.append(':');
        append(out, entry.getValue());
        if (it.hasNext()) {
          out.append(',');
        }
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        append(out, list.get(i));
      }
      out.append(']');
    } else {
      string(out, value.toString());
    }
  }

  /** Quoted string; HTML-significant characters are escaped too (answers may carry payloads). */
  static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == '\'') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
