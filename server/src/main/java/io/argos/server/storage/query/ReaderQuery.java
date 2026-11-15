/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One SQL template of the query layer (a file {@code sql/<path>.sql}, format in {@code
 * sql/README.md}): a single {@code SELECT} (or {@code WITH ... SELECT}) whose values are ClickHouse
 * query parameters ({@code {name:Type}}) and whose identifiers are the placeholders {@code {db}},
 * {@code {p}} and {@code {rollup:<table>}}, rendered from configuration only.
 *
 * <p>{@link #parse} enforces the rules a reviewer would otherwise grep for (SEC-76, ADR-0007 C-64,
 * 13 §13.2 step 3), so that a template that breaks one fails the server start and the unit test:
 *
 * <ul>
 *   <li>one statement starting with {@code SELECT} or {@code WITH}, no {@code ;};
 *   <li>no {@code SETTINGS}, {@code FORMAT} or {@code INTO OUTFILE}: the executor appends the
 *       {@code SETTINGS} clause and the result format itself;
 *   <li>no {@code UNION} outside parentheses: a trailing {@code SETTINGS} clause must apply to the
 *       whole statement;
 *   <li>placeholders only {@code {db}}, {@code {p}}, {@code {rollup:<known rollup table>}}, {@code
 *       {segments}} (outer templates of a {@link SegmentedQuery}) and parameters {@code
 *       {name:Type}}; every {@code {db}.} is followed by {@code {p}}; a rollup table is never named
 *       after {@code {p}} directly (it goes through {@code {rollup:...}} and so through {@link
 *       RollupAliases});
 *   <li>parameter names {@code from_sN} and {@code to_sN} are reserved for segment bounds.
 * </ul>
 *
 * Comment lines ({@code --}) are documentation: they are not sent to ClickHouse. The directive
 * {@code -- @tables a, b} lists the tables a template reads (documentation, {@code
 * x-argos-source}).
 *
 * @param name the template name: its path below the catalog root without {@code .sql}
 * @param sql the statement without comment lines and without a trailing {@code ;}
 * @param parameters the declared parameters, name to ClickHouse type, in order of appearance
 * @param rollups the logical rollup tables of {@code {rollup:...}}
 * @param segmentsPlaceholder whether the template contains {@code {segments}} (an outer template)
 * @param tables the tables of the {@code @tables} directive
 */
