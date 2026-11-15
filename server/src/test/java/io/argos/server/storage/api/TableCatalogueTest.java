/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The column catalogue of {@link Table} against the ClickHouse schema scripts: every {@code
 * deploy/clickhouse/schema/V<NNN>__*.sql} is parsed in version order ({@code CREATE TABLE} column
 * lists, then {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... [AFTER x | FIRST]}), and the
 * resulting columns of the five raw tables must equal the catalogue in name, type and position.
 */
@Tag("req:C-17")
@Tag("req:C-67")
@Tag("req:C-144")
@Tag("req:DSN-12-02")
class TableCatalogueTest {

  private static final Pattern CREATE =
      Pattern.compile(
          "(?is)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+\\{db}\\.\\{p}([a-z0-9_]+)\\s*\\(");
  private static final Pattern ALTER =
      Pattern.compile("(?is)^ALTER\\s+TABLE\\s+\\{db}\\.\\{p}([a-z0-9_]+)\\s+(.*)$");
  private static final Pattern ADD_COLUMN =
      Pattern.compile("(?is)^ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+(.*)$");
  private static final Pattern AFTER = Pattern.compile("(?is)^(.*?)\\s+AFTER\\s+([a-z0-9_]+)$");
  private static final Pattern FIRST = Pattern.compile("(?is)^(.*?)\\s+FIRST$");
  private static final Set<String> TYPE_END =
      Set.of(
          "DEFAULT",
          "MATERIALIZED",
          "EPHEMERAL",
          "ALIAS",
          "CODEC",
          "COMMENT",
          "TTL",
          "STATISTICS",
          "SETTINGS",
          "PRIMARY",
          "NOT",
          "NULL");
  private static final Set<String> NOT_A_COLUMN = Set.of("INDEX", "PROJECTION", "CONSTRAINT");

  @Test
  @DisplayName("the catalogue equals the columns of V001 + V002 (C-144) in name, type and order")
  void catalogueEqualsSchemaScripts() throws IOException {
    Map<String, List<Column>> schema = parseSchema(schemaScripts());

    for (Table table : Table.values()) {
      assertThat(schema)
          .as("raw table %s in the schema scripts", table.tableName())
          .containsKey(table.tableName());
      assertThat(table.columns())
          .as("columns of %s", table.tableName())
          .containsExactlyElementsOf(schema.get(table.tableName()));
    }
  }

  @Test
  @DisplayName("the scripts are V001 and the additive V002 of C-144; V001 is unchanged by them")
  void scriptsAreV001AndV002() throws IOException {
    List<Path> scripts = schemaScripts();
    assertThat(scripts)
        .extracting(p -> p.getFileName().toString())
        .startsWith("V001__init.sql", "V002__ingest_columns.sql");
    Map<String, List<Column>> v001 = parseSchema(scripts.subList(0, 1));
    Map<String, List<Column>> all = parseSchema(scripts.subList(0, 2));

    List<String> xlogAdded = added(v001.get("xlog"), all.get("xlog"));
    List<String> stepAdded = added(v001.get("profile_step"), all.get("profile_step"));
    assertThat(xlogAdded).containsExactly("url_path", "headers");
    assertThat(stepAdded).containsExactly("truncated", "total_steps");
    for (String table : List.of("metric", "sql_stat_raw", "ext_stat_raw")) {
      assertThat(all.get(table)).as(table).isEqualTo(v001.get(table));
    }
    assertThat(Table.XLOG.column("headers"))
        .hasValue(new Column("headers", "Map(LowCardinality(String), String)"));
    assertThat(Table.PROFILE_STEP.column("total_steps"))
        .hasValue(new Column("total_steps", "UInt32"));
  }

  @Test
  @DisplayName("the explicit insert column list names every catalogue column once, in order")
  void columnList() {
    for (Table table : Table.values()) {
      assertThat(table.columnList().split(", ", -1))
          .containsExactlyElementsOf(table.columnNames())
          .doesNotHaveDuplicates();
      assertThat(Table.fromCode(table.code())).hasValue(table);
    }
    assertThat(Table.XLOG.columnNames()).hasSize(42).startsWith("app_id", "env", "instance_id");
    assertThat(Table.fromCode(0)).isEmpty();
    assertThat(Table.fromCode(6)).isEmpty();
  }

