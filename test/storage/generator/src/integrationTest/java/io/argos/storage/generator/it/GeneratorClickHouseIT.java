/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.GeneratorMain;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.argos.storage.generator.report.LatencyHistogram;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.schema.SchemaApplier;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loads generated data into a real ClickHouse (bundled image, deploy/clickhouse configuration,
 * Testcontainers) through the argos_writer account and checks the result against the generator's
 * manifest: raw counts, materialized view consistency per minute and per day (Q12 / Q13 shape),
 * re-sent blocks and a repeated identical load deduplicated by their tokens, and the replay path
 * (WAL block ids, 2 s flush, writer restarts, ingest lag).
 */
class GeneratorClickHouseIT {

  private static ArgosClickHouse server;
  private static ClickHouseHttp reader;
  private static Path reports;

  @BeforeAll
  static void start() throws IOException {
    server =
        ArgosClickHouse.builder()
            .memoryLimit(2 * MemoryBudget.GIB)
            .writerTablePrefixes("gb_", "gr_", "gt_")
            .start();
    reader = server.client(Account.READER);
    reports =
        Path.of(System.getProperty("argos.reportDir", "build/reports/storage-generator-tests"));
    Files.createDirectories(reports);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  private static GeneratorConfig apply(GeneratorConfig config) {
    SchemaApplier.apply(server.client(Account.MIGRATOR), GeneratorMain.schema(config));
    return config;
  }

  private static Generator.Result run(GeneratorConfig config, RunRange range) throws Exception {
    ClickHouseInserter inserter =
        new ClickHouseInserter(server.client(Account.WRITER), config.database, config.tablePrefix);
    PrintStream log = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    return new Generator(config, inserter, reader, log).run(range, reports);
  }

  private static long count(String sql) {
    return reader.scalarLong(sql);
  }

  /** 30 minutes around the last midnight at 100 TPS: every table, midnight, late rows. */
  private static GeneratorConfig bulkConfig() {
    return GeneratorConfig.defaults()
        .with("table-prefix", "gb_")
        .with("tps", "100")
        .with("scale", "0.000694444444") // 30 minutes of the 30-day shape
        .with("block-rows", "20000")
        .with("duplicate-rate", "0.05")
        .with("monitor-interval-s", "2");
  }

  @Test
  @DisplayName(
      "Bulk load: counts = manifest, rollups consistent per minute and per day, re-load deduplicated")
  void bulkLoad() throws Exception {
    GeneratorConfig config = apply(bulkConfig());
    long span = config.xlogSpanMs();
    long start = RunRange.defaultStart(System.currentTimeMillis(), span);
    RunRange range = RunRange.of(config, start, start + span);
    Generator.Result result = run(config, range);
    String p = "argos.gb_";

    // Raw counts equal the generator counters.
    for (Table table : Table.ALL) {
      assertThat(count("SELECT count() FROM " + p + table.name()))
          .as(table.name())
          .isEqualTo(result.manifest().rows(table))
          .isPositive();
    }
    // Blocks were re-sent with their token (the counts above and the rollup checks below show
    // that the server dropped them; the written_rows header still counts them, so it is no signal).
    long resent = 0;
    for (Table table : Table.ALL) {
      resent += result.loader().duplicateSends(table);
    }
    assertThat(resent).isGreaterThanOrEqualTo(Table.ALL.size());

    // D-03 (Q12 shape, ADR-0007 C-65 form): per minute, xlog = app_stat_1m = service_stat_1m =
    // instance_stat_1m; the sources in UNION ALL with a src label, grouped by minute, compared with
    // sumIf in HAVING, so a minute missing in one source counts 0 there whatever join_use_nulls is.
    String perMinute =
        "SELECT count() FROM (SELECT m, sumIf(c, src = 'xlog') AS xc, sumIf(c, src = 'app') AS ac,"
            + " sumIf(c, src = 'service') AS sc, sumIf(c, src = 'instance') AS ic FROM ("
            + "SELECT 'xlog' AS src, toStartOfMinute(ts) AS m, count() AS c FROM "
            + p
            + "xlog GROUP BY m UNION ALL SELECT 'app', minute, sum(count) FROM "
            + p
            + "app_stat_1m GROUP BY minute UNION ALL SELECT 'service', minute, sum(count) FROM "
            + p
            + "service_stat_1m GROUP BY minute UNION ALL SELECT 'instance', minute, sum(count) FROM "
            + p
            + "instance_stat_1m GROUP BY minute) GROUP BY m HAVING xc != ac OR xc != sc OR xc != ic)";
    assertThat(count(perMinute)).as("minutes with a mismatch").isZero();
    assertThat(
            server
                .client(Account.MIGRATOR)
                .execute(perMinute, java.util.Map.of("join_use_nulls", "1"))
                .body()
                .strip())
        .as("minutes with a mismatch, join_use_nulls = 1")
        .isEqualTo("0");
    // The check sees a one-sided minute: a rollup row without xlog rows is one mismatch.
    assertThat(
            count(
                perMinute.replace(
                    "GROUP BY minute) GROUP BY m",
                    "GROUP BY minute UNION ALL SELECT 'instance', toDateTime('2000-01-01 00:00:00'),"
                        + " 1) GROUP BY m")))
        .as("negative control: a minute found only in instance_stat_1m")
        .isEqualTo(1);
    assertThat(count("SELECT sum(count) FROM " + p + "app_stat_1h"))
        .isEqualTo(result.manifest().rows(Table.XLOG));

    // D-12 (Q13 shape): totals equal; per day equal to the manifest by xlog day / window day.
    assertThat(count("SELECT sum(sql_count) FROM " + p + "xlog"))
        .isEqualTo(count("SELECT sum(count) FROM " + p + "sql_stat_1m"))
        .isEqualTo(result.manifest().xlogSqlCount());
    assertThat(count("SELECT sum(ext_count) FROM " + p + "xlog"))
        .isEqualTo(count("SELECT sum(count) FROM " + p + "ext_stat_1m"))
        .isEqualTo(result.manifest().xlogExtCount());
    assertThat(perDay("SELECT toDate(ts), sum(sql_count) FROM " + p + "xlog GROUP BY 1"))
        .isEqualTo(result.manifest().xlogSqlCountByDay());
    assertThat(perDay("SELECT toDate(minute), sum(count) FROM " + p + "sql_stat_1m GROUP BY 1"))
        .isEqualTo(result.manifest().statSqlCountByDay());
    assertThat(result.manifest().xlogRowsByDay()).hasSize(2); // midnight inside the range
    assertThat(count("SELECT count() FROM " + p + "xlog WHERE late = 1"))
        .isEqualTo(result.manifest().xlogLateRows())
        .isPositive();
    assertThat(count("SELECT count() FROM " + p + "metric_1m")).isPositive();

    // Table sizes on both bases: column data (compression ratio, 13 §15) and disk (skip indexes,
    // marks, primary index: the 13 §14 capacity basis), read with the reader account.
    @SuppressWarnings("unchecked")
    Map<String, Object> storage =
        (Map<String, Object>)
            ((Map<String, Object>) result.document().get("clickhouse")).get("storage");
    for (Table table : Table.ALL) {
      @SuppressWarnings("unchecked")
      Map<String, Object> s = (Map<String, Object>) storage.get(table.name());
      assertThat((long) s.get("rows")).as(table.name()).isEqualTo(result.manifest().rows(table));
      assertThat((double) s.get("bytesPerRow")).as(table.name()).isPositive();
      assertThat((long) s.get("diskBytes"))
          .as(table.name())
          .isGreaterThan((long) s.get("compressedBytes"));
      assertThat((double) s.get("diskBytesPerRow"))
          .as(table.name())
          .isCloseTo((double) (long) s.get("diskBytes") / (long) s.get("rows"), within(1e-6))
          .isGreaterThan((double) s.get("bytesPerRow"));
    }

    // The same configuration and start again: identical tokens, everything deduplicated.
    Generator.Result again = run(config, range);
    for (Table table : Table.ALL) {
      assertThat(count("SELECT count() FROM " + p + table.name()))
          .as("after the repeated load: " + table.name())
          .isEqualTo(result.manifest().rows(table));
    }
    assertThat(count("SELECT sum(count) FROM " + p + "app_stat_1m"))
        .isEqualTo(result.manifest().rows(Table.XLOG));
    assertThat(again.runId()).isEqualTo(result.runId());
    record("bulk", result);
  }

  @Test
  @DisplayName(
      "Replay: WAL blocks every 2 s, writer restarts recovered, counts = manifest, lag p99 < 5 s")
  void replay() throws Exception {
    GeneratorConfig config =
        apply(
            GeneratorConfig.defaults()
                .with("table-prefix", "gr_")
                .with("mode", "replay")
                .with("tps-steps", "50,150")
                .with("step-duration", "PT12S")
                .with("writer-restarts", "2")
                .with("duplicate-rate", "0.05")
                .with("monitor-interval-s", "2"));
    RunRange range = RunRange.replay(config, System.currentTimeMillis());
    Generator.Result result = run(config, range);
    String p = "argos.gr_";
    for (Table table : Table.ALL) {
      assertThat(count("SELECT count() FROM " + p + table.name()))
          .as(table.name())
          .isEqualTo(result.manifest().rows(table))
          .isPositive();
    }
    assertThat(count("SELECT sum(count) FROM " + p + "service_stat_1m"))
        .isEqualTo(result.manifest().rows(Table.XLOG));
    assertThat(result.loader().recoverySends(Table.XLOG)).isEqualTo(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> replay = (Map<String, Object>) result.document().get("replay");
    assertThat(replay.get("writerRestartsBeforeInsert")).isEqualTo(1L);
    assertThat(replay.get("writerRestartsAfterInsert")).isEqualTo(1L);
    // 24 s of replay with a 2 s flush: about 12 xlog blocks (plus the final flush).
    assertThat(result.loader().blocks(Table.XLOG)).isBetween(8L, 20L);
    LatencyHistogram lag = result.loader().lag();
    // Rows of late batches (receive time after the end of the run) are not part of the lag.
    assertThat((double) lag.count()).isGreaterThan(0.98 * sumRows(result));
    assertThat(lag.quantile(0.99)).as("ingest lag p99 (ms)").isLessThan(5_000L);
    record("replay", result);
  }

  @Test
  @DisplayName("RowBinaryWithNamesAndTypes header types equal the server column types")
  void columnTypes() {
    GeneratorConfig config = apply(GeneratorConfig.defaults().with("table-prefix", "gt_"));
    for (Table table : Table.ALL) {
      Map<String, String> server = new LinkedHashMap<>();
      for (List<String> row : reader.rows("DESCRIBE TABLE argos.gt_" + table.name())) {
        server.put(row.get(0), row.get(1));
      }
      Map<String, String> ours = new LinkedHashMap<>();
      for (Table.Column c : table.columns()) {
        ours.put(c.name(), c.type());
      }
      assertThat(ours).as(table.name()).containsExactlyEntriesOf(server);
    }
    assertThat(config.tablePrefix).isEqualTo("gt_");
  }

  private static long sumRows(Generator.Result result) {
    long n = 0;
    for (Table table : Table.ALL) {
      n += result.loader().rows(table);
    }
    return n;
  }

  private static Map<LocalDate, Long> perDay(String sql) {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    for (List<String> row : reader.rows(sql + " ORDER BY 1")) {
      m.put(LocalDate.parse(row.get(0)), Long.parseLong(row.get(1)));
    }
    return m;
  }

  private static void record(String name, Generator.Result result) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "## %s %s (%s .. %s)%n%n",
            name,
            result.runId(),
            Instant.ofEpochMilli(result.range().start()),
            Instant.ofEpochMilli(result.range().end())));
    for (Table table : Table.ALL) {
      sb.append(
          String.format(
              Locale.ROOT,
              "- %s: %,d rows, %d blocks, %d re-sent%n",
              table.name(),
              result.manifest().rows(table),
              result.loader().blocks(table),
              result.loader().duplicateSends(table)));
    }
    sb.append(String.format(Locale.ROOT, "- report: %s%n%n", result.directory()));
    Files.writeString(
        reports.resolve("generator-it.md"),
        sb.toString(),
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }
}
