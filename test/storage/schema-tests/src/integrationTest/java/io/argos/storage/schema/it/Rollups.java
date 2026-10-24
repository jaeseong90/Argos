/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.util.ArrayList;
import java.util.List;

/**
 * Rollup comparisons and the rollup rebuild procedure for the schema tests (13 §4.1, D-14).
 *
 * <p>A rollup table is compared through its <em>finalized</em> rows: one row per full key (every
 * plain column: app_id, env, kind, the other dimensions, minute or hour), each {@code
 * SimpleAggregateFunction} column aggregated with its own function and each {@code sumForEach}
 * state merged ({@code sumForEachMerge}). Two columns are left out because their merged value is
 * not a function of the data alone:
 *
 * <ul>
 *   <li>{@code q_elapsed} (tDigest state): the merged value depends on the merge order (PoC §8,
 *       UpgradeIT), so a rebuild cannot reproduce it.
 *   <li>{@code last_v} ({@code anyLast}): "the last value" depends on the order in which blocks and
 *       parts are read (PoC §14.3).
 * </ul>
 *
 * Any other aggregate column type fails the comparison instead of being skipped silently.
 */
final class Rollups {

  /** The rebuild day of the schema tests (the 1m partition). */
  static final String DAY = "2026-10-24";

  /** The rebuild month of the schema tests (the 1h partition). */
  static final int MONTH = 202610;

  private Rollups() {}

  /** Column names and types of a table (needs SELECT only). */
  static List<List<String>> columns(ClickHouseHttp ch, String table) {
    List<List<String>> rows =
        ch.rows("SELECT * FROM " + table + " LIMIT 0 FORMAT TabSeparatedWithNamesAndTypes");
    List<List<String>> columns = new ArrayList<>();
    for (int i = 0; i < rows.get(0).size(); i++) {
      columns.add(List.of(rows.get(0).get(i), rows.get(1).get(i)));
    }
    return columns;
  }

  /**
   * One row per full key of {@code rollupTable} (columns of {@code p.table(rollupTable)}), read
   * from {@code source} (the rollup table itself, a staging copy, or a parenthesized rollup
   * SELECT), restricted by {@code where} ("" for all).
   */
  static String finalized(
      ClickHouseHttp ch, SchemaParameters p, String rollupTable, String source, String where) {
    List<String> keys = new ArrayList<>();
    List<String> measures = new ArrayList<>();
    for (List<String> column : columns(ch, p.table(rollupTable))) {
      String name = column.get(0);
      String type = column.get(1);
      if (type.startsWith("SimpleAggregateFunction(")) {
        String function = type.substring("SimpleAggregateFunction(".length(), type.indexOf(','));
        switch (function) {
          case "sum", "min", "max" -> measures.add(function + "(" + name + ") AS " + name);
          case "anyLast" -> {
            // Order-dependent, see the class comment.
          }
          default -> throw new IllegalStateException(rollupTable + "." + name + ": " + type);
        }
      } else if (type.startsWith("AggregateFunction(sumForEach,")) {
        measures.add("sumForEachMerge(" + name + ") AS " + name);
      } else if (type.startsWith("AggregateFunction(")) {
        // tDigest states are merge-order dependent (class comment); any other state is unknown.
        if (!type.startsWith("AggregateFunction(quantilesTDigest(")) {
          throw new IllegalStateException(rollupTable + "." + name + ": " + type);
        }
      } else {
        keys.add(name);
      }
    }
    String key = String.join(", ", keys);
    return "SELECT "
        + key
        + ", "
        + String.join(", ", measures)
        + " FROM "
        + source
        + (where.isEmpty() ? "" : " WHERE " + where)
        + " GROUP BY "
        + key;
  }

  /** Rows that appear in only one of the two result sets (symmetric difference size). */
  static long differences(ClickHouseHttp ch, String left, String right) {
    return ch.scalarLong(
        "SELECT (SELECT count() FROM (("
            + left
            + ") EXCEPT ("
            + right
            + ")))"
            + " + (SELECT count() FROM (("
            + right
            + ") EXCEPT ("
            + left
            + ")))");
  }

  /** Number of finalized rows (full keys). */
  static long keyCount(ClickHouseHttp ch, String finalizedSelect) {
    return ch.scalarLong("SELECT count() FROM (" + finalizedSelect + ")");
  }

  /** True for the 1h rollups (source: a 1m table, partition: month). */
  static boolean hourly(String rollupTable) {
    return rollupTable.endsWith("_1h");
  }

  /** The rebuild predicate on the source of {@code rollupTable} for the test day or month. */
  static String sourcePredicate(String rollupTable) {
    return hourly(rollupTable) ? "toYYYYMM(minute) = " + MONTH : "toDate(ts) = '" + DAY + "'";
  }

  /** The same day or month on the rollup table itself. */
  static String rollupPredicate(String rollupTable) {
    return hourly(rollupTable) ? "toYYYYMM(hour) = " + MONTH : "toDate(minute) = '" + DAY + "'";
  }

  /** The partition literal of the test day or month. */
  static String partition(String rollupTable) {
    return hourly(rollupTable) ? Integer.toString(MONTH) : "'" + DAY + "'";
  }

  /** Name of the staging table of a rebuild. */
  static String staging(SchemaParameters p, String rollupTable) {
    return p.table(rollupTable + "_rebuild");
  }

  /**
   * Fills a fresh staging table ({@code CREATE TABLE ... AS <rollup>}) with the rebuild SELECT of
   * the rollup file for the test day (1m) or month (1h) and returns its name.
   */
  static String fillStaging(
      ClickHouseHttp migrator, SchemaRenderer renderer, SchemaParameters p, String rollupTable) {
    String staging = staging(p, rollupTable);
    migrator.execute("DROP TABLE IF EXISTS " + staging + " SYNC");
    migrator.execute("CREATE TABLE " + staging + " AS " + p.table(rollupTable));
    migrator.execute(renderer.rebuildInsert(rollupTable, staging, p, sourcePredicate(rollupTable)));
    return staging;
  }

  /**
   * The rebuild procedure (README "Rollup rebuild"): staging table, then {@code REPLACE PARTITION}
   * of the whole day (1m) or month (1h), then the staging table is dropped.
   */
  static void rebuildPartition(
      ClickHouseHttp migrator, SchemaRenderer renderer, SchemaParameters p, String rollupTable) {
    String staging = fillStaging(migrator, renderer, p, rollupTable);
    migrator.execute(
        "ALTER TABLE "
            + p.table(rollupTable)
            + " REPLACE PARTITION "
            + partition(rollupTable)
            + " FROM "
            + staging);
    migrator.execute("DROP TABLE " + staging + " SYNC");
  }
}