  @Test
  @DisplayName(
      "the DDL reader handles multi-line types, codecs, defaults, indexes, AFTER and FIRST")
  void parserSanity() {
    String script =
        """
        -- comment with a ; and a ( inside
        CREATE TABLE IF NOT EXISTS {db}.{p}t
        (
            a UInt32,
            b Enum8('x' = 1, 'y;z' = 2,
                    'w' = 3) CODEC(ZSTD(1)), -- trailing comment
            c DateTime64(3,   'UTC') DEFAULT now64() CODEC(DoubleDelta, ZSTD(1)),
            INDEX idx_a a TYPE bloom_filter(0.01) GRANULARITY 4
        )
        ENGINE = MergeTree ORDER BY a;
        ALTER TABLE {db}.{p}t ADD COLUMN IF NOT EXISTS d Map(LowCardinality(String), String) CODEC(ZSTD(3)),
            ADD COLUMN IF NOT EXISTS e String DEFAULT '' AFTER a,
            ADD COLUMN IF NOT EXISTS f UInt8 FIRST;
        """;
    List<Column> columns = parse(script, new LinkedHashMap<>()).get("t");
    assertThat(columns)
        .containsExactly(
            new Column("f", "UInt8"),
            new Column("a", "UInt32"),
            new Column("e", "String"),
            new Column("b", "Enum8('x' = 1, 'y;z' = 2, 'w' = 3)"),
            new Column("c", "DateTime64(3, 'UTC')"),
            new Column("d", "Map(LowCardinality(String), String)"));
    assertThatThrownBy(
            () ->
                parse(
                    "ALTER TABLE {db}.{p}missing ADD COLUMN IF NOT EXISTS x UInt8;",
                    new LinkedHashMap<>()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("missing");
  }

  // --- DDL reader ---

  private static List<Path> schemaScripts() throws IOException {
    Path dir =
        Path.of(System.getProperty("argos.repositoryRoot", ".."))
            .resolve("deploy/clickhouse/schema");
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(f -> f.getFileName().toString().matches("V\\d{3}__[a-z0-9_]+\\.sql"))
          .sorted()
          .toList();
    }
  }

  private static Map<String, List<Column>> parseSchema(List<Path> scripts) throws IOException {
    Map<String, List<Column>> tables = new LinkedHashMap<>();
    for (Path script : scripts) {
      parse(Files.readString(script, StandardCharsets.UTF_8), tables);
    }
    return tables;
  }

  private static List<String> added(List<Column> before, List<Column> after) {
    assertThat(after.subList(0, before.size())).isEqualTo(before);
    return after.subList(before.size(), after.size()).stream().map(Column::name).toList();
  }

  /** Applies the CREATE TABLE and ALTER TABLE ... ADD COLUMN statements of {@code script}. */
  private static Map<String, List<Column>> parse(String script, Map<String, List<Column>> tables) {
    for (String statement : statements(withoutComments(script))) {
      Matcher create = CREATE.matcher(statement);
      if (create.find()) {
        int open = create.end() - 1;
        String body = statement.substring(open + 1, closing(statement, open));
        List<Column> columns = new ArrayList<>();
        for (String element : splitTopLevel(body, ',')) {
          String first = firstWord(element).toUpperCase(Locale.ROOT);
          if (!NOT_A_COLUMN.contains(first)) {
            columns.add(column(element));
          }
        }
        tables.putIfAbsent(create.group(1), columns);
        continue;
      }
      Matcher alter = ALTER.matcher(statement);
      if (alter.matches()) {
        List<Column> columns = tables.get(alter.group(1));
        if (columns == null) {
          throw new AssertionError("ALTER of an unknown table: " + alter.group(1));
        }
        for (String action : splitTopLevel(alter.group(2), ',')) {
          Matcher add = ADD_COLUMN.matcher(action.strip());
          if (!add.matches()) {
            throw new AssertionError("Unsupported ALTER action in a schema script: " + action);
          }
          addColumn(columns, add.group(1).strip());
        }
      }
    }
    return tables;
  }

  private static void addColumn(List<Column> columns, String definition) {
    Matcher after = AFTER.matcher(definition);
    Matcher first = FIRST.matcher(definition);
    Column column;
    int index;
    if (after.matches()) {
      column = column(after.group(1));
      index = indexOf(columns, after.group(2)) + 1;
    } else if (first.matches()) {
      column = column(first.group(1));
      index = 0;
    } else {
      column = column(definition);
      index = columns.size();
    }
    if (indexOf(columns, column.name()) < 0) {
      columns.add(index, column);
    }
  }

  private static int indexOf(List<Column> columns, String name) {
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /** {@code name Type [DEFAULT ...] [CODEC(...)] ...} → name and normalized type. */
  private static Column column(String definition) {
    String text = definition.strip();
    int space = text.indexOf(' ');
    String name = text.substring(0, space);
    String rest = text.substring(space + 1).strip();
    StringBuilder type = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    int i = 0;
    while (i < rest.length()) {
      char ch = rest.charAt(i);
      if (quoted) {
        type.append(ch);
        if (ch == '\'') {
          quoted = false;
        }
        i++;
        continue;
      }
      if (ch == '\'') {
        quoted = true;
      } else if (ch == '(') {
        depth++;
      } else if (ch == ')') {
        depth--;
      } else if (depth == 0 && Character.isWhitespace(ch)) {
        String next = firstWord(rest.substring(i).strip()).toUpperCase(Locale.ROOT);
        if (TYPE_END.contains(next)) {
          break;
        }
      }
      type.append(ch);
      i++;
    }
    return new Column(name, normalize(type.toString()));
  }

  /** Single spaces, none inside the parentheses' edges, {@code ", "} after every comma. */
  private static String normalize(String type) {
    StringBuilder out = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < type.length(); i++) {
      char ch = type.charAt(i);
      if (quoted) {
        out.append(ch);
        quoted = ch != '\'';
        continue;
      }
      if (ch == '\'') {
        quoted = true;
        out.append(ch);
      } else if (Character.isWhitespace(ch)) {
        if (out.length() > 0
            && out.charAt(out.length() - 1) != ' '
            && out.charAt(out.length() - 1) != '(') {
          out.append(' ');
        }
      } else if (ch == ')' || ch == ',') {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
          out.setLength(out.length() - 1);
        }
        out.append(ch);
        if (ch == ',') {
          out.append(' ');
        }
      } else {
        out.append(ch);
      }
    }
    return out.toString().strip();
  }

