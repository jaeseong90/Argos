/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Query parameter values in the ClickHouse text form ({@code param_<name>} request parameters,
 * parsed by the server with the declared type of {@code {name:Type}}). Values from probes are kept
 * exactly as the TabSeparated output escaped them, which is the form the server parses back.
 */
public final class Parameters {

  private final Map<String, String> values;

  private Parameters(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  /** No parameters. */
  public static Parameters empty() {
    return new Parameters(Map.of());
  }

  /** Parameters from a map (text values). */
  public static Parameters of(Map<String, String> values) {
    return new Parameters(values);
  }

  /** A copy with additional or replaced values. */
  public Parameters with(Map<String, String> more) {
    Map<String, String> copy = new LinkedHashMap<>(values);
    copy.putAll(more);
    return new Parameters(copy);
  }

  /** A copy with one more value. */
  public Parameters with(String name, String value) {
    return with(Map.of(name, value));
  }

  /** The value of a parameter, or null. */
  public String get(String name) {
    return values.get(name);
  }

  /** All values. */
  public Map<String, String> asMap() {
    return values;
  }

  /**
   * The request parameters ({@code param_<name>}) for the typed placeholders of {@code sql}.
   *
   * @throws MissingParameterException when a placeholder has no value
   */
  public Map<String, String> bind(String sql) {
    Map<String, String> bound = new LinkedHashMap<>();
    Set<String> missing = new TreeSet<>();
    for (String name : SqlTemplate.typedPlaceholders(sql).keySet()) {
      String value = values.get(name);
      if (value == null) {
        missing.add(name);
      } else {
        bound.put("param_" + name, value);
      }
    }
    if (!missing.isEmpty()) {
      throw new MissingParameterException(missing);
    }
    return bound;
  }

  /**
   * {@code sql} with every typed placeholder replaced by a literal, for the few statements that
   * cannot carry request parameters (binary output through {@code ClickHouseHttp.queryBytes}).
   * Supported types: integers, String, Date, DateTime, DateTime64; string values are quoted and
   * escaped, numbers and dates are validated.
   */
  public String inline(String sql) {
    java.util.regex.Matcher m = SqlTemplate.TYPED.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String type = m.group(2).strip();
      String value = values.get(name);
      if (value == null) {
        throw new MissingParameterException(Set.of(name));
      }
      m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(literal(type, value)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String literal(String type, String value) {
    if (type.matches("U?Int(8|16|32|64)")) {
      if (!value.matches("-?\\d+")) {
        throw new IllegalArgumentException("Not an integer: " + value);
      }
      return value;
    }
    if (type.startsWith("DateTime64")) {
      requireDate(value, "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");
      return "toDateTime64('" + value + "', 3, 'UTC')";
    }
    if (type.startsWith("DateTime")) {
      requireDate(value, "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
      return "toDateTime('" + value + "', 'UTC')";
    }
    if (type.equals("Date")) {
      requireDate(value, "\\d{4}-\\d{2}-\\d{2}");
      return "toDate('" + value + "')";
    }
    if (type.equals("String")) {
      return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    throw new IllegalArgumentException("Cannot inline a parameter of type " + type);
  }

  private static void requireDate(String value, String pattern) {
    if (!value.matches(pattern)) {
      throw new IllegalArgumentException("Not a date/time literal: " + value);
    }
  }

  @Override
  public String toString() {
    return values.toString();
  }

  /** A typed placeholder without a value (its probe found nothing, or none produces it). */
  public static final class MissingParameterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Set<String> names;

    MissingParameterException(Set<String> names) {
      super("No value for parameter(s) " + names);
      this.names = Set.copyOf(names);
    }

    /** The parameters without a value. */
    public Set<String> names() {
      return names;
    }
  }
}
