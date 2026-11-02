/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Inserts through the writer account fill every rollup (13 §4): 1m views on the raw tables, 1h
 * views cascading from the 1m tables, buckets with the sumForEach fallback, metric rollups. Every
 * measure is compared with a direct aggregation of the raw rows (D-03, D-12 at small scale), late
 * rows update their minute (Q16 at small scale) and the rebuild SELECT of every rollup file into a
 * staging table reproduces its view per key for one day (1m) or month (1h), the quantilesDD states
 * of q_elapsed byte for byte (ADR-0007 C-53), apart from the read-order dependent anyLast column
 * (D-14). The rebuild procedure itself (C-59: REPLACE PARTITION, 1h cascade) is RollupRebuildIT.
 * The consistency SQL uses the C-65 form (UNION ALL, GROUP BY the key, sumIf, HAVING).
 */
@Tag("integration")
@Tag("req:D-03")
@Tag("req:D-12")
@Tag("req:D-14")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollupViewsIT {

  /** 30 minutes across midnight of the anchor day: two daily partitions, two hours. */
  private static final Instant START = Workload.at(LocalTime.of(23, 40));

  private static SchemaParameters p;
  private static ClickHouseHttp migrator;

  @BeforeAll
  static void load() {
    p = Fixture.schema("mv_");
    migrator = Fixture.migrator();
    Workload w = Workload.generate(42, START, 30, 120);
    insertAll(Fixture.writer(), p, w, "main");
  }

  static void insertAll(ClickHouseHttp writer, SchemaParameters params, Workload w, String tag) {
    insertBlocks(writer, params, "xlog", Workload.XLOG_COLUMNS, w.xlog, 1000, tag);
    insertBlocks(writer, params, "sql_stat_raw", Workload.SQL_STAT_COLUMNS, w.sqlStat, 2000, tag);
    insertBlocks(writer, params, "ext_stat_raw", Workload.EXT_STAT_COLUMNS, w.extStat, 2000, tag);
    insertBlocks(writer, params, "metric", Workload.METRIC_COLUMNS, w.metric, 2000, tag);
  }

  static void insertBlocks(
      ClickHouseHttp writer,
      SchemaParameters params,
      String table,
      String columns,
      List<String> rows,
      int size,
      String tag) {
    List<String> blocks = Workload.blocks(rows, size);
    for (int i = 0; i < blocks.size(); i++) {
      long first = Workload.tableBase(table) + (long) i * size;
      long last = first + Math.min(size, rows.size() - (long) i * size) - 1;
      writer.insert(
          Workload.insert(params, table, columns),
          blocks.get(i),
          Map.of(
              "insert_deduplication_token",
              Workload.token(tag, first, last),
              "deduplicate_blocks_in_dependent_materialized_views",
              "1"));
    }
  }

  @Test
  @DisplayName(
      "T-ST-030 Q12 per minute: count(xlog) = app_stat_1m = service_stat_1m = instance_stat_1m")
  void minuteCountsAgree() {
    assertThat(q12Mismatches()).isZero();
    assertThat(migrator.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(3600);
    // The window crosses midnight: both daily partitions and two hours are populated.
    assertThat(migrator.scalarLong("SELECT uniqExact(toDate(ts)) FROM " + p.table("xlog")))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("T-ST-030 every app/service/instance 1m measure equals a direct aggregation of xlog")
  void transactionRollupsMatchRaw() {
    String measures =
        "count() AS c, countIf(error = 1) AS e, sum(elapsed_ms) AS se, max(elapsed_ms) AS mx,"
            + " sum(sql_time_ms) AS sq, sum(dbc_wait_ms) AS db, sum(ext_time_ms) AS ex,"
            + " countIf(elapsed_ms <= apdex_t_ms) AS sat,"
            + " countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) AS tol";
    String rolled =
        "sum(count) AS c, sum(error_count) AS e, sum(sum_elapsed_ms) AS se, max(max_elapsed_ms) AS mx,"
            + " sum(sum_sql_time_ms) AS sq, sum(sum_dbc_wait_ms) AS db, sum(sum_ext_time_ms) AS ex,"
            + " sum(apdex_satisfied) AS sat, sum(apdex_tolerating) AS tol";
    Map<String, String> keys =
        Map.of(
            "app_stat_1m", "app_id, env, kind",
            "service_stat_1m", "app_id, env, kind, service_id, app_version_id",
            "instance_stat_1m", "app_id, env, instance_id, kind");
    keys.forEach(
        (table, key) -> {
          String raw =
              "SELECT "
                  + key
                  + ", toStartOfMinute(ts) AS minute, "
                  + measures
                  + " FROM "
                  + p.table("xlog")
                  + " GROUP BY "
                  + key
                  + ", minute";
          String rollup =
              "SELECT "
                  + key
                  + ", minute, "
                  + rolled
                  + " FROM "
                  + p.table(table)
                  + " GROUP BY "
                  + key
                  + ", minute";
          assertThat(differences(raw, rollup)).as(table).isZero();
        });
  }

  @Test
  @DisplayName("T-ST-030 1h rollups equal their 1m source per hour (cascade views)")
  void hourlyCascadeMatches() {
    Map<String, String> pairs =
        Map.of(
            "app_stat", "app_id, env, kind",
            "service_stat", "app_id, env, kind, service_id, app_version_id");
    pairs.forEach(
        (base, key) -> {
          String m =
              "sum(count) AS c, sum(error_count) AS e, sum(sum_elapsed_ms) AS se,"
                  + " max(max_elapsed_ms) AS mx, sum(apdex_satisfied) AS sat";
          String minute =
              "SELECT "
                  + key
                  + ", toStartOfHour(minute) AS hour, "
                  + m
                  + " FROM "
                  + p.table(base + "_1m")
                  + " GROUP BY "
                  + key
                  + ", hour";
          String hour =
              "SELECT "
                  + key
                  + ", hour, "
                  + m
                  + " FROM "
                  + p.table(base + "_1h")
                  + " GROUP BY "
                  + key
                  + ", hour";
          assertThat(differences(minute, hour)).as(base).isZero();
        });
    assertThat(migrator.scalarLong("SELECT uniqExact(hour) FROM " + p.table("app_stat_1h")))
        .isEqualTo(2);
    List<String> quantiles =
        migrator
            .rows(
                "SELECT arrayJoin(quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed)) FROM "
                    + p.table("app_stat_1h"))
            .stream()
            .map(r -> r.get(0))
            .toList();
    List<String> exact =
        migrator
            .rows(
                "SELECT arrayJoin(quantilesExact(0.5, 0.95, 0.99)(elapsed_ms)) FROM "
                    + p.table("xlog"))
            .stream()
            .map(r -> r.get(0))
            .toList();
    Evidence.record("rollups", "p50/p95/p99 quantilesDD via 1m->1h states (3,600 tx)", quantiles);
    Evidence.record("rollups", "p50/p95/p99 exact (3,600 tx)", exact);
    assertThat(quantiles).hasSize(3);
    assertThat(Double.parseDouble(quantiles.get(0)))
        .isLessThanOrEqualTo(Double.parseDouble(quantiles.get(1)));
    // C-53: merging DD states only adds bucket counts, so the 1h state (1m states cascaded) is the
    // DD state of the raw values of the hour, byte for byte, for every app and kind.
    for (String level : List.of("hour", "minute")) {
      String table = level.equals("hour") ? "app_stat_1h" : "app_stat_1m";
      String bucket = level.equals("hour") ? "toStartOfHour(ts)" : "toStartOfMinute(ts)";
      String raw =
          "SELECT app_id, kind, "
              + bucket
              + " AS t, hex(quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)) AS s FROM "
              + p.table("xlog")
              + " GROUP BY app_id, kind, t";
      String rolled =
          "SELECT app_id, kind, "
              + level
              + " AS t, hex(quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed)) AS s FROM "
              + p.table(table)
              + " GROUP BY app_id, kind, t";
      assertThat(migrator.scalarLong("SELECT count() FROM (" + raw + ")")).as(level).isPositive();
      assertThat(differences(raw, rolled)).as(level + " DD state = raw DD state").isZero();
    }
  }

  @Test
  @DisplayName("T-ST-030 error_stat_1m counts and first/last ts per group and minute")
  void errorRollup() {
    String raw =
        "SELECT app_id, error_group_id, service_id, app_version_id, toStartOfMinute(ts) AS minute,"
            + " count() AS c, min(ts) AS f, max(ts) AS l FROM "
            + p.table("xlog")
            + " WHERE error = 1 AND error_group_id != 0 GROUP BY app_id, error_group_id, service_id,"
            + " app_version_id, minute";
    String rollup =
        "SELECT app_id, error_group_id, service_id, app_version_id, minute, sum(count) AS c,"
            + " min(first_ts) AS f, max(last_ts) AS l FROM "
            + p.table("error_stat_1m")
            + " GROUP BY app_id, error_group_id, service_id, app_version_id, minute";
    assertThat(differences(raw, rollup)).isZero();
    assertThat(migrator.scalarLong("SELECT sum(count) FROM " + p.table("error_stat_1m")))
        .isPositive();
  }

  @Test
  @DisplayName(
      "T-ST-031 Q13: sum(xlog.sql_count) = sum(sql_stat_1m.count), same for ext; buckets via sumForEach")
  void statisticsRollups() {
    assertThat(
            migrator.scalarLong(
                "SELECT (SELECT sum(sql_count) FROM "
                    + p.table("xlog")
                    + ") - (SELECT sum(count) FROM "
                    + p.table("sql_stat_1m")
                    + ")"))
        .isZero();
    assertThat(
            migrator.scalarLong(
                "SELECT (SELECT sum(ext_count) FROM "
                    + p.table("xlog")
                    + ") - (SELECT sum(count) FROM "
                    + p.table("ext_stat_1m")
                    + ")"))
        .isZero();
    assertThat(migrator.scalarLong("SELECT sum(count) FROM " + p.table("sql_stat_1m")))
        .isPositive();

    String rawSql =
        "SELECT app_id, kind, sql_id, db_id, toStartOfMinute(ts) AS minute, sum(count) AS c,"
            + " sum(error_count) AS e, sum(sum_ms) AS s, sum(sum_exec_ms) AS x, sum(sum_fetch_ms) AS f,"
            + " max(max_ms) AS m, sum(fetch_rows) AS r,"
            + " sumForEach(arrayMap(v -> toUInt64(v), buckets)) AS b FROM "
            + p.table("sql_stat_raw")
            + " GROUP BY app_id, kind, sql_id, db_id, minute";
    String sql1m =
        "SELECT app_id, kind, sql_id, db_id, minute, sum(count) AS c, sum(error_count) AS e,"
            + " sum(sum_ms) AS s, sum(sum_exec_ms) AS x, sum(sum_fetch_ms) AS f, max(max_ms) AS m,"
            + " sum(fetch_rows) AS r, sumForEachMerge(buckets) AS b FROM "
            + p.table("sql_stat_1m")
            + " GROUP BY app_id, kind, sql_id, db_id, minute";
    assertThat(differences(rawSql, sql1m)).isZero();

    String sql1mHourly =
        "SELECT app_id, kind, sql_id, db_id, toStartOfHour(minute) AS hour, sum(count) AS c,"
            + " sumForEachMerge(buckets) AS b FROM "
            + p.table("sql_stat_1m")
            + " GROUP BY app_id, kind, sql_id, db_id, hour";
    String sql1h =
        "SELECT app_id, kind, sql_id, db_id, hour, sum(count) AS c, sumForEachMerge(buckets) AS b"
            + " FROM "
            + p.table("sql_stat_1h")
            + " GROUP BY app_id, kind, sql_id, db_id, hour";
    assertThat(differences(sql1mHourly, sql1h)).isZero();

    // Per minute, so a wrong time bucket in the 1m view (e.g. 5-minute buckets) is caught.
    String rawExtMinute =
        "SELECT app_id, kind, host_id, toStartOfMinute(ts) AS minute, sum(count) AS c,"
            + " sum(error_count) AS e, sum(timeout_count) AS t, sum(connect_fail_count) AS cf,"
            + " sum(sum_ms) AS s, max(max_ms) AS m,"
            + " sumForEach(arrayMap(v -> toUInt64(v), buckets)) AS b FROM "
            + p.table("ext_stat_raw")
            + " GROUP BY app_id, kind, host_id, minute";
    String ext1m =
        "SELECT app_id, kind, host_id, minute, sum(count) AS c, sum(error_count) AS e,"
            + " sum(timeout_count) AS t, sum(connect_fail_count) AS cf, sum(sum_ms) AS s,"
            + " max(max_ms) AS m, sumForEachMerge(buckets) AS b FROM "
            + p.table("ext_stat_1m")
            + " GROUP BY app_id, kind, host_id, minute";
    assertThat(differences(rawExtMinute, ext1m)).isZero();
    assertThat(migrator.scalarLong("SELECT uniqExact(minute) FROM " + p.table("ext_stat_1m")))
        .isEqualTo(30);

    String rawExt =
        "SELECT app_id, kind, host_id, toStartOfHour(ts) AS hour, sum(count) AS c,"
            + " sum(timeout_count) AS t, sum(connect_fail_count) AS cf, max(max_ms) AS m,"
            + " sumForEach(arrayMap(v -> toUInt64(v), buckets)) AS b FROM "
            + p.table("ext_stat_raw")
            + " GROUP BY app_id, kind, host_id, hour";
    String ext1h =
        "SELECT app_id, kind, host_id, hour, sum(count) AS c, sum(timeout_count) AS t,"
            + " sum(connect_fail_count) AS cf, max(max_ms) AS m, sumForEachMerge(buckets) AS b FROM "
            + p.table("ext_stat_1h")
            + " GROUP BY app_id, kind, host_id, hour";
    assertThat(differences(rawExt, ext1h)).isZero();
  }

  @Test
  @DisplayName("T-ST-030 metric_1m/1h: sample count, avg sum, min, max, counter increase")
  void metricRollups() {
    String raw =
        "SELECT app_id, instance_id, name, kind, toStartOfMinute(ts) AS minute, count() AS n,"
            + " sum(value) AS s, min(value) AS lo, max(value) AS hi, sum(delta) AS inc"
            + " FROM "
            + p.table("metric")
            + " GROUP BY app_id, instance_id, name, kind, minute";
    String rolled =
        "SELECT app_id, instance_id, name, kind, minute, sum(sample_count) AS n, sum(avg_sum) AS s,"
            + " min(min_v) AS lo, max(max_v) AS hi, sum(increase) AS inc"
            + " FROM "
            + p.table("metric_1m")
            + " GROUP BY app_id, instance_id, name, kind, minute";
    assertThat(differences(raw, rolled)).isZero();
    String hourlyRaw =
        "SELECT app_id, instance_id, name, toStartOfHour(ts) AS hour, count() AS n,"
            + " min(value) AS lo, max(value) AS hi, sum(delta) AS inc FROM "
            + p.table("metric")
            + " GROUP BY app_id, instance_id, name, hour";
    String hourly =
        "SELECT app_id, instance_id, name, hour, sum(sample_count) AS n, min(min_v) AS lo,"
            + " max(max_v) AS hi, sum(increase) AS inc FROM "
            + p.table("metric_1h")
            + " GROUP BY app_id, instance_id, name, hour";
    assertThat(differences(hourlyRaw, hourly)).isZero();
  }

  @Test
  @Order(Integer.MAX_VALUE)
  @DisplayName("T-ST-033 Q16: rows arriving an hour late update their minute and hour, Q12 stays 0")
  void lateRowsUpdateTheirMinute() {
    Instant lateMinute = Workload.at(LocalTime.of(23, 41));
    String minute = " WHERE minute = '" + Workload.seconds(lateMinute) + "'";
    long before = migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1m") + minute);
    Workload late = Workload.generate(4242, lateMinute, 1, 200);
    insertBlocks(Fixture.writer(), p, "xlog", Workload.XLOG_COLUMNS, late.xlog, 1000, "late");

    assertThat(migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1m") + minute))
        .isEqualTo(before + 200);
    assertThat(q12Mismatches()).isZero();
  }

  @Test
  @DisplayName(
      "T-ST-030 D-14 rebuild: every rollup file SELECT into a staging table equals its view per key")
  void rebuildEqualsView() {
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    assertThat(renderer.rollupTables()).hasSize(12);
    for (String table : renderer.rollupTables()) {
      // 1m: one day from the raw source; 1h: one month from the 1m source (1h partitions are
      // monthly). Every key (app_id, env, kind, dimensions, minute/hour) is compared; the
      // SimpleAggregateFunction and sumForEach columns must match exactly, the quantilesDD
      // q_elapsed states byte for byte and by value (C-53). anyLast last_v is left out: its value
      // depends on the read order (Rollups).
      String staging = Rollups.fillStaging(migrator, renderer, p, table);
      String fromView =
          Rollups.finalized(migrator, p, table, p.table(table), Rollups.rollupPredicate(table));
      String fromRebuild = Rollups.finalized(migrator, p, table, staging, "");
      long keys = Rollups.keyCount(migrator, fromView);
      Evidence.record("rollups", "rebuild = view, keys compared: " + table, keys);
      assertThat(keys).as(table).isPositive();
      assertThat(Rollups.keyCount(migrator, fromRebuild)).as(table).isEqualTo(keys);
      assertThat(Rollups.differences(migrator, fromView, fromRebuild)).as(table).isZero();
      migrator.execute("DROP TABLE " + staging + " SYNC");
    }
  }

  /**
   * Q12 per minute in the C-65 form (ADR-0007): the four sources with a src label in UNION ALL,
   * grouped by minute, compared with sumIf in HAVING; a minute missing in one source counts 0
   * there, whatever join_use_nulls is (the query also runs with join_use_nulls = 1 to show it).
   */
  static long q12Mismatches() {
    String sql =
        "SELECT count() FROM (SELECT m, sumIf(c, src = 'xlog') AS x, sumIf(c, src = 'app') AS a,"
            + " sumIf(c, src = 'service') AS s, sumIf(c, src = 'instance') AS i FROM ("
            + "SELECT 'xlog' AS src, toStartOfMinute(ts) AS m, count() AS c FROM "
            + p.table("xlog")
            + " GROUP BY m UNION ALL SELECT 'app', minute, sum(count) FROM "
            + p.table("app_stat_1m")
            + " GROUP BY minute UNION ALL SELECT 'service', minute, sum(count) FROM "
            + p.table("service_stat_1m")
            + " GROUP BY minute UNION ALL SELECT 'instance', minute, sum(count) FROM "
            + p.table("instance_stat_1m")
            + " GROUP BY minute) GROUP BY m HAVING x != a OR x != s OR x != i)";
    long mismatches = migrator.scalarLong(sql);
    assertThat(migrator.execute(sql, Map.of("join_use_nulls", "1")).body().strip())
        .isEqualTo(Long.toString(mismatches));
    return mismatches;
  }

  /** Rows that appear in only one of the two result sets (symmetric difference size). */
  private static long differences(String left, String right) {
    return migrator.scalarLong(
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
}
