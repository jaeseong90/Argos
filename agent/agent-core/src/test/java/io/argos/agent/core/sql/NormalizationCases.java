/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reader of {@code src/test/resources/sql/normalization-cases.yaml}: the YAML subset the file
 * documents (one mapping per case under {@code cases:}, double-quoted strings with JSON escapes,
 * plain scalars, flow lists of double-quoted strings). No YAML library is on the agent's test class
 * path; a malformed line fails the test that reads it.
 */
final class NormalizationCases {
  static final String RESOURCE = "/sql/normalization-cases.yaml";

  /** Characters of a generated over-length statement ({@code generate: truncation}). */
  static final int GENERATED_LITERAL_CHARS = 70_000;

  private NormalizationCases() {}

  /** One case: its keys as written (list values as {@code List<String>}). */
  static final class Case {
    final Map<String, Object> values;

    Case(Map<String, Object> values) {
      this.values = values;
    }

    int id() {
      return Integer.parseInt(string("id"));
    }

    String name() {
      return string("name");
    }

    String string(String key) {
      Object value = values.get(key);
      return value == null ? null : value.toString();
    }

    boolean flag(String key) {
      return "true".equals(string(key));
    }

    @SuppressWarnings("unchecked")
    List<String> list(String key) {
      Object value = values.get(key);
      return value == null ? Collections.<String>emptyList() : (List<String>) value;
    }

    /** The statement: {@code input}, or the generated one of {@code generate}. */
    String input() {
      if ("truncation".equals(string("generate"))) {
        StringBuilder sql = new StringBuilder("select a from t where s = '");
        for (int i = 0; i < GENERATED_LITERAL_CHARS; i++) {
          sql.append('x');
        }
        return sql.append("' and b = 2").toString();
      }
      return string("input");
    }

    int dialect() {
      String dialect = string("dialect");
      if (dialect == null) {
        return SqlDialect.UNKNOWN;
      }
      switch (dialect) {
        case "mysql":
          return SqlDialect.MYSQL;
        case "postgresql":
          return SqlDialect.POSTGRESQL;
        case "oracle":
          return SqlDialect.ORACLE;
        case "sqlserver":
          return SqlDialect.SQLSERVER;
        case "ansi":
          return SqlDialect.ANSI;
        case "unknown":
          return SqlDialect.UNKNOWN;
        default:
          throw new IllegalArgumentException("case " + id() + ": dialect " + dialect);
      }
    }

    /** The normalizer of the case's options and failure injection. */
    SqlNormalizer normalizer() {
      String options = string("options");
      boolean backslash = options == null || !options.contains("backslash_escape=false");
      boolean keepHints = options != null && options.contains("keep_hints=true");
      int budget = "budget".equals(string("inject")) ? 0 : SqlNormalizer.BUDGET_PER_CHAR;
      return new SqlNormalizer(backslash, SqlNormalizer.MAX_LENGTH, keepHints, budget);
    }

    /** {@code sql_id} as a signed 64-bit value. */
    long sqlId() {
      String hex = string("sql_id").toLowerCase(Locale.ROOT);
      return Long.parseUnsignedLong(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
    }

    @Override
    public String toString() {
      return id() + ": " + name();
    }
  }

  /** Every case of the fixture, in file order. */
  static List<Case> load() throws IOException {
    InputStream in = NormalizationCases.class.getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IOException(RESOURCE + " not on the test class path");
    }
    String text;
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        bytes.write(buffer, 0, n);
      }
      text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      in.close();
    }
    return parse(text);
  }

  static List<Case> parse(String text) {
    List<Case> cases = new ArrayList<Case>();
    Map<String, Object> current = null;
    int lineNumber = 0;
    for (String raw : text.split("\n", -1)) {
      lineNumber++;
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("cases:")) {
        continue;
      }
      String entry;
      if (trimmed.startsWith("- ")) {
        current = new LinkedHashMap<String, Object>();
        cases.add(new Case(current));
        entry = trimmed.substring(2);
      } else {
        entry = trimmed;
      }
      if (current == null) {
        throw new IllegalArgumentException("line " + lineNumber + ": no case started");
      }
      int colon = entry.indexOf(": ");
      if (colon <= 0) {
        throw new IllegalArgumentException("line " + lineNumber + ": no key");
      }
      String key = entry.substring(0, colon).trim();
      String value = entry.substring(colon + 2).trim();
      current.put(key, value(value, lineNumber));
    }
    return cases;
  }

  private static Object value(String value, int line) {
    if (value.startsWith("\"")) {
      int[] end = new int[1];
      String parsed = quoted(value, 0, end, line);
      if (end[0] != value.length()) {
        throw new IllegalArgumentException("line " + line + ": text after a string");
      }
      return parsed;
    }
    if (value.startsWith("[")) {
      List<String> items = new ArrayList<String>();
      int i = 1;
      while (true) {
        while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == ',')) {
          i++;
        }
        if (i < value.length() && value.charAt(i) == ']') {
          return items;
        }
        int[] end = new int[1];
        items.add(quoted(value, i, end, line));
        i = end[0];
      }
    }
    return value;
  }

  /** The JSON-escaped string starting with the quote at {@code start}; {@code end[0]} after it. */
  private static String quoted(String value, int start, int[] end, int line) {
    if (start >= value.length() || value.charAt(start) != '"') {
      throw new IllegalArgumentException("line " + line + ": string expected");
    }
    StringBuilder out = new StringBuilder();
    int i = start + 1;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c == '"') {
        end[0] = i + 1;
        return out.toString();
      }
      if (c == '\\') {
        char e = value.charAt(i + 1);
        switch (e) {
          case 'n':
            out.append('\n');
            break;
          case 't':
            out.append('\t');
            break;
          case 'r':
            out.append('\r');
            break;
          case 'b':
            out.append('\b');
            break;
          case 'f':
            out.append('\f');
            break;
          case 'u':
            out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
            i += 4;
            break;
          case '"':
          case '\\':
          case '/':
            out.append(e);
            break;
          default:
            throw new IllegalArgumentException("line " + line + ": escape \\" + e);
        }
        i += 2;
        continue;
      }
      out.append(c);
      i++;
    }
    throw new IllegalArgumentException("line " + line + ": unterminated string");
  }
}
