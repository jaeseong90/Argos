/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rollup rebuild procedure of ADR-0007 C-59 (13 §4.1, D-14, F-S22) as statements for the
 * migration account:
 *
 * <ol>
 *   <li>(a) each partition is rebuilt into a staging table ({@code CREATE TABLE <t>_rebuild AS
 *       <t>}, then {@code INSERT INTO <t>_rebuild <rollup SELECT>}) and swapped in with {@code
 *       ALTER TABLE <t> REPLACE PARTITION ... FROM <t>_rebuild}; nothing is inserted into a rollup
 *       table, which would count the rows a second time in the 1h cascade;
 *   <li>(b) rebuilding a 1m day rebuilds the 1h month of that day from the 1m tables afterwards
 *       ({@code REPLACE PARTITION} does not feed the views);
 *   <li>(c) the unit is a whole partition (every application): a staging table with one application
 *       would remove the other applications of the partition.
 * </ol>
 *
 * The rebuild SELECT is the rollup file the materialized view uses ({@link SchemaRenderer}).
 */
public final class RollupRebuild {

  /** Suffix of the staging table: {@code <rollup table>_rebuild}. */
  public static final String STAGING_SUFFIX = "_rebuild";

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

  private RollupRebuild() {}

  /** True for the 1h rollups (source: a 1m table, partition: month). */
  public static boolean hourly(String rollupTable) {
    return rollupTable.endsWith("_1h");
  }

  /** The 1m partition literal of a day. */
  public static String dayPartition(LocalDate day) {
    return "'" + day + "'";
  }

  /** The 1h partition literal of the month of a day ({@code toYYYYMM}). */
  public static String monthPartition(LocalDate day) {
    return MONTH.format(day);
  }

  /**
   * The rebuild predicate on the source of {@code rollupTable} for the partition of {@code day}.
   */
  public static String sourcePredicate(String rollupTable, LocalDate day) {
    return hourly(rollupTable)
        ? "toYYYYMM(minute) = " + monthPartition(day)
        : "toDate(ts) = " + dayPartition(day);
  }

  /** The partition literal of {@code rollupTable} that contains {@code day}. */
  public static String partition(String rollupTable, LocalDate day) {
    return hourly(rollupTable) ? monthPartition(day) : dayPartition(day);
  }

  /** Fully qualified staging table of a rollup table. */
  public static String staging(SchemaParameters params, String rollupTable) {
    return params.table(rollupTable + STAGING_SUFFIX);
  }

  /**
   * The statements that fill a fresh staging table with the rebuild SELECT of {@code rollupTable}
   * for the partition of {@code day} (drop a leftover staging table, create, insert).
   */
  public static List<String> fillStaging(
      SchemaRenderer renderer, SchemaParameters params, String rollupTable, LocalDate day) {
    String staging = staging(params, rollupTable);
    return List.of(
        "DROP TABLE IF EXISTS " + staging + " SYNC",
        "CREATE TABLE " + staging + " AS " + params.table(rollupTable),
        renderer.rebuildInsert(rollupTable, staging, params, sourcePredicate(rollupTable, day)));
  }

  /**
   * Rebuild of one partition of one rollup table (C-59 a, c): fill the staging table, {@code
   * REPLACE PARTITION}, drop the staging table.
   */
  public static List<String> partitionStatements(
      SchemaRenderer renderer, SchemaParameters params, String rollupTable, LocalDate day) {
    String staging = staging(params, rollupTable);
    List<String> statements = new ArrayList<>(fillStaging(renderer, params, rollupTable, day));
    statements.add(
        "ALTER TABLE "
            + params.table(rollupTable)
            + " REPLACE PARTITION "
            + partition(rollupTable, day)
            + " FROM "
            + staging);
    statements.add("DROP TABLE " + staging + " SYNC");
    return statements;
  }

  /**
   * Rebuild of one day (C-59 b): every 1m rollup table for the day, then every 1h rollup table for
   * the month of the day, from the rebuilt 1m tables.
   */
  public static List<String> dayStatements(
      SchemaRenderer renderer, SchemaParameters params, LocalDate day) {
    List<String> statements = new ArrayList<>();
    for (boolean hourlyPass : new boolean[] {false, true}) {
      for (String table : renderer.rollupTables()) {
        if (hourly(table) == hourlyPass) {
          statements.addAll(partitionStatements(renderer, params, table, day));
        }
      }
    }
    return statements;
  }
}
