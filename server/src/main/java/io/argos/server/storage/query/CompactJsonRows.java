/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * Reads the {@code JSONCompactEachRowWithNamesAndTypes} output of ClickHouse: a JSON array of the
 * column names, one of the type names, then one JSON array per row, one per line. Values are
 * converted by the column type ({@link QueryRow} lists the conversions). A line that is not a JSON
 * array is an error that ClickHouse wrote into the body ({@code Code: 159. DB::Exception: ...} or
 * {@code {"exception": ...}}); it is raised as {@link ClickHouseErrors.StreamedError} with its
 * code.
 */
final class CompactJsonRows {

  private static final JsonFactory JSON = new JsonFactory();

  private static final Pattern CODE = Pattern.compile("Code:\\s*(\\d+)");

  private static final DateTimeFormatter DATE_TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .optionalEnd()
          .toFormatter(Locale.ROOT);

  private CompactJsonRows() {}

  /**
   * Reads every row of {@code body}, handing each to {@code sink} as it is read.
   *
   * @return the column names and types
   * @throws IOException when the body cannot be read
   * @throws ClickHouseErrors.StreamedError when ClickHouse reported an error in the body
   */
  static Header read(InputStream body, Consumer<QueryRow> sink) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    List<String> names = null;
    List<Type> types = null;
    Map<String, Integer> index = null;
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      List<Object> values = parseLine(line, names == null || types == null ? null : types);
      if (names == null) {
        names = strings(values);
        continue;
      }
      if (types == null) {
        List<String> typeNames = strings(values);
        if (typeNames.size() != names.size()) {
          throw new IOException("Result header has " + names.size() + " names and types");
        }
        types = typeNames.stream().map(Type::of).toList();
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
          byName.putIfAbsent(names.get(i), i);
        }
        index = Collections.unmodifiableMap(byName);
        continue;
      }
      if (values.size() != names.size()) {
        throw new IOException(
            "Result row has " + values.size() + " values, expected " + names.size());
      }
      sink.accept(new Row(names, index, values.toArray()));
    }
    if (names == null || types == null) {
      return new Header(List.of(), List.of());
    }
    return new Header(names, types.stream().map(Type::name).toList());
  }

  /** The column names and type names of a result. */
  record Header(List<String> names, List<String> types) {}

  private static List<String> strings(List<Object> values) throws IOException {
    List<String> out = new ArrayList<>(values.size());
    for (Object v : values) {
      if (!(v instanceof String s)) {
        throw new IOException("Result header is not a list of strings");
      }
      out.add(s);
    }
    return out;
  }

  /** One line: a JSON array, converted by {@code types} (null for the two header lines). */
  static List<Object> parseLine(String line, List<Type> types) throws IOException {
    String trimmed = line.strip();
    if (!trimmed.startsWith("[")) {
      throw streamedError(trimmed);
    }
    try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), trimmed.toCharArray())) {
      if (parser.nextToken() != JsonToken.START_ARRAY) {
        throw streamedError(trimmed);
      }
      List<Object> values = new ArrayList<>();
      int column = 0;
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
        if (token == null) {
          throw new IOException("Truncated result row");
        }
        Type type = types == null || column >= types.size() ? Type.ANY : types.get(column);
        values.add(value(parser, token, type));
        column++;
      }
      return values;
    } catch (JacksonException e) {
      throw streamedError(trimmed);
    }
  }

  private static Object value(JsonParser parser, JsonToken token, Type type) throws IOException {
    return switch (token) {
      case VALUE_NULL -> null;
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      case VALUE_NUMBER_INT -> integer(parser, type);
      case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
      case VALUE_STRING -> string(parser.getString(), type);
      case START_ARRAY -> array(parser, type);
      case START_OBJECT -> object(parser, type);
      default -> throw new IOException("Unexpected token " + token + " in a result row");
    };
  }

  private static Object integer(JsonParser parser, Type type) {
    if (parser.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
      BigInteger big = parser.getBigIntegerValue();
      return big.longValue(); // UInt64 above 2^63: keep the 64 bits
    }
    if (type.floating()) {
      return parser.getDoubleValue();
    }
    return parser.getLongValue();
  }

  private static List<Object> array(JsonParser parser, Type type) throws IOException {
    List<Object> list = new ArrayList<>();
    int i = 0;
    JsonToken t;
    while ((t = parser.nextToken()) != JsonToken.END_ARRAY) {
      if (t == null) {
        throw new IOException("Truncated array");
      }
      list.add(value(parser, t, type.element(i)));
      i++;
    }
    return Collections.unmodifiableList(list);
  }

  private static Map<String, Object> object(JsonParser parser, Type type) throws IOException {
    Map<String, Object> map = new LinkedHashMap<>();
    JsonToken t;
    while ((t = parser.nextToken()) != JsonToken.END_OBJECT) {
      if (t != JsonToken.PROPERTY_NAME) {
        throw new IOException("Malformed object in a result row");
      }
      String key = parser.currentName();
      map.put(key, value(parser, parser.nextToken(), type.mapValue()));
    }
    return Collections.unmodifiableMap(map);
  }

  private static Object string(String text, Type type) {
    if (type.dateTime()) {
      try {
        LocalDateTime local = LocalDateTime.parse(text, DATE_TIME);
        return local.atZone(type.zone()).toInstant();
      } catch (RuntimeException e) {
        return text;
      }
    }
    if (type.integer()) {
      try {
        return type.unsigned() ? Long.parseUnsignedLong(text) : Long.parseLong(text);
      } catch (NumberFormatException e) {
        return text;
      }
    }
    return text;
  }

  private static ClickHouseErrors.StreamedError streamedError(String line) {
    Matcher m = CODE.matcher(line);
    int code = m.find() ? Integer.parseInt(m.group(1)) : 0;
    return new ClickHouseErrors.StreamedError(code);
  }

  /** A ClickHouse type name, reduced to what the conversion needs. */
  record Type(
      String name,
      boolean dateTime,
      ZoneId zone,
      boolean integer,
      boolean unsigned,
      boolean floating,
      List<Type> elements) {

    static final Type ANY = new Type("", false, ZoneOffset.UTC, false, false, false, List.of());

    private static final Pattern TZ = Pattern.compile("'([^']+)'");

    static Type of(String name) {
      String t = unwrap(name.strip());
      if (t.startsWith("DateTime")) {
        Matcher m = TZ.matcher(t);
        ZoneId zone = ZoneOffset.UTC;
        if (m.find()) {
          try {
            zone = ZoneId.of(m.group(1));
          } catch (RuntimeException e) {
            zone = ZoneOffset.UTC;
          }
        }
        return new Type(name, true, zone, false, false, false, List.of());
      }
      if (t.matches("U?Int(8|16|32|64|128|256)")) {
        return new Type(name, false, ZoneOffset.UTC, true, t.startsWith("U"), false, List.of());
      }
      if (t.startsWith("Float") || t.startsWith("Decimal")) {
        return new Type(name, false, ZoneOffset.UTC, false, false, true, List.of());
      }
      if (t.startsWith("Array(") || t.startsWith("Tuple(") || t.startsWith("Map(")) {
        String inner = t.substring(t.indexOf('(') + 1, t.length() - 1);
        List<Type> parts = new ArrayList<>();
        for (String part : splitTopLevel(inner)) {
          parts.add(of(stripTupleName(part)));
        }
        return new Type(name, false, ZoneOffset.UTC, false, false, false, List.copyOf(parts));
      }
      return new Type(name, false, ZoneOffset.UTC, false, false, false, List.of());
    }

    /** The type of element {@code i}: the element type of an array, the i-th of a tuple. */
    Type element(int i) {
      if (elements.isEmpty()) {
        return ANY;
      }
      String t = unwrap(name.strip());
      if (t.startsWith("Tuple(")) {
        return i < elements.size() ? elements.get(i) : ANY;
      }
      return elements.get(0);
    }

    /** The value type of a map. */
    Type mapValue() {
      return elements.size() == 2 ? elements.get(1) : ANY;
    }

    private static String unwrap(String t) {
      String s = t;
      boolean changed = true;
      while (changed) {
        changed = false;
        for (String wrapper : List.of("Nullable(", "LowCardinality(", "SimpleAggregateFunction(")) {
          if (s.startsWith(wrapper) && s.endsWith(")")) {
            s = s.substring(wrapper.length(), s.length() - 1).strip();
            if (wrapper.startsWith("Simple")) {
              List<String> parts = splitTopLevel(s);
              s = parts.get(parts.size() - 1).strip();
            }
            changed = true;
          }
        }
      }
      return s;
    }

    /** {@code name Type} of a named tuple element to {@code Type}. */
    private static String stripTupleName(String part) {
      String p = part.strip();
      int space = p.indexOf(' ');
      int paren = p.indexOf('(');
      if (space > 0 && (paren < 0 || space < paren) && p.substring(0, space).matches("\\w+")) {
        String rest = p.substring(space + 1).strip();
        if (!rest.isEmpty() && Character.isUpperCase(rest.charAt(0))) {
          return rest;
        }
      }
      return p;
    }

    private static List<String> splitTopLevel(String s) {
      List<String> parts = new ArrayList<>();
      int depth = 0;
      boolean quoted = false;
      int start = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\'') {
          quoted = !quoted;
        } else if (!quoted && c == '(') {
          depth++;
        } else if (!quoted && c == ')') {
          depth--;
        } else if (!quoted && depth == 0 && c == ',') {
          parts.add(s.substring(start, i));
          start = i + 1;
        }
      }
      parts.add(s.substring(start));
      return parts;
    }
  }

  /** A row backed by an array, columns by name through a shared index. */
  private static final class Row implements QueryRow {

    private final List<String> names;
    private final Map<String, Integer> index;
    private final Object[] values;

    Row(List<String> names, Map<String, Integer> index, Object[] values) {
      this.names = names;
      this.index = index;
      this.values = values;
    }

    @Override
    public List<String> columns() {
      return names;
    }

    @Override
    public Object get(String column) {
      Integer i = index.get(column);
      if (i == null) {
        throw new IllegalArgumentException("No column " + column + " in " + names);
      }
      return values[i];
    }

    @Override
    public String toString() {
      return "QueryRow" + names;
    }
  }
}
