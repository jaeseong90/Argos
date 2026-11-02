/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON writer and parser (no third-party dependency). Objects become {@link LinkedHashMap},
 * arrays {@link ArrayList}, integral numbers {@link Long}, other numbers {@link Double}. Non-finite
 * doubles are written as {@code null}.
 */
final class Json {
  private Json() {}

  /** Pretty-printed JSON of maps, collections, strings, numbers, booleans and null. */
  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    return out.append('\n').toString();
  }

  private static void write(StringBuilder out, Object value, int indent) {
    switch (value) {
      case null -> out.append("null");
      case String s -> quote(out, s);
      case Boolean b -> out.append(b.booleanValue());
      case Double d -> number(out, d);
      case Float f -> number(out, f.doubleValue());
      case Number n -> out.append(n.longValue());
      case Map<?, ?> map -> {
        if (map.isEmpty()) {
          out.append("{}");
          return;
        }
        out.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          pad(out, indent + 2);
          quote(out, String.valueOf(entry.getKey()));
          out.append(": ");
          write(out, entry.getValue(), indent + 2);
          out.append(++i < map.size() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append('}');
      }
      case Collection<?> list -> array(out, new ArrayList<Object>(list), indent);
      case double[] array -> {
        List<Object> list = new ArrayList<>();
        for (double d : array) {
          list.add(d);
        }
        array(out, list, indent);
      }
      default -> quote(out, value.toString());
    }
  }

  private static void array(StringBuilder out, List<Object> list, int indent) {
    if (list.isEmpty()) {
      out.append("[]");
      return;
    }
    // Arrays of scalars stay on one line (timelines, value lists).
    boolean scalars = true;
    for (Object item : list) {
      if (item instanceof Map || item instanceof Collection) {
        scalars = false;
        break;
      }
    }
    if (scalars) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          out.append(", ");
        }
        write(out, list.get(i), indent);
      }
      out.append(']');
      return;
    }
    out.append("[\n");
    for (int i = 0; i < list.size(); i++) {
      pad(out, indent + 2);
      write(out, list.get(i), indent + 2);
      out.append(i + 1 < list.size() ? ",\n" : "\n");
    }
    pad(out, indent);
    out.append(']');
  }

  private static void number(StringBuilder out, double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      out.append("null");
    } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
      out.append((long) d);
    } else {
      out.append(d);
    }
  }

  private static void pad(StringBuilder out, int n) {
    out.append(" ".repeat(n));
  }

  private static void quote(StringBuilder out, String s) {
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

  /** Parses one JSON value. */
  static Object parse(String text) {
    Parser parser = new Parser(text);
    Object value = parser.value();
    parser.skipSpace();
    if (parser.pos != text.length()) {
      throw parser.error("trailing characters");
    }
    return value;
  }

  /** The object {@code value}, or an empty map when it is not an object. */
  static Map<String, Object> object(Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> m) {
      m.forEach((k, v) -> map.put(String.valueOf(k), v));
    }
    return map;
  }

  /** The array {@code value}, or an empty list when it is not an array. */
  static List<Object> array(Object value) {
    return value instanceof List<?> list ? new ArrayList<Object>(list) : new ArrayList<>();
  }

  /** The member at {@code path} below {@code root} (objects only), or null. */
  static Object at(Object root, String... path) {
    Object current = root;
    for (String key : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(key);
    }
    return current;
  }

  /** A number as double; NaN for null or non-numbers. */
  static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : Double.NaN;
  }

  /** The number at {@code path}, NaN when absent. */
  static double number(Object root, String... path) {
    return number(at(root, path));
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException("JSON " + message + " at offset " + pos);
    }

    void skipSpace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }

    Object value() {
      skipSpace();
      if (pos >= text.length()) {
        throw error("unexpected end");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> literal("true", true);
        case 'f' -> literal("false", false);
        case 'n' -> literal("null", null);
        default -> parseNumber();
      };
    }

    private Object literal(String word, Object result) {
      if (!text.startsWith(word, pos)) {
        throw error("unexpected token");
      }
      pos += word.length();
      return result;
    }

    private Map<String, Object> parseObject() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipSpace();
      if (pos < text.length() && text.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipSpace();
        if (pos >= text.length() || text.charAt(pos) != '"') {
          throw error("expected a member name");
        }
        String key = parseString();
        skipSpace();
        expect(':');
        map.put(key, value());
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == ',') {
          pos++;
          continue;
        }
        expect('}');
        return map;
      }
    }

    private List<Object> parseArray() {
      List<Object> list = new ArrayList<>();
      pos++;
      skipSpace();
      if (pos < text.length() && text.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(value());
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == ',') {
          pos++;
          continue;
        }
        expect(']');
        return list;
      }
    }

    private void expect(char c) {
      if (pos >= text.length() || text.charAt(pos) != c) {
        throw error("expected '" + c + "'");
      }
      pos++;
    }

    private String parseString() {
      StringBuilder out = new StringBuilder();
      pos++;
      while (pos < text.length()) {
        char c = text.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (pos >= text.length()) {
          break;
        }
        char e = text.charAt(pos++);
        switch (e) {
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            if (pos + 4 > text.length()) {
              throw error("bad escape");
            }
            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> out.append(e);
        }
      }
      throw error("unterminated string");
    }

    private Object parseNumber() {
      int start = pos;
      while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
        pos++;
      }
      String token = text.substring(start, pos);
      if (token.isEmpty()) {
        throw error("unexpected character");
      }
      if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
        try {
          return Long.parseLong(token);
        } catch (NumberFormatException e) {
          return Double.parseDouble(token);
        }
      }
      return Double.parseDouble(token);
    }
  }
}
