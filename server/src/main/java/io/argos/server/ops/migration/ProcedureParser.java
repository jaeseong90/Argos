/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.ops.migration.Procedure.Step;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a procedure file {@code migrations/M<NNN>__<desc>.yaml} (13 §13.2; format in {@code
 * deploy/clickhouse/migrations/README.md}).
 *
 * <pre>{@code
 * description: replace instance_stat_1m by instance_stat_1m_v2
 * steps:
 *   - id: create_v2                 # [a-z][a-z0-9_]{0,15}, unique in the file
 *     kind: ddl                     # ddl | backfill | switch | drop
 *     statements:                   # ddl and drop: one statement per element
 *       - CREATE TABLE IF NOT EXISTS {db}.{p}instance_stat_1m_v2 (...) ENGINE = ...
 *   - id: backfill
 *     kind: backfill
 *     table: instance_stat_1m_v2    # rollup table; SELECT from rollups/<table>.sql
 *     unit: day                     # day (default; 1m tables) or month (1h tables)
 *     days: "{retention.xlog-days}" # a number or {retention.<key>}
 *   - id: switch
 *     kind: switch
 *     alias: instance_stat_1m       # logical name (settings.rollup_alias key)
 *     table: instance_stat_1m_v2    # physical table
 *   - id: drop_old
 *     kind: drop
 *     statements:
 *       - DROP VIEW IF EXISTS {db}.{p}instance_stat_1m_mv
 * }</pre>
 *
 * Unknown keys, missing keys and keys that do not belong to the kind are errors. Statements follow
 * the placeholder rules of the schema scripts ({@link SchemaTemplates}) and the lint rules ({@link
 * SchemaLint}); {@code ddl} steps contain no {@code DROP} statement and {@code drop} steps contain
 * only {@code DROP} statements. YAML is read with SnakeYAML's safe constructor (plain maps, lists
 * and scalars only, no aliases beyond a small bound).
 */
final class ProcedureParser {

  private static final Pattern STEP_ID = Pattern.compile("[a-z][a-z0-9_]{0,15}");
  private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern RETENTION_DAYS = Pattern.compile("\\{retention\\.([a-z0-9-]+)}");
  private static final Set<String> TOP_KEYS = Set.of("description", "steps");
  private static final Map<Step.Kind, Set<String>> STEP_KEYS =
      Map.of(
          Step.Kind.DDL, Set.of("id", "kind", "statements"),
          Step.Kind.DROP, Set.of("id", "kind", "statements"),
          Step.Kind.BACKFILL, Set.of("id", "kind", "table", "unit", "days"),
          Step.Kind.SWITCH, Set.of("id", "kind", "alias", "table"));
  private static final int MAX_DESCRIPTION = 200;
  private static final int MAX_STEPS = 50;

  private final SchemaTemplates templates;

  ProcedureParser(SchemaTemplates templates) {
    this.templates = templates;
  }

