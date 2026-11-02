/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the agent's JSONL exporter output (objects, arrays, strings, numbers,
 * booleans, null). Numbers become {@link Long} when integral, else {@link Double}. Test harness
 * only; no external JSON library is needed for the agent's own output format.
 */
final class Json {
  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  /** Parses one JSON value; trailing whitespace only is allowed after it. */
  static Object parse(String text) {
    Json json = new Json(text);
    json.skipWhitespace();
    Object value = json.value();
    json.skipWhitespace();
    if (json.pos != text.length()) {
      throw json.error("trailing characters");
    }
    return value;
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
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          yield number();
        }
        throw error("unexpected character '" + c + "'");
      }
    };
  }

  private Map<String, Object> object() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    skipWhitespace();
    if (peek('}')) {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      if (!peek('"')) {
        throw error("expected a key");
      }
      String key = string();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, value());
      skipWhitespace();
      if (peek(',')) {
        pos++;
        continue;
      }
      expect('}');
      return map;
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    pos++;
    skipWhitespace();
    if (peek(']')) {
      pos++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(value());
      skipWhitespace();
      if (peek(',')) {
        pos++;
        continue;
      }
      expect(']');
      return list;
    }
  }

  private String string() {
    expect('"');
    StringBuilder out = new StringBuilder();
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
      char escape = text.charAt(pos++);
      switch (escape) {
        case '"', '\\', '/' -> out.append(escape);
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
        default -> throw error("bad escape '\\" + escape + "'");
      }
    }
    throw error("unterminated string");
  }

  private Object number() {
    int start = pos;
    if (peek('-')) {
      pos++;
    }
    boolean integral = true;
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c >= '0' && c <= '9') {
        pos++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        integral = false;
        pos++;
      } else {
        break;
      }
    }
    String number = text.substring(start, pos);
    if (integral) {
      try {
        return Long.parseLong(number);
      } catch (NumberFormatException e) {
        return Double.parseDouble(number);
      }
    }
    return Double.parseDouble(number);
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private boolean peek(char c) {
    return pos < text.length() && text.charAt(pos) == c;
  }

  private void expect(char c) {
    if (!peek(c)) {
      throw error("expected '" + c + "'");
    }
    pos++;
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException("JSON: " + message + " at " + pos);
  }
}
