/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two placeholder kinds of the query files.
 *
 * <ul>
 *   <li>Textual {@code {name}} (no colon): identifiers and DDL literals rendered before execution:
 *       {@code {db}}, {@code {p}}, {@code {retention.<key>}}, {@code {bloom.<index>.<param>}} of
 *       the schema parameters, plus names that a procedure supplies (table names of its copies).
 *       Values must be identifier or number characters, so rendering cannot inject SQL.
 *   <li>Typed {@code {name:Type}}: ClickHouse query parameters, left in the SQL and bound by the
 *       server from the {@code param_<name>} request parameters (values never enter the SQL text).
 * </ul>
 */
public final class SqlTemplate {

  /** ClickHouse query parameter {@code {name:Type}}. */
  static final Pattern TYPED = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*):([^{}]+)}");

  /** Textual placeholder {@code {name}}. */
  static final Pattern TEXTUAL = Pattern.compile("\\{([a-z][a-z0-9_.-]*)}");

  private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.]*");

  private SqlTemplate() {}

  /** Names of the textual placeholders of {@code sql}. */
  public static Set<String> textualPlaceholders(String sql) {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = TEXTUAL.matcher(sql);
    while (m.find()) {
      names.add(m.group(1));
    }
    return names;
  }

  /** Typed placeholders of {@code sql}: name to declared type. */
  public static Map<String, String> typedPlaceholders(String sql) {
    Map<String, String> names = new LinkedHashMap<>();
    Matcher m = TYPED.matcher(sql);
    while (m.find()) {
      String previous = names.putIfAbsent(m.group(1), m.group(2).strip());
      if (previous != null && !previous.equals(m.group(2).strip())) {
        throw new IllegalArgumentException(
            "Parameter {" + m.group(1) + "} used with two types: " + previous + ", " + m.group(2));
      }
    }
    return names;
  }

  /**
   * Replaces every textual placeholder.
   *
   * @throws IllegalArgumentException for an unknown name or a value with other than identifier or
   *     number characters
   */
  public static String render(String sql, Map<String, String> values) {
    Matcher m = TEXTUAL.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = values.get(m.group(1));
      if (value == null) {
        throw new IllegalArgumentException("No value for placeholder {" + m.group(1) + "}");
      }
      if (!SAFE_VALUE.matcher(value).matches()) {
        throw new IllegalArgumentException(
            "Placeholder {" + m.group(1) + "} value is not an identifier or number: " + value);
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
