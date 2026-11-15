/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The argos.v1 contract vectors ({@value #RESOURCE}, 11 §16): fixed inputs and expected outputs of
 * the session order (§3, §5), deduplication and acks (§6), the clock arithmetic (§9) and the status
 * codes and trailers (§5, §10). The FakeCollector's tests replay them; the server (WO-21, WO-22)
 * can replay the same file. This class reads the JSON into maps, lists, strings, {@link Long}s,
 * {@link Double}s, booleans and nulls.
 */
public final class ContractVectors {
  /** Class path resource of the vectors. */
  public static final String RESOURCE = "/contract/argos-v1-vectors.json";

  private final String text;
  private int pos;

  private ContractVectors(String text) {
    this.text = text;
  }

  /** The vectors file as a JSON object. */
  public static Map<String, Object> load() {
    try (InputStream in = ContractVectors.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the class path");
      }
      return map(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One JSON value. */
  public static Object parse(String json) {
    ContractVectors parser = new ContractVectors(json);
    parser.whitespace();
    Object value = parser.value();
    parser.whitespace();
    if (parser.pos != json.length()) {
      throw parser.error("trailing characters");
    }
    return value;
  }

  /** {@code value} as a JSON object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object value) {
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + value);
    }
    return (Map<String, Object>) value;
  }

  /** {@code value} as a list of JSON objects. */
  public static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("not a JSON array: " + value);
    }
    List<Map<String, Object>> maps = new ArrayList<>();
    for (Object item : list) {
      maps.add(map(item));
    }
    return maps;
  }

  /** {@code object.key} as a long. */
  public static long number(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(key + " is not a number in " + object);
    }
    return number.longValue();
  }

  /** {@code object.key} as a string (null when absent). */
  public static String string(Map<String, Object> object, String key) {
    Object value = object.get(key);
    return value == null ? null : value.toString();
  }

  // --- parser -----------------------------------------------------------------------------------

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
    Map<String, Object> object = new LinkedHashMap<>();
    pos++;
    whitespace();
    if (peek() == '}') {
      pos++;
      return object;
    }
    while (true) {
      whitespace();
      String key = string();
      whitespace();
      expect(':');
      whitespace();
      object.put(key, value());
      whitespace();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect('}');
      return object;
    }
  }

  private List<Object> array() {
    List<Object> array = new ArrayList<>();
    pos++;
    whitespace();
    if (peek() == ']') {
      pos++;
      return array;
    }
    while (true) {
      whitespace();
      array.add(value());
      whitespace();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect(']');
      return array;
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
            throw error("short unicode escape");
          }
          out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw error("bad escape \\" + escape);
      }
    }
    throw error("unterminated string");
  }

  private Object number() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    String number = text.substring(start, pos);
    if (number.isEmpty()) {
      throw error("unexpected character '" + text.charAt(start) + "'");
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

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return text.charAt(pos);
  }

  private void expect(char c) {
    if (peek() != c) {
      throw error("expected '" + c + "'");
    }
    pos++;
  }

  private void whitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at offset " + pos);
  }
}
