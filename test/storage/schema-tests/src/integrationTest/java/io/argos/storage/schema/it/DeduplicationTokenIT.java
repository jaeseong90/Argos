/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * insert_deduplication_token with non-replicated MergeTree and dependent materialized views (13 §5,
 * DSN-13-07, D-13; 13 §17 item 1, 12 open item 2). Every insert goes through the writer account
 * with the writer's settings, exactly as the batch writer will send it.
 */
@Tag("integration")
@Tag("req:D-13")
class DeduplicationTokenIT {

  private static final String[] RAW = {"xlog", "sql_stat_raw", "ext_stat_raw", "metric"};
  private static final String[] ROLLUPS = {
    "app_stat_1m",
    "app_stat_1h",
    "service_stat_1m",
    "service_stat_1h",
    "instance_stat_1m",
    "instance_stat_1h",
    "error_stat_1m",
    "sql_stat_1m",
    "sql_stat_1h",
    "ext_stat_1m",
    "ext_stat_1h",
    "metric_1m",
    "metric_1h"
  };

  private final ClickHouseHttp writer = Fixture.writer();
  private final ClickHouseHttp migrator = Fixture.migrator();

  @Test
  @DisplayName(
      "T-ST-032 Q15: the same block_id 100 times leaves raw and every 1m/1h rollup unchanged")
  void sameTokenHundredTimes() {
    SchemaParameters p = Fixture.schema("dd100_");
    Workload w = Workload.generate(1, Workload.at(LocalTime.of(9, 58)), 4, 100);
    Map<String, String> blocks = firstBlocks(w);
    blocks.forEach((table, body) -> insert(p, table, body, "b1"));
    Map<String, List<List<String>>> once = snapshot(p);

    long started = System.nanoTime();
    for (int i = 0; i < 100; i++) {
      blocks.forEach((table, body) -> insert(p, table, body, "b1"));
    }
    long perInsertMicros = (System.nanoTime() - started) / 1000 / (100L * blocks.size());

    assertThat(snapshot(p)).isEqualTo(once);
    assertThat(once.get("xlog").get(0).get(0)).isEqualTo("400");
    Evidence.record(
        "dedup", "duplicate insert round trip, 13 views (us per insert)", perInsertMicros);
  }

  @Test
  @DisplayName(
      "T-ST-032 Q15: a view that failed is filled by the retry; source and healthy views stay")
  void failedViewIsFilledByRetry() {
    SchemaParameters reference = Fixture.schema("ddref_");
    SchemaParameters broken = Fixture.schema("ddbrk_");
    Workload w = Workload.generate(2, Workload.at(LocalTime.of(9, 58)), 4, 100);
    List<String> xlogBlocks = Workload.blocks(w.xlog, 200);
    for (int i = 0; i < 2; i++) {
      insert(reference, "xlog", xlogBlocks.get(i), "b" + i);
    }
    insert(broken, "xlog", xlogBlocks.get(0), "b0");

    // Break one 1m view in the middle of the chain (its target refuses rows), as an MV failure.
    migrator.execute(
        "ALTER TABLE " + broken.table("service_stat_1m") + " ADD CONSTRAINT broken CHECK 0");
    ClickHouseException failure =
        catchInsert(() -> insert(broken, "xlog", xlogBlocks.get(1), "b1"));
    assertThat(failure.errorName()).isEqualTo("VIOLATED_CONSTRAINT");
    Map<String, List<List<String>>> partial = snapshot(broken);
    Evidence.record(
        "dedup",
        "state after the failed insert (raw committed, views before the failing one committed)",
        counts(partial));
    assertThat(partial.get("xlog").get(0).get(0)).isEqualTo("400");
    assertThat(partial.get("service_stat_1m"))
        .isNotEqualTo(snapshot(reference).get("service_stat_1m"));

    migrator.execute("ALTER TABLE " + broken.table("service_stat_1m") + " DROP CONSTRAINT broken");
    insert(broken, "xlog", xlogBlocks.get(1), "b1");
    assertThat(snapshot(broken)).isEqualTo(snapshot(reference));

    insert(broken, "xlog", xlogBlocks.get(1), "b1");
    assertThat(snapshot(broken)).isEqualTo(snapshot(reference));
  }

