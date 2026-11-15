/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Renders the ClickHouse templates of a {@link MigrationSource} (13 §4.1, §13.2, ADR-0007 C-56).
 *
 * <ul>
 *   <li>Schema scripts ({@code schema/V<NNN>__<desc>.sql}) and procedure statements: environment
 *       placeholders {@code {db}}, {@code {p}}, {@code {retention.<key>}} and the include directive
 *       {@code {rollup:<table>}}, which inserts the SELECT of {@code rollups/<table>.sql} with an
 *       empty WHERE extension (the materialized view body). Nothing else is allowed.
 *   <li>Rollup files ({@code rollups/<table>.sql}): one SELECT, placeholders {@code {db}}, {@code
 *       {p}} and {@code {where}} only. {@code {where}} follows a WHERE clause and is replaced by
 *       {@code ''} (materialized view) or {@code AND (<predicate>)} (backfill, rebuild, D-14).
 * </ul>
 *
 * Rendering is two steps: {@link #expand} replaces every {@code {rollup:<table>}} directive and
 * leaves the environment placeholders as written; {@link #render} substitutes them and splits the
 * statements. {@link #checksum} is the lower-case hex SHA-256 of the UTF-8 bytes of the expanded
 * text (C-56), so the checksum in {@code ch_schema_history} does not depend on the database name,
 * the table prefix or the retention, and changes when the script or an included rollup SELECT
 * changes. Placeholders in comments and quoted text are not touched. Line endings are normalized to
 * LF before anything else.
 *
 * <p>Ported from {@code io.argos.storage.schema.SchemaRenderer} of {@code
 * test/storage/clickhouse-support} (development-only reference of WO-03/WO-03F); {@code
 * SchemaTemplatesEquivalenceTest} proves identical statements and checksums for every file of
 * {@code deploy/clickhouse/schema}.
 */
final class SchemaTemplates {

  private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern WHERE_EXTENSION = Pattern.compile("(?is)AND\\s+\\S.*");
  private static final String ROLLUP_DIRECTIVE = "rollup:";
  private static final String RETENTION = "retention.";

  private final MigrationSource source;

  SchemaTemplates(MigrationSource source) {
    this.source = source;
  }

  /** Tables that have a rollup SELECT file. */
  Set<String> rollupTables() {
    Set<String> tables = new TreeSet<>();
    for (String name : source.names("rollups")) {
      if (name.endsWith(".sql")) {
        tables.add(name.substring(0, name.length() - 4));
      }
    }
    return tables;
  }

  /**
   * A template with every {@code {rollup:<table>}} directive replaced by the rollup SELECT of
   * {@code rollups/<table>.sql} (comments removed, {@code {where}} = {@code ''}); the environment
   * placeholders stay as written, also inside the inserted SELECTs.
   *
   * @param template script text or one procedure statement
   * @param origin file name for messages
   * @throws IllegalArgumentException for a placeholder other than {@code {db}}, {@code {p}}, {@code
   *     {retention.<key>}} or {@code {rollup:<table>}} of an existing rollup file
   */
  String expand(String template, String origin) {
    String text = template.replace("\r\n", "\n");
    Set<String> rollups = rollupTables();
    List<String> unknown = new ArrayList<>();
    for (String name : SqlScript.placeholders(text)) {
      boolean known =
          isEnvironmentPlaceholder(name)
              || (name.startsWith(ROLLUP_DIRECTIVE)
                  && rollups.contains(name.substring(ROLLUP_DIRECTIVE.length())));
      if (!known) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          origin
              + ": unknown placeholders "
              + unknown
              + " (allowed: {db}, {p}, {retention.<key>}, {rollup:<table>}; C-56)");
    }
    return SqlScript.replacePlaceholders(
        text,
        name ->
            name.startsWith(ROLLUP_DIRECTIVE)
                ? SqlScript.replacePlaceholders(
                    rollupTemplate(name.substring(ROLLUP_DIRECTIVE.length())),
                    inner -> inner.equals("where") ? "" : "{" + inner + "}")
                : "{" + name + "}");
  }

  /** {@link #expand} of a file of the source. */
  String expandFile(String path) {
    String template =
        source
            .read(path)
            .orElseThrow(() -> new IllegalArgumentException("No such migration file: " + path));
    return expand(template, path);
  }

  /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code text} (C-56 checksum of an expansion). */
  static String checksum(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Substitutes the environment placeholders of an expanded template and splits the statements. */
  static List<String> render(String expanded, SchemaParameters params) {
    Map<String, String> values = params.placeholderValues();
    String rendered =
        SqlScript.replacePlaceholders(
            expanded,
            name -> {
              String value = values.get(name);
              if (value == null) {
                throw new IllegalArgumentException("Unexpected placeholder {" + name + "}");
              }
              return value;
            });
    return SqlScript.split(rendered);
  }

  /** Environment placeholders: {@code db}, {@code p}, retention keys. */
  private static boolean isEnvironmentPlaceholder(String name) {
    return name.equals("db")
        || name.equals("p")
        || (name.startsWith(RETENTION)
            && SchemaParameters.RETENTION_KEYS.contains(name.substring(RETENTION.length())));
  }

  /**
   * The rollup SELECT of {@code table} without comments, rendered.
   *
   * @param whereExtension {@code ""} (materialized view) or {@code "AND (<predicate>)"}
   */
  String rollupSelect(String table, SchemaParameters params, String whereExtension) {
    if (!whereExtension.isEmpty() && !WHERE_EXTENSION.matcher(whereExtension).matches()) {
      throw new IllegalArgumentException(
          "{where} must be '' or 'AND <predicate>': " + whereExtension);
    }
    Map<String, String> values =
        Map.of("db", params.database(), "p", params.tablePrefix(), "where", whereExtension);
    return SqlScript.replacePlaceholders(rollupTemplate(table), values::get);
  }

  /**
   * The SELECT of {@code rollups/<table>.sql} without comments, placeholders as written.
   *
   * @throws IllegalArgumentException when the file is missing, has other placeholders than {@code
   *     {db}}, {@code {p}}, {@code {where}} (required), or is not exactly one statement
   */
  String rollupTemplate(String table) {
    if (!TABLE_NAME.matcher(table).matches()) {
      throw new IllegalArgumentException("Invalid rollup table name: " + table);
    }
    String path = "rollups/" + table + ".sql";
    String file =
        source
            .read(path)
            .orElseThrow(() -> new IllegalArgumentException("No rollup SELECT file " + path));
    String text = SqlScript.withoutComments(file.replace("\r\n", "\n")).strip();
    Set<String> used = SqlScript.placeholders(text);
    if (!Set.of("db", "p", "where").containsAll(used) || !used.contains("where")) {
      throw new IllegalArgumentException(
          path + ": placeholders must be {db}, {p}, {where} (with {where}): " + used);
    }
    if (SqlScript.split(text).size() != 1 || text.endsWith(";")) {
      throw new IllegalArgumentException(path + ": one SELECT without ';' expected");
    }
    return text;
  }
}
