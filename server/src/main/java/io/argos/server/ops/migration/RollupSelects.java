/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rollup SELECTs of the bundled templates ({@code rollups/<table>.sql}, D-14) for the rollup
 * rebuild outside the start sequence ({@code io.argos.server.ops.rollup}, WO-27): the same files
 * and the same {@code {where}} rendering as the materialized views and the migration backfill, so a
 * rebuild writes exactly what the views write.
 */
public final class RollupSelects {

  private static final Pattern SOURCE_TABLE =
      Pattern.compile("(?i)\\bFROM\\s+\\{db}\\.\\{p}([a-z][a-z0-9_]*)");

  private static final Pattern SCHEMA_VERSION = Pattern.compile("(V\\d+)__.+\\.sql");

  private final MigrationSource source;
  private final SchemaTemplates templates;

  private RollupSelects(MigrationSource source) {
    this.source = source;
    this.templates = new SchemaTemplates(source);
  }

  /**
   * The versions of the bundled schema scripts ({@code schema/V<n>__<name>.sql}), sorted. A rebuild
   * needs every one of them applied: the rollup SELECTs belong to that schema.
   */
  public List<String> schemaVersions() {
    List<String> versions = new ArrayList<>();
    for (String name : source.names("schema")) {
      Matcher m = SCHEMA_VERSION.matcher(name);
      if (m.matches()) {
        versions.add(m.group(1));
      }
    }
    versions.sort(null);
    return versions;
  }

  /** The rollup SELECTs of {@code source} (usually {@link MigrationSource#fromClasspath}). */
  public static RollupSelects of(MigrationSource source) {
    return new RollupSelects(source);
  }

  /** Tables that have a rollup SELECT file, sorted by name. */
  public Set<String> tables() {
    return templates.rollupTables();
  }

  /**
   * The table the rollup SELECT of {@code table} reads ({@code FROM {db}.{p}<source>}): a raw table
   * for a 1m rollup, a 1m rollup for a 1h rollup (cascade).
   *
   * @throws IllegalArgumentException when there is no such rollup file
   */
  public Optional<String> sourceTable(String table) {
    Matcher m = SOURCE_TABLE.matcher(templates.rollupTemplate(table));
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  /**
   * The rendered rollup SELECT of {@code table}.
   *
   * @param whereExtension {@code ""} (the view body) or {@code "AND (<predicate>)"} (a partition)
   * @throws IllegalArgumentException for an unknown table or an invalid extension
   */
  public String select(String table, SchemaParameters params, String whereExtension) {
    return templates.rollupSelect(table, params, whereExtension);
  }
}
