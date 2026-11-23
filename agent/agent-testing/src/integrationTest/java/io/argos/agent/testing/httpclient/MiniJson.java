/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the answers of the sample apps and ext-stub's request log ({@code
 * /_stub/requests}): objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link
 * Long} or {@link Double}. Test-only; malformed input throws {@link IllegalArgumentException}.
 */
final class MiniJson {
  private final String text;
  private int pos;

  private MiniJson(String text) {
    this.text = text;
  }

  static Object parse(String text) {
    MiniJson parser = new MiniJson(text);
    Object value = parser.value();
    parser.whitespace();
    if (parser.pos != text.length()) {
      throw parser.error("trailing characters");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + text);
    }
    return (Map<String, Object>) value;
  }

  private Object value() {
    whitespace();
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return switch (text.charAt(pos)) {
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
    whitespace();
    if (peek('}')) {
      pos++;
      return map;
    }
    while (true) {
      whitespace();
      String key = string();
      whitespace();
      expect(':');
      map.put(key, value());
      whitespace();
      if (peek(',')) {
        pos++;
      } else {
        expect('}');
        return map;
      }
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    pos++;
    whitespace();
    if (peek(']')) {
      pos++;
      return list;
    }
    while (true) {
      list.add(value());
      whitespace();
      if (peek(',')) {
        pos++;
      } else {
        expect(']');
        return list;
      }
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
      char e = text.charAt(pos++);
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
    throw error("unterminated string");
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
    if (token.contains(".") || token.contains("e") || token.contains("E")) {
      return Double.parseDouble(token);
    }
    return Long.parseLong(token);
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private void whitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
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

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException(what + " at " + pos + " in: " + text);
  }
}
