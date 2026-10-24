/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders the ClickHouse schema templates of {@code deploy/clickhouse} (13 §4.1, §13.2).
 *
 * <ul>
 *   <li>{@code schema/V{NNN}__{desc}.sql}: placeholders {@code {db}}, {@code {p}}, {@code
 *       {retention.<key>}}, {@code {bloom.<index>.fpp|granularity}} and the include directive
 *       {@code {rollup:<table>}}, which inserts the SELECT of {@code rollups/<table>.sql} with an
 *       empty WHERE extension (the materialized view body).
 *   <li>{@code rollups/<table>.sql}: one SELECT, placeholders {@code {db}}, {@code {p}}, {@code
 *       {where}} only. Contract: {@code {where}} follows a WHERE clause and is replaced by {@code
 *       ''} (materialized view) or by {@code AND (<predicate>)} (rebuild, D-14). The predicate
 *       names columns of the source table: {@code ts} for raw sources, {@code minute} for 1m
 *       sources.
 * </ul>
 *
 * Unknown placeholders are an error. Placeholders in comments and quoted text are not touched.
 */
public final class SchemaRenderer {

  private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d{3})__[a-z0-9_]+\\.sql");
  private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern WHERE_EXTENSION = Pattern.compile("(?is)AND\\s+\\S.*");
  private static final Pattern CODEC =
      Pattern.compile("\\s*CODEC\\s*\\((?:[^()]|\\([^()]*\\))*\\)", Pattern.CASE_INSENSITIVE);
  private static final String ROLLUP_DIRECTIVE = "rollup:";

  private final Path clickhouseDir;

  /**
   * @param clickhouseDir directory with {@code schema/} and {@code rollups/}
   */
  public SchemaRenderer(Path clickhouseDir) {
    this.clickhouseDir = clickhouseDir;
  }

  /** Renderer of the repository's {@code deploy/clickhouse}. */
  public static SchemaRenderer fromRepository() {
    return new SchemaRenderer(RepositoryLayout.clickhouseDir());
  }

  /** Migration scripts in version order. */
  public List<Path> migrations() {
    try (Stream<Path> files = Files.list(clickhouseDir.resolve("schema"))) {
      List<Path> result =
          files
              .filter(f -> MIGRATION_FILE.matcher(f.getFileName().toString()).matches())
              .sorted(Comparator.comparing(f -> f.getFileName().toString()))
              .toList();
      if (result.isEmpty()) {
        throw new IllegalStateException("No V*.sql in " + clickhouseDir.resolve("schema"));
      }
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Tables that have a rollup SELECT file. */
  public Set<String> rollupTables() {
    try (Stream<Path> files = Files.list(clickhouseDir.resolve("rollups"))) {
      Set<String> tables = new TreeSet<>();
      files
          .map(f -> f.getFileName().toString())
          .filter(name -> name.endsWith(".sql"))
          .forEach(name -> tables.add(name.substring(0, name.length() - 4)));
      return tables;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Renders every migration, in order, into executable statements. */
  public List<String> renderAll(SchemaParameters params) {
    List<String> statements = new ArrayList<>();
    for (Path migration : migrations()) {
      statements.addAll(render(migration, params));
    }
    return statements;
  }

  /** Renders one migration script into executable statements. */
  public List<String> render(Path migration, SchemaParameters params) {
    String template = read(migration);
    Map<String, String> values = params.placeholderValues();
    Set<String> rollups = rollupTables();
    List<String> unknown = new ArrayList<>();
    for (String name : SqlScript.placeholders(template)) {
      boolean known =
          values.containsKey(name)
              || (name.startsWith(ROLLUP_DIRECTIVE)
                  && rollups.contains(name.substring(ROLLUP_DIRECTIVE.length())));
      if (!known) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          migration.getFileName() + ": unknown placeholders " + unknown);
    }
    String rendered =
        SqlScript.replacePlaceholders(
            template,
            name ->
                name.startsWith(ROLLUP_DIRECTIVE)
                    ? rollupSelect(name.substring(ROLLUP_DIRECTIVE.length()), params, "")
                    : values.get(name));
    return SqlScript.split(rendered);
  }

  /**
   * The rollup SELECT of {@code table} without comments.
   *
   * @param whereExtension {@code ""} (materialized view) or {@code "AND (<predicate>)"} (rebuild)
   */
  public String rollupSelect(String table, SchemaParameters params, String whereExtension) {
    if (!TABLE_NAME.matcher(table).matches()) {
      throw new IllegalArgumentException("Invalid rollup table name: " + table);
    }
    if (!whereExtension.isEmpty() && !WHERE_EXTENSION.matcher(whereExtension).matches()) {
      throw new IllegalArgumentException(
          "{where} must be '' or 'AND <predicate>': " + whereExtension);
    }
    Path file = clickhouseDir.resolve("rollups").resolve(table + ".sql");
    String text = SqlScript.withoutComments(read(file)).strip();
    Set<String> used = SqlScript.placeholders(text);
    if (!Set.of("db", "p", "where").containsAll(used) || !used.contains("where")) {
      throw new IllegalArgumentException(
          file.getFileName() + ": placeholders must be {db}, {p}, {where} (with {where}): " + used);
    }
    if (SqlScript.split(text).size() != 1 || text.endsWith(";")) {
      throw new IllegalArgumentException(file.getFileName() + ": one SELECT without ';' expected");
    }
    Map<String, String> values =
        Map.of("db", params.database(), "p", params.tablePrefix(), "where", whereExtension);
    return SqlScript.replacePlaceholders(text, values::get);
  }

  /**
   * Rebuild statement of 13 §4.1 (D-14): {@code INSERT INTO <target> <rollup SELECT> AND
   * (<predicate>)}. The target must be a staging table that then replaces the whole partition
   * ({@code REPLACE PARTITION}): a 1m table by day ({@code toDate(ts) = D}), a 1h table by month of
   * its 1m source ({@code toYYYYMM(minute) = M}). Inserting into the rollup table itself counts the
   * rows twice in the 1h cascade (deploy/clickhouse/README.md "Rollup rebuild", RollupRebuildIT).
   */
  public String rebuildInsert(
      String rollupTable, String targetTable, SchemaParameters params, String predicate) {
    return "INSERT INTO "
        + targetTable
        + "\n"
        + rollupSelect(rollupTable, params, "AND (" + predicate + ")");
  }

  /** Removes every column {@code CODEC(...)} clause (Q20: column codecs off variant). */
  public static String withoutColumnCodecs(String statement) {
    StringBuilder out = new StringBuilder();
    for (SqlScript.Segment s : SqlScript.lex(statement)) {
      if (s.kind() == SqlScript.Kind.CODE) {
        Matcher m = CODEC.matcher(s.text());
        out.append(m.replaceAll(""));
      } else {
        out.append(s.text());
      }
    }
    return out.toString();
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }
}