public record ReaderQuery(
    String name,
    String sql,
    Map<String, String> parameters,
    Set<String> rollups,
    boolean segmentsPlaceholder,
    List<String> tables) {

  /** The rollup tables of V001 (13 §4); their names go through {@link RollupAliases}. */
  public static final Set<String> ROLLUP_TABLES =
      Set.of(
          "app_stat_1m",
          "app_stat_1h",
          "service_stat_1m",
          "service_stat_1h",
          "instance_stat_1m",
          "error_stat_1m",
          "sql_stat_1m",
          "sql_stat_1h",
          "ext_stat_1m",
          "ext_stat_1h",
          "metric_1m",
          "metric_1h");

  /** The raw tables of V001 (13 §3). */
  public static final Set<String> RAW_TABLES =
      Set.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");

  /** Placeholder of the segment union in an outer template. */
  public static final String SEGMENTS = "{segments}";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
  private static final Pattern PARAMETER = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*):(.+)");
  private static final Pattern RESERVED_PARAMETER = Pattern.compile("(?:from|to)_s\\d+");

  /** A {@code SETTINGS name = ...} clause (a {@code Settings['x']} column is not one). */
  private static final Pattern SETTINGS_CLAUSE =
      Pattern.compile("\\bSETTINGS\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=", Pattern.CASE_INSENSITIVE);

  /** A {@code FORMAT name} clause: last in the statement or before a {@code SETTINGS} clause. */
  private static final Pattern FORMAT_CLAUSE =
      Pattern.compile(
          "\\bFORMAT\\s+[A-Za-z][A-Za-z0-9_]*\\s*(?:$|SETTINGS\\b)", Pattern.CASE_INSENSITIVE);

  private static final Pattern OUTFILE =
      Pattern.compile("\\bINTO\\s+OUTFILE\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern START = Pattern.compile("(?is)\\s*(SELECT|WITH)\\b.*");
  private static final Pattern DB_WITHOUT_PREFIX = Pattern.compile("\\{db\\}\\.(?!\\{p\\})");
  private static final Pattern PREFIXED_TABLE = Pattern.compile("\\{p\\}([A-Za-z0-9_]+)");
  private static final Pattern TABLES_DIRECTIVE = Pattern.compile("--\\s*@tables\\s+(.+)");
  private static final Pattern UNION = Pattern.compile("\\bUNION\\b", Pattern.CASE_INSENSITIVE);

  public ReaderQuery {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(sql, "sql");
    parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    rollups = Collections.unmodifiableSet(new LinkedHashSet<>(rollups));
    tables = List.copyOf(tables);
  }

  /**
   * Parses and checks a template.
   *
   * @param name the template name (for messages)
   * @param text the file content
   * @throws IllegalArgumentException naming the template and the broken rule
   */
  public static ReaderQuery parse(String name, String text) {
    Objects.requireNonNull(text, "text");
    List<String> tables = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    for (String line : text.split("\r?\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("--")) {
        Matcher m = TABLES_DIRECTIVE.matcher(trimmed);
        if (m.matches()) {
          for (String t : m.group(1).split(",", -1)) {
            if (!t.isBlank()) {
              tables.add(t.strip());
            }
          }
        }
        continue;
      }
      if (body.length() > 0) {
        body.append('\n');
      }
      body.append(line.stripTrailing());
    }
    String sql = body.toString().strip();
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1).stripTrailing();
    }
    if (sql.isEmpty()) {
      throw invalid(name, "no statement");
    }
    if (sql.indexOf(';') >= 0) {
      throw invalid(name, "more than one statement (';')");
    }
    if (!START.matcher(sql).matches()) {
      throw invalid(name, "a template is one SELECT or WITH ... SELECT");
    }
    if (SETTINGS_CLAUSE.matcher(sql).find()) {
      throw invalid(name, "SETTINGS: the executor adds the SETTINGS clause (C-64)");
    }
    if (FORMAT_CLAUSE.matcher(sql).find()) {
      throw invalid(name, "FORMAT: the executor chooses the result format");
    }
    if (OUTFILE.matcher(sql).find()) {
      throw invalid(name, "OUTFILE is not allowed");
    }
    if (unionAtTopLevel(sql)) {
      throw invalid(name, "UNION outside parentheses: wrap the union in a subquery");
    }
    if (DB_WITHOUT_PREFIX.matcher(sql).find()) {
      throw invalid(name, "a table is named as {db}.{p}<table>");
    }
    Matcher prefixed = PREFIXED_TABLE.matcher(sql);
    while (prefixed.find()) {
      if (ROLLUP_TABLES.contains(prefixed.group(1))) {
        throw invalid(
            name,
            "rollup table "
                + prefixed.group(1)
                + " must be named {rollup:"
                + prefixed.group(1)
                + "} (13 §13.2 step 3)");
      }
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    Set<String> rollups = new LinkedHashSet<>();
    int segments = 0;
    Matcher m = PLACEHOLDER.matcher(sql);
    while (m.find()) {
      String content = m.group(1);
      if (content.equals("db") || content.equals("p")) {
        continue;
      }
      if (content.equals("segments")) {
        segments++;
        continue;
      }
      if (content.startsWith("rollup:")) {
        String table = content.substring("rollup:".length());
        if (!ROLLUP_TABLES.contains(table)) {
          throw invalid(name, "unknown rollup table {" + content + "}");
        }
        rollups.add(table);
        continue;
      }
      Matcher p = PARAMETER.matcher(content);
      if (!p.matches()) {
        throw invalid(name, "unknown placeholder {" + content + "}");
      }
      String param = p.group(1);
      if (RESERVED_PARAMETER.matcher(param).matches()) {
        throw invalid(name, "parameter name " + param + " is reserved for segment bounds");
      }
      String type = p.group(2).strip();
      String previous = parameters.putIfAbsent(param, type);
      if (previous != null && !previous.equals(type)) {
        throw invalid(name, "parameter " + param + " declared as " + previous + " and " + type);
      }
    }
    if (segments > 1) {
      throw invalid(name, "{segments} appears more than once");
    }
    return new ReaderQuery(name, sql, parameters, rollups, segments == 1, tables);
  }

  /** Whether {@code UNION} appears outside every parenthesis (string literals ignored). */
  static boolean unionAtTopLevel(String sql) {
    StringBuilder top = new StringBuilder(sql.length());
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quoted) {
        if (c == '\\' && i + 1 < sql.length()) {
          i++;
        } else if (c == '\'') {
          quoted = false;
        }
        top.append(' ');
        continue;
      }
      if (c == '\'') {
        quoted = true;
        top.append(' ');
      } else if (c == '(') {
        depth++;
        top.append(' ');
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
        top.append(' ');
      } else {
        top.append(depth == 0 ? c : ' ');
      }
    }
    return UNION.matcher(top).find();
  }

  private static IllegalArgumentException invalid(String name, String rule) {
    return new IllegalArgumentException("SQL template " + name + ": " + rule);
  }
}
