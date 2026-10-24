/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer for the smoke runner (app answers and reports). Numbers without a
 * fraction or exponent become {@link Long}, others {@link Double}.
 */
final class MiniJson {

  private final String text;
  private int pos;

  private MiniJson(String text) {
    this.text = text;
  }

  /** Parses one JSON value; throws IllegalArgumentException on malformed input. */
  static Object parse(String text) {
    MiniJson parser = new MiniJson(text);
    parser.skipWhitespace();
    Object value = parser.value();
    parser.skipWhitespace();
    if (parser.pos != text.length()) {
      throw parser.error("trailing characters");
    }
    return value;
  }

  /** Parses a JSON object, or returns an empty map when the text is not a JSON object. */
  static Map<String, Object> parseObjectOrEmpty(String text) {
    try {
      Object value = parse(text);
      if (value instanceof Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
      }
    } catch (IllegalArgumentException e) {
      // Not JSON: the caller reports the missing field.
    }
    return new LinkedHashMap<>();
  }

  /** String form of a JSON value as used by catalog checks ("1", "true", "OK", "null"). */
  static String scalar(Object value) {
    if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
      return Long.toString(d.longValue());
    }
    return String.valueOf(value);
  }

  private Object value() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    char c = text.charAt(pos);
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", true);
      case 'f' -> literal("false", false);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = string();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, value());
      skipWhitespace();
      char c = next();
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("expected ',' or '}'");
      }
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    pos++;
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(value());
      skipWhitespace();
      char c = next();
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("expected ',' or ']'");
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder out = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return out.toString();
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char e = next();
      switch (e) {
        case '"', '\\', '/' -> out.append(e);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          if (pos + 4 > text.length()) {
            throw error("bad unicode escape");
          }
          out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw error("bad escape");
      }
    }
  }

  private Object number() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    String token = text.substring(start, pos);
    if (token.isEmpty()) {
      throw error("unexpected character");
    }
    try {
      if (token.contains(".") || token.contains("e") || token.contains("E")) {
        return Double.parseDouble(token);
      }
      return Long.parseLong(token);
    } catch (NumberFormatException e) {
      throw error("bad number " + token);
    }
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return text.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("expected '" + c + "'");
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException("JSON " + message + " at " + pos);
  }

  /** Writes maps, lists, strings, numbers, booleans and null as indented JSON. */
  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    return out.append('\n').toString();
  }

  private static void write(StringBuilder out, Object value, int indent) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      quote(out, s);
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<?, ?> entry = it.next();
        out.append("  ".repeat(indent + 1));
        quote(out, String.valueOf(entry.getKey()));
        out.append(": ");
        write(out, entry.getValue(), indent + 1);
        out.append(it.hasNext() ? ",\n" : "\n");
      }
      out.append("  ".repeat(indent)).append('}');
    } else if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < list.size(); i++) {
        out.append("  ".repeat(indent + 1));
        write(out, list.get(i), indent + 1);
        out.append(i + 1 < list.size() ? ",\n" : "\n");
      }
      out.append("  ".repeat(indent)).append(']');
    } else {
      quote(out, value.toString());
    }
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