  private static String firstWord(String text) {
    String stripped = text.strip();
    int end = 0;
    while (end < stripped.length()
        && (Character.isLetterOrDigit(stripped.charAt(end)) || stripped.charAt(end) == '_')) {
      end++;
    }
    return stripped.substring(0, end);
  }

  /** {@code --} comments removed outside quoted text. */
  private static String withoutComments(String sql) {
    StringBuilder out = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      if (!quoted && ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
        out.append('\n');
        continue;
      }
      if (ch == '\'') {
        quoted = !quoted;
      }
      out.append(ch);
    }
    return out.toString();
  }

  private static List<String> statements(String sql) {
    return splitTopLevel(sql, ';').stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  /** Splits at {@code separator} outside parentheses and quoted text. */
  private static List<String> splitTopLevel(String text, char separator) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\'') {
        quoted = !quoted;
      } else if (!quoted && ch == '(') {
        depth++;
      } else if (!quoted && ch == ')') {
        depth--;
      } else if (!quoted && depth == 0 && ch == separator) {
        parts.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    if (!current.toString().isBlank()) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static int closing(String text, int open) {
    int depth = 0;
    boolean quoted = false;
    for (int i = open; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\'') {
        quoted = !quoted;
      } else if (!quoted && ch == '(') {
        depth++;
      } else if (!quoted && ch == ')' && --depth == 0) {
        return i;
      }
    }
    throw new AssertionError("Unbalanced parentheses in a CREATE TABLE statement");
  }
}
