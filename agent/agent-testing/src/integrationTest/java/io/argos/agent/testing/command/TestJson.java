/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader for the command results (RFC 8259: objects, arrays, strings with escapes,
 * numbers, literals); throws {@link IllegalArgumentException} on anything else, trailing data and
 * duplicate keys included, so a malformed agent result fails the test.
 */
final class TestJson {
  private final String text;
  private int pos;

  private TestJson(String text) {
    this.text = text;
  }

  static Object parse(String text) {
    TestJson p = new TestJson(text);
    p.ws();
    Object value = p.value();
    p.ws();
    if (p.pos != text.length()) {
      throw new IllegalArgumentException("trailing data at " + p.pos);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(String text) {
    return (Map<String, Object>) parse(text);
  }

  private Object value() {
    if (pos >= text.length()) {
      throw new IllegalArgumentException("end of input");
    }
    return switch (text.charAt(pos)) {
      case '{' -> obj();
      case '[' -> arr();
      case '"' -> str();
      case 't' -> lit("true", true);
      case 'f' -> lit("false", false);
      case 'n' -> lit("null", null);
      default -> num();
    };
  }

  private Map<String, Object> obj() {
    pos++;
    Map<String, Object> map = new LinkedHashMap<>();
    ws();
    if (text.charAt(pos) == '}') {
      pos++;
      return map;
    }
    while (true) {
      ws();
      String key = str();
      ws();
      expect(':');
      ws();
      if (map.containsKey(key)) {
        throw new IllegalArgumentException("duplicate key " + key);
      }
      map.put(key, value());
      ws();
      char c = text.charAt(pos++);
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected , or } at " + pos);
      }
    }
  }

  private List<Object> arr() {
    pos++;
    List<Object> list = new ArrayList<>();
    ws();
    if (text.charAt(pos) == ']') {
      pos++;
      return list;
    }
    while (true) {
      ws();
      list.add(value());
      ws();
      char c = text.charAt(pos++);
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected , or ] at " + pos);
      }
    }
  }

  private String str() {
    expect('"');
    StringBuilder out = new StringBuilder();
    while (true) {
      char c = text.charAt(pos++);
      if (c == '"') {
        return out.toString();
      }
      if (c < 0x20) {
        throw new IllegalArgumentException("control character in string at " + pos);
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char e = text.charAt(pos++);
      switch (e) {
        case '"', '\\', '/' -> out.append(e);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw new IllegalArgumentException("bad escape at " + pos);
      }
    }
  }

  private Object num() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    String n = text.substring(start, pos);
    if (n.isEmpty()) {
      throw new IllegalArgumentException("unexpected character at " + pos);
    }
    if (n.contains(".") || n.contains("e") || n.contains("E")) {
      return Double.valueOf(n);
    }
    return Long.valueOf(n);
  }

  private Object lit(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw new IllegalArgumentException("bad literal at " + pos);
    }
    pos += word.length();
    return value;
  }

  private void expect(char c) {
    if (text.charAt(pos++) != c) {
      throw new IllegalArgumentException("expected " + c + " at " + pos);
    }
  }

  private void ws() {
    while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
  }
}
