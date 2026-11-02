/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The rollup rebuild path (13 §4.1, D-14, F-S22, ADR-0007 C-59) and its interaction with the 1h
 * cascade and the monthly 1h partitions. The workload covers 23:40 of the anchor day ({@link
 * Workload#anchorDay()}) to 00:10 of the next day: two daily 1m partitions and two hours, of one
 * monthly 1h partition (of two when the anchor day is the last day of its month; the 1h rebuild is
 * of the month of the anchor day either way).
 *
 * <ul>
 *   <li>The procedure of deploy/clickhouse/README.md ("Rollup rebuild"): every 1m table of the day
 *       through a staging table and {@code REPLACE PARTITION}, then every 1h table of the month
 *       from its 1m source the same way. It repairs a damaged day and leaves the other day as it
 *       was.
 *   <li>The statement of 13 §4.1 ({@code INSERT INTO <rollup> <SELECT> WHERE toDate(ts) = :date})
 *       run on the rollup table itself counts the day twice in the 1h tables, even after {@code
 *       DROP PARTITION} of the 1m day: the 1h views cascade from the 1m tables. This is the reason
 *       for C-59 (a); the tests run it only as damage (first test) and as a negative control
 *       (second test), never as a rebuild.
 * </ul>
 *
 * <p>Every comparison includes the q_elapsed quantilesDD states byte for byte (C-53, Rollups): the
 * repaired day equals the rollup SELECT over its source, state bytes included.
 */
@Tag("integration")
@Tag("req:D-14")
@Tag("req:F-S22")
class RollupRebuildIT {

  private static final Instant START = Workload.at(LocalTime.of(23, 40));
  private static final String OTHER_DAY = Workload.day(1);

  /** The two hours of the workload, as DateTime literals. */
  private static final String HOUR_23 = Workload.seconds(Workload.at(LocalTime.of(23, 0)));

  private static final String HOUR_00 = Workload.seconds(Workload.at(1, LocalTime.MIDNIGHT));

  /** Raw source and per-hour measure of each 1h rollup (raw <-> 1h, independent of the files). */
  private static final Map<String, String[]> HOURLY_RAW = new LinkedHashMap<>();

  static {
    HOURLY_RAW.put("app_stat_1h", new String[] {"xlog", "count()", "sum(count)", "app_id, kind"});
    HOURLY_RAW.put(
        "service_stat_1h",
        new String[] {"xlog", "count()", "sum(count)", "app_id, kind, service_id, app_version_id"});
    HOURLY_RAW.put(
        "sql_stat_1h",
        new String[] {"sql_stat_raw", "sum(count)", "sum(count)", "app_id, kind, sql_id, db_id"});
    HOURLY_RAW.put(
        "ext_stat_1h",
        new String[] {"ext_stat_raw", "sum(count)", "sum(count)", "app_id, kind, host_id"});
    HOURLY_RAW.put(
        "metric_1h",
        new String[] {"metric", "count()", "sum(sample_count)", "app_id, instance_id, name"});
  }

  @Test
  @DisplayName(
      "T-ST-030 D-14 rebuild procedure: 1m day and 1h month via staging + REPLACE PARTITION repair"
          + " a double-counted day; the other day is kept")
  void stagingAndReplacePartitionRepairADay() {
    SchemaParameters p = Fixture.schema("rb_");
    ClickHouseHttp migrator = Fixture.migrator();
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    RollupViewsIT.insertAll(Fixture.writer(), p, Workload.generate(77, START, 30, 120), "rb");
    List<String> minuteTables = tables(renderer, false);
    List<String> hourTables = tables(renderer, true);
    assertThat(minuteTables).hasSize(7);
    assertThat(hourTables).hasSize(5);
    assertConsistent(migrator, renderer, p);

    Map<String, List<List<String>>> otherDayBefore = otherDay(migrator, p, minuteTables);
    long rawDay = rawCount(migrator, p, "toDate(ts) = '" + Rollups.DAY + "'");
    long rawHour23 = rawCount(migrator, p, "toStartOfHour(ts) = '" + HOUR_23 + "'");
    assertThat(rawDay).isPositive().isEqualTo(rawHour23);

    // Damage: the 13 §4.1 statement run on a healthy day counts it twice in 1m and in 1h.
    for (String table : minuteTables) {
      migrator.execute(
          renderer.rebuildInsert(table, p.table(table), p, Rollups.sourcePredicate(table)));
    }
    assertThat(appCount(migrator, p, "app_stat_1m", "toDate(minute) = '" + Rollups.DAY + "'"))
        .isEqualTo(2 * rawDay);
    assertThat(appCount(migrator, p, "app_stat_1h", "hour = '" + HOUR_23 + "'"))
        .isEqualTo(2 * rawHour23);

    // Repair the 1m day. REPLACE PARTITION does not feed the 1h views: 1h is still doubled.
    for (String table : minuteTables) {
      Rollups.rebuildPartition(migrator, renderer, p, table);
    }
    assertThat(appCount(migrator, p, "app_stat_1m", "toDate(minute) = '" + Rollups.DAY + "'"))
        .isEqualTo(rawDay);
    assertThat(appCount(migrator, p, "app_stat_1h", "hour = '" + HOUR_23 + "'"))
        .isEqualTo(2 * rawHour23);

    // Repair the 1h month from the 1m tables.
    for (String table : hourTables) {
      Rollups.rebuildPartition(migrator, renderer, p, table);
    }

    assertConsistent(migrator, renderer, p);
    assertThat(otherDay(migrator, p, minuteTables)).isEqualTo(otherDayBefore);
    for (String table : renderer.rollupTables()) {
      assertThat(migrator.scalarLong("EXISTS TABLE " + Rollups.staging(p, table)))
          .as(table)
          .isZero();
    }
  }

  @Test
  @DisplayName(
      "T-ST-030 13 §4.1 in-place rebuild (DROP PARTITION + INSERT INTO the rollup) doubles the day"
          + " in the 1h cascade")
  void inPlaceRebuildDoublesTheHourlyCascade() {
    SchemaParameters p = Fixture.schema("rbx_");
    ClickHouseHttp migrator = Fixture.migrator();
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    RollupViewsIT.insertAll(Fixture.writer(), p, Workload.generate(78, START, 30, 120), "rbx");
    assertConsistent(migrator, renderer, p);
    long rawHour23 = rawCount(migrator, p, "toStartOfHour(ts) = '" + HOUR_23 + "'");
    long rawHour00 = rawCount(migrator, p, "toStartOfHour(ts) = '" + HOUR_00 + "'");

    for (String table : tables(renderer, false)) {
      migrator.execute(
          "ALTER TABLE " + p.table(table) + " DROP PARTITION " + Rollups.partition(table));
      migrator.execute(
          renderer.rebuildInsert(table, p.table(table), p, Rollups.sourcePredicate(table)));
    }

    // The 1m tables are right again ...
    for (String table : tables(renderer, false)) {
      String view = Rollups.finalized(migrator, p, table, p.table(table), "");
      String expected =
          Rollups.finalized(
              migrator, p, table, "(" + renderer.rollupSelect(table, p, "") + ")", "");
      assertThat(Rollups.differences(migrator, view, expected)).as(table).isZero();
    }
    // ... but every 1h table received the day a second time through its view.
    assertThat(appCount(migrator, p, "app_stat_1h", "hour = '" + HOUR_23 + "'"))
        .isEqualTo(2 * rawHour23);
    assertThat(appCount(migrator, p, "app_stat_1h", "hour = '" + HOUR_00 + "'"))
        .isEqualTo(rawHour00);
    HOURLY_RAW.forEach(
        (table, spec) -> {
          long raw =
              migrator.scalarLong(
                  "SELECT "
                      + spec[1]
                      + " FROM "
                      + p.table(spec[0])
                      + " WHERE toDate(ts) = '"
                      + Rollups.DAY
                      + "'");
          long hourly =
              migrator.scalarLong(
                  "SELECT "
                      + spec[2]
                      + " FROM "
                      + p.table(table)
                      + " WHERE toDate(hour) = '"
                      + Rollups.DAY
                      + "'");
          Evidence.record(
              "rollups", "in-place rebuild, 1h day total vs raw: " + table, hourly + " vs " + raw);
          assertThat(raw).as(table).isPositive();
          assertThat(hourly).as(table).isEqualTo(2 * raw);
        });
  }

  /** raw <-> 1m per key and minute, 1m <-> 1h per key and hour, raw <-> 1h per hour. */
  private static void assertConsistent(
      ClickHouseHttp migrator, SchemaRenderer renderer, SchemaParameters p) {
    for (String table : renderer.rollupTables()) {
      // The rollup SELECT over its whole source (raw for 1m, the 1m table for 1h) as reference.
      String stored = Rollups.finalized(migrator, p, table, p.table(table), "");
      String fromSource =
          Rollups.finalized(
              migrator, p, table, "(" + renderer.rollupSelect(table, p, "") + ")", "");
      assertThat(Rollups.keyCount(migrator, stored)).as(table).isPositive();
      assertThat(Rollups.differences(migrator, stored, fromSource)).as(table).isZero();
    }
    HOURLY_RAW.forEach(
        (table, spec) -> {
          String raw =
              "SELECT "
                  + spec[3]
                  + ", toStartOfHour(ts) AS hour, "
                  + spec[1]
                  + " AS c FROM "
                  + p.table(spec[0])
                  + " GROUP BY "
                  + spec[3]
                  + ", hour";
          String hourly =
              "SELECT "
                  + spec[3]
                  + ", hour, "
                  + spec[2]
                  + " AS c FROM "
                  + p.table(table)
                  + " GROUP BY "
                  + spec[3]
                  + ", hour";
          assertThat(Rollups.differences(migrator, raw, hourly)).as(table).isZero();
        });
  }

  private static List<String> tables(SchemaRenderer renderer, boolean hourly) {
    return renderer.rollupTables().stream().filter(t -> Rollups.hourly(t) == hourly).toList();
  }

  /** The finalized rows of the day that the rebuild must not touch, in a fixed order. */
  private static Map<String, List<List<String>>> otherDay(
      ClickHouseHttp migrator, SchemaParameters p, List<String> minuteTables) {
    Map<String, List<List<String>>> rows = new LinkedHashMap<>();
    for (String table : minuteTables) {
      String select =
          Rollups.finalized(
              migrator, p, table, p.table(table), "toDate(minute) = '" + OTHER_DAY + "'");
      rows.put(table, migrator.rows("SELECT * FROM (" + select + ") ORDER BY ALL"));
      assertThat(rows.get(table)).as(table).isNotEmpty();
    }
    return rows;
  }

  private static long rawCount(ClickHouseHttp migrator, SchemaParameters p, String where) {
    return migrator.scalarLong("SELECT count() FROM " + p.table("xlog") + " WHERE " + where);
  }

  private static long appCount(
      ClickHouseHttp migrator, SchemaParameters p, String table, String where) {
    return migrator.scalarLong("SELECT sum(count) FROM " + p.table(table) + " WHERE " + where);
  }
}
