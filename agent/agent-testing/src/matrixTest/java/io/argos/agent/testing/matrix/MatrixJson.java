/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the matrix tests (sample app answers, ext-stub's request log, command
 * results): objects as {@code Map<String, Object>}, arrays as {@code List<Object>}, numbers as
 * {@code Long} or {@code Double}, plus strings, booleans and null. Test code only.
 */
final class MatrixJson {
  private final String text;
  private int pos;

  private MatrixJson(String text) {
    this.text = text;
  }

  /** The value of {@code text}. */
  static Object parse(String text) {
    MatrixJson reader = new MatrixJson(text);
    Object value = reader.value();
    reader.skipSpace();
    if (reader.pos != text.length()) {
      throw reader.error("trailing characters");
    }
    return value;
  }

  /** The object of {@code text}. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> object(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + abbreviate(text));
    }
    return (Map<String, Object>) value;
  }

  /** The array of {@code text}. */
  @SuppressWarnings("unchecked")
  static List<Object> array(String text) {
    Object value = parse(text);
    if (!(value instanceof List)) {
      throw new IllegalArgumentException("not a JSON array: " + abbreviate(text));
    }
    return (List<Object>) value;
  }

  private Object value() {
    skipSpace();
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    char c = text.charAt(pos);
    return switch (c) {
      case '{' -> objectValue();
      case '[' -> arrayValue();
      case '"' -> string();
      case 't' -> literal("true", true);
      case 'f' -> literal("false", false);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> objectValue() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    skipSpace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipSpace();
      String key = string();
      skipSpace();
      expect(':');
      map.put(key, value());
      skipSpace();
      char c = next();
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("expected , or }");
      }
    }
  }

  private List<Object> arrayValue() {
    List<Object> list = new ArrayList<>();
    pos++;
    skipSpace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      list.add(value());
      skipSpace();
      char c = next();
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("expected , or ]");
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
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          if (pos + 4 > text.length()) {
            throw error("short \\u escape");
          }
          out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> out.append(e);
      }
    }
  }

  private Object number() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    String number = text.substring(start, pos);
    if (number.isEmpty()) {
      throw error("unexpected character");
    }
    if (number.contains(".") || number.contains("e") || number.contains("E")) {
      return Double.parseDouble(number);
    }
    return Long.parseLong(number);
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private void skipSpace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    return pos < text.length() ? text.charAt(pos) : '\0';
  }

  private char next() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return text.charAt(pos++);
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("expected " + c);
    }
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException("JSON: " + what + " at " + pos + " in " + abbreviate(text));
  }

  private static String abbreviate(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }
}