  @Test
  @DisplayName(
      "13 §5 1h tables need their own window: without it a retried token counts twice in 1h")
  void hourlyTablesNeedTheirOwnWindow() {
    SchemaParameters p = SchemaParameters.defaults().withTablePrefix("dd1h_");
    List<String> statements = new ArrayList<>();
    for (String s : SchemaRenderer.fromRepository().renderAll(p)) {
      boolean hourlyTable = s.startsWith("CREATE TABLE") && s.contains("_1h\n");
      statements.add(
          hourlyTable ? s.replace("non_replicated_deduplication_window = 1000,", "") : s);
    }
    SchemaApplier.apply(migrator, statements);
    Workload w = Workload.generate(3, Workload.at(LocalTime.of(9, 58)), 4, 100);
    String block = Workload.blocks(w.xlog, 1000).get(0);
    insert(p, "xlog", block, "b0");
    long minute = migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1m"));
    long hour = migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1h"));

    insert(p, "xlog", block, "b0");

    assertThat(migrator.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(400);
    assertThat(migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1m")))
        .isEqualTo(minute);
    long hourAfter = migrator.scalarLong("SELECT sum(count) FROM " + p.table("app_stat_1h"));
    Evidence.record(
        "dedup",
        "1h table without non_replicated_deduplication_window, same token twice (1m / 1h before -> 1h after)",
        minute + " / " + hour + " -> " + hourAfter);
    assertThat(hourAfter).isEqualTo(2 * hour);
  }

  @Test
  @DisplayName("T-ST-032 a block across midnight (two partitions) is deduplicated as a whole")
  void midnightBlock() {
    SchemaParameters p = Fixture.schema("ddmid_");
    Workload w = Workload.generate(4, Workload.at(LocalTime.of(23, 58)), 4, 100);
    String block = Workload.blocks(w.xlog, 1000).get(0);
    insert(p, "xlog", block, "mid");
    Map<String, List<List<String>>> once = snapshot(p);
    assertThat(
            Fixture.reader()
                .scalarLong(
                    "SELECT uniqExact(partition) FROM system.parts WHERE database = 'argos'"
                        + " AND table = '"
                        + p.tablePrefix()
                        + "xlog' AND active"))
        .isEqualTo(2);

    insert(p, "xlog", block, "mid");

    assertThat(snapshot(p)).isEqualTo(once);
  }

  @Test
  @DisplayName("T-ST-032 the token is the identity: new token = new block; no false dedup in views")
  void tokenIsTheIdentity() {
    SchemaParameters p = Fixture.schema("ddid_");
    Instant ts = Workload.at(LocalTime.of(11, 0));
    Workload.requireRetained("xlog", ts, ts);
    String row =
        "9\tprod\t91\t"
            + Workload.millis(ts)
            + "\t%d\tHTTP\t5\t901\tGET\t200\t100\t0\t0\t0\t500"
            + "\t::\toff\t\toff\t\t0\t0\t0\t0\t0\t-1\t-1\t0\t[]\n";
    String xlog = Workload.insert(p, "xlog", Workload.XLOG_COLUMNS);
    // Same data, different tokens: two blocks.
    writer.insert(xlog, String.format(row, 1), Map.of("insert_deduplication_token", "ddid:1:1-1"));
    writer.insert(xlog, String.format(row, 1), Map.of("insert_deduplication_token", "ddid:1:2-2"));
    assertThat(migrator.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(2);
    // Different transactions whose rollup rows are identical, with and without tokens: no view
    // block may be mistaken for a duplicate.
    writer.insert(xlog, String.format(row, 2), Map.of("insert_deduplication_token", "ddid:1:3-3"));
    writer.insert(xlog, String.format(row, 3), Map.of());
    writer.insert(xlog, String.format(row, 4), Map.of());
    assertThat(migrator.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(5);
    for (String table :
        List.of("app_stat_1m", "app_stat_1h", "service_stat_1m", "instance_stat_1m")) {
      assertThat(migrator.scalarLong("SELECT sum(count) FROM " + p.table(table)))
          .as(table)
          .isEqualTo(5);
    }
    // Without a token the block content is the identity (non_replicated_deduplication_window).
    writer.insert(xlog, String.format(row, 4), Map.of());
    assertThat(migrator.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(5);
  }

  @Test
  @DisplayName("T-ST-032 the window remembers the last N blocks only (recovery window of 13 §5.1)")
  void windowForgetsOldTokens() {
    SchemaParameters p = Fixture.schema("ddwin_");
    // The writer inserts into the raw tables only (C-60): the window is shrunk on this prefix's
    // metric table, which no view of the test reads back.
    String table = p.table("metric");
    migrator.execute(
        "ALTER TABLE " + table + " MODIFY SETTING non_replicated_deduplication_window = 3");
    // A current sample time: rows older than the raw retention would be dropped at insert time.
    String ts = "\t" + Workload.seconds(Instant.now()) + "\n";
    String insert = "INSERT INTO " + table + " (app_id, ts) FORMAT TabSeparated";
    for (int i = 1; i <= 4; i++) {
      writer.insert(
          insert, i + ts, Map.of("insert_deduplication_token", Workload.token("ddwin", i, i)));
    }
    writer.insert(
        insert, 4 + ts, Map.of("insert_deduplication_token", Workload.token("ddwin", 4, 4)));
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isEqualTo(4);
    writer.insert(
        insert, 1 + ts, Map.of("insert_deduplication_token", Workload.token("ddwin", 1, 1)));
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isEqualTo(5);
  }

  private Map<String, String> firstBlocks(Workload w) {
    Map<String, String> blocks = new LinkedHashMap<>();
    blocks.put("xlog", Workload.blocks(w.xlog, 100_000).get(0));
    blocks.put("sql_stat_raw", Workload.blocks(w.sqlStat, 100_000).get(0));
    blocks.put("ext_stat_raw", Workload.blocks(w.extStat, 100_000).get(0));
    blocks.put("metric", Workload.blocks(w.metric, 100_000).get(0));
    return blocks;
  }

  private void insert(SchemaParameters p, String table, String body, String blockId) {
    String columns =
        switch (table) {
          case "xlog" -> Workload.XLOG_COLUMNS;
          case "sql_stat_raw" -> Workload.SQL_STAT_COLUMNS;
          case "ext_stat_raw" -> Workload.EXT_STAT_COLUMNS;
          case "metric" -> Workload.METRIC_COLUMNS;
          default -> throw new IllegalArgumentException(table);
        };
    // C-61 token shape: <wal_instance_id>:<epoch>:<first>-<last>; the block id names the block, the
    // table base keeps the positions of different tables apart.
    long first = Workload.tableBase(table) + (blockId.hashCode() & 0xFFFFFL) * 1_000_000L;
    writer.insert(
        Workload.insert(p, table, columns),
        body,
        Map.of(
            "insert_deduplication_token",
            Workload.token("dd-" + blockId, first, first + body.lines().count() - 1),
            "deduplicate_blocks_in_dependent_materialized_views",
            "1"));
  }

  private static ClickHouseException catchInsert(Runnable insert) {
    try {
      insert.run();
    } catch (ClickHouseException e) {
      return e;
    }
    throw new AssertionError("insert should have failed");
  }

  /** Per table: row count and the sum of every numeric measure, finalized (state-free). */
  private Map<String, List<List<String>>> snapshot(SchemaParameters p) {
    Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
    for (String table : RAW) {
      snapshot.put(table, migrator.rows("SELECT count() FROM " + p.table(table)));
    }
    for (String table : ROLLUPS) {
      String measures;
      if (table.startsWith("metric")) {
        measures = "sum(sample_count), sum(avg_sum), sum(increase)";
      } else if (table.startsWith("sql_stat") || table.startsWith("ext_stat")) {
        measures = "sum(count), sum(sum_ms), arraySum(sumForEachMerge(buckets))";
      } else if (table.startsWith("error_stat")) {
        measures = "sum(count), min(first_ts), max(last_ts)";
      } else {
        measures = "sum(count), sum(error_count), sum(sum_elapsed_ms), max(max_elapsed_ms)";
      }
      snapshot.put(table, migrator.rows("SELECT " + measures + " FROM " + p.table(table)));
    }
    return snapshot;
  }

  private static String counts(Map<String, List<List<String>>> snapshot) {
    StringBuilder sb = new StringBuilder();
    snapshot.forEach(
        (table, rows) -> sb.append(table).append('=').append(rows.get(0).get(0)).append(' '));
    return sb.toString().strip();
  }
}