  /**
   * Parses one procedure file.
   *
   * @throws IllegalArgumentException with the file name and the problem
   */
  Procedure parse(int number, String version, String path, String text) {
    Object document;
    try {
      document = yaml().load(text.replace("\r\n", "\n"));
    } catch (YAMLException e) {
      throw new IllegalArgumentException(
          path + ": invalid YAML: " + MigrationException.oneLine(e, 300), e);
    }
    Map<?, ?> top = map(document, path, "the document");
    checkKeys(top, TOP_KEYS, path, "the document");
    String description = string(top.get("description"), path, "description");
    if (description.length() > MAX_DESCRIPTION) {
      throw new IllegalArgumentException(
          path + ": description longer than " + MAX_DESCRIPTION + " characters");
    }
    Object stepsValue = top.get("steps");
    if (!(stepsValue instanceof List<?> stepList) || stepList.isEmpty()) {
      throw new IllegalArgumentException(path + ": steps must be a non-empty list");
    }
    if (stepList.size() > MAX_STEPS) {
      throw new IllegalArgumentException(path + ": at most " + MAX_STEPS + " steps");
    }
    List<Step> steps = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < stepList.size(); i++) {
      String where = "step " + (i + 1);
      Map<?, ?> step = map(stepList.get(i), path, where);
      String id = string(step.get("id"), path, where + " id");
      if (!STEP_ID.matcher(id).matches()) {
        throw new IllegalArgumentException(
            path + ": " + where + " id '" + id + "' must match [a-z][a-z0-9_]{0,15}");
      }
      if (!ids.add(id)) {
        throw new IllegalArgumentException(path + ": duplicate step id " + id);
      }
      where = "step " + id;
      Step.Kind kind = kind(string(step.get("kind"), path, where + " kind"), path, where);
      checkKeys(step, STEP_KEYS.get(kind), path, where);
      steps.add(step(version, id, kind, step, path + ": " + where));
    }
    return new Procedure(number, version, description, path, steps);
  }

  private Step step(String version, String id, Step.Kind kind, Map<?, ?> step, String where) {
    List<String> statements = new ArrayList<>();
    String table = null;
    String alias = null;
    String unit = null;
    String days = null;
    switch (kind) {
      case DDL, DROP -> statements = statements(step.get("statements"), kind, where);
      case BACKFILL -> {
        table = table(step.get("table"), where, "table");
        if (!templates.rollupTables().contains(table)) {
          throw new IllegalArgumentException(
              where + ": no rollup SELECT file rollups/" + table + ".sql");
        }
        templates.rollupTemplate(table); // validates the rollup file
        Object unitValue = step.get("unit");
        unit = unitValue == null ? "day" : string(unitValue, where, "unit");
        if (!unit.equals("day") && !unit.equals("month")) {
          throw new IllegalArgumentException(where + ": unit must be day or month");
        }
        days = days(step.get("days"), where);
      }
      case SWITCH -> {
        alias = table(step.get("alias"), where, "alias");
        table = table(step.get("table"), where, "table");
      }
    }
    String checksum =
        SchemaTemplates.checksum(canonical(id, kind, statements, table, alias, unit, days));
    return new Step(version, id, kind, statements, table, alias, unit, days, checksum);
  }

  /**
   * The text the step checksum is computed from: one {@code key=value} line per attribute, the
   * statements expanded (C-56), for a backfill also the expanded rollup SELECT, so a changed rollup
   * file changes the checksum of an applied backfill (as for {@code {rollup:}} in a script).
   */
  String canonical(
      String id,
      Step.Kind kind,
      List<String> statements,
      String table,
      String alias,
      String unit,
      String days) {
    StringBuilder sb = new StringBuilder();
    sb.append("id=").append(id).append('\n');
    sb.append("kind=").append(kind.name().toLowerCase(Locale.ROOT)).append('\n');
    for (String statement : statements) {
      sb.append("statement=").append(statement).append('\n');
    }
    if (table != null) {
      sb.append("table=").append(table).append('\n');
    }
    if (alias != null) {
      sb.append("alias=").append(alias).append('\n');
    }
    if (unit != null) {
      sb.append("unit=").append(unit).append('\n');
    }
    if (days != null) {
      sb.append("days=").append(days).append('\n');
    }
    if (kind == Step.Kind.BACKFILL) {
      sb.append("rollup=").append(templates.rollupTemplate(table)).append('\n');
    }
    return sb.toString();
  }

  private List<String> statements(Object value, Step.Kind kind, String where) {
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException(where + ": statements must be a non-empty list");
    }
    List<String> statements = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String origin = where + " statement " + (i + 1);
      String template = string(list.get(i), origin, "statement");
      String expanded = templates.expand(template, origin);
      List<String> split = SqlScript.split(expanded);
      if (split.size() != 1) {
        throw new IllegalArgumentException(origin + ": exactly one statement per element expected");
      }
      String statement = split.get(0);
      boolean drop =
          SqlScript.withoutComments(statement).strip().toUpperCase(Locale.ROOT).startsWith("DROP ");
      if (kind == Step.Kind.DDL && drop) {
        throw new IllegalArgumentException(
            origin + ": DROP statements belong to a drop step (13 §13.2 step 4)");
      }
      if (kind == Step.Kind.DROP && !drop) {
        throw new IllegalArgumentException(origin + ": a drop step contains DROP statements only");
      }
      statements.add(statement);
    }
    return statements;
  }

  private static String days(Object value, String where) {
    if (value instanceof Integer number) {
      if (number < 1 || number > 36_500) {
        throw new IllegalArgumentException(where + ": days must be between 1 and 36500");
      }
      return number.toString();
    }
    if (value instanceof String text) {
      var m = RETENTION_DAYS.matcher(text.strip());
      if (m.matches() && SchemaParameters.RETENTION_KEYS.contains(m.group(1))) {
        return text.strip();
      }
    }
    throw new IllegalArgumentException(
        where + ": days must be a number or {retention.<key>} with a key of 13 §8");
  }

  private static String table(Object value, String where, String key) {
    String text = string(value, where, key);
    if (!TABLE.matcher(text).matches()) {
      throw new IllegalArgumentException(where + ": " + key + " must match [a-z][a-z0-9_]*");
    }
    return text;
  }

  private static Step.Kind kind(String value, String path, String where) {
    for (Step.Kind kind : Step.Kind.values()) {
      if (kind.name().toLowerCase(Locale.ROOT).equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        path + ": " + where + " kind must be ddl, backfill, switch or drop: " + value);
  }

  private static Map<?, ?> map(Object value, String path, String what) {
    if (value instanceof Map<?, ?> m) {
      return m;
    }
    throw new IllegalArgumentException(path + ": " + what + " must be a mapping");
  }

  private static String string(Object value, String path, String what) {
    if (value instanceof String s && !s.isBlank()) {
      return s.strip();
    }
    throw new IllegalArgumentException(path + ": " + what + " must be a non-empty string");
  }

  private static void checkKeys(Map<?, ?> map, Set<String> allowed, String path, String where) {
    for (Object key : map.keySet()) {
      if (!(key instanceof String s) || !allowed.contains(s)) {
        throw new IllegalArgumentException(
            path + ": " + where + ": unknown key '" + key + "' (allowed: " + allowed + ")");
      }
    }
  }

  private static Yaml yaml() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(10);
    options.setAllowRecursiveKeys(false);
    options.setCodePointLimit(1 << 20);
    return new Yaml(new SafeConstructor(options));
  }
}
