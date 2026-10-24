/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.argos.storage.generator.report.Manifest;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.schema.SchemaParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads the CI dataset of the consistency tests: by default with the PoC generator
 * (test/storage/generator, 13 §15 distributions, writer path with tokens, late batches, re-sent
 * blocks, clock skew), at CI size (2 days at 1 TPS); with {@code -Dargos.queries.dataset=fixture}
 * with the runner's own deterministic {@link CiDataset}.
 *
 * <p>Right after the load, before any test or procedure adds rows, the server's counts are taken as
 * a {@link Snapshot} (as argos_migrator); the tests compare that snapshot exactly with the ground
 * truth of the source (the generator manifest, or the fixture summary).
 */
final class Datasets {

  /** The five raw tables. */
  static final List<String> RAW =
      List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");

  private Datasets() {}

  /**
   * What was loaded.
   *
   * @param source generator or fixture
   * @param description one line for the report
   * @param xlogRows distinct xlog rows sent (ground truth)
   * @param lateRows xlog rows that arrived late (ground truth)
   * @param resentBlocks blocks sent again with the same token
   * @param start first instant
   * @param end end of the range
   * @param skewedInstances instances with a clock 60 s ahead
   * @param elapsed generation and load time
   * @param groundTruth distinct rows sent per raw table
   * @param manifest the generator manifest (per-day expected values), null for the fixture
   * @param snapshot the server's counts right after the load
   */
  record Loaded(
      String source,
      String description,
      long xlogRows,
      long lateRows,
      long resentBlocks,
      Instant start,
      Instant end,
      int skewedInstances,
      Duration elapsed,
      Map<String, Long> groundTruth,
      Manifest manifest,
      Snapshot snapshot) {

    /** The range covers a UTC midnight (blocks across two partitions). */
    boolean crossesMidnight() {
      return start
          .truncatedTo(ChronoUnit.DAYS)
          .isBefore(end.minusMillis(1).truncatedTo(ChronoUnit.DAYS));
    }

    Loaded withSnapshot(Snapshot value) {
      return new Loaded(
          source,
          description,
          xlogRows,
          lateRows,
          resentBlocks,
          start,
          end,
          skewedInstances,
          elapsed,
          groundTruth,
          manifest,
          value);
    }
  }

  /**
   * The server's counts right after the load (argos_migrator), before any procedure adds rows.
   *
   * @param rows count() per raw table
   * @param xlogLateRows xlog rows with {@code late = 1} (the manifest's definition)
   * @param appStat1mCount sum(app_stat_1m.count)
   * @param xlogSqlByDay sum(xlog.sql_count) per toDate(ts)
   * @param xlogExtByDay sum(xlog.ext_count) per toDate(ts)
   * @param statSqlByDay sum(sql_stat_1m.count) per toDate(minute)
   * @param statExtByDay sum(ext_stat_1m.count) per toDate(minute)
   */
  record Snapshot(
      Map<String, Long> rows,
      long xlogLateRows,
      long appStat1mCount,
      Map<LocalDate, Long> xlogSqlByDay,
      Map<LocalDate, Long> xlogExtByDay,
      Map<LocalDate, Long> statSqlByDay,
      Map<LocalDate, Long> statExtByDay) {}

  /** The source chosen by {@code argos.queries.dataset} (default generator). */
  static String source() {
    return System.getProperty("argos.queries.dataset", "generator");
  }

  /** Loads the dataset through the writer path and takes the snapshot as argos_migrator. */
  static Loaded load(
      ClickHouseHttp writer, ClickHouseHttp migrator, SchemaParameters schema, Path logDir) {
    Loaded loaded = load(writer, schema, logDir);
    return loaded.withSnapshot(snapshot(migrator, schema));
  }

  /** The counts of the snapshot. */
  static Snapshot snapshot(ClickHouseHttp migrator, SchemaParameters schema) {
    Map<String, Long> rows = new LinkedHashMap<>();
    for (String t : RAW) {
      rows.put(t, migrator.scalarLong("SELECT count() FROM " + schema.table(t)));
    }
    long late =
        migrator.scalarLong("SELECT count() FROM " + schema.table("xlog") + " WHERE late = 1");
    long appStat = migrator.scalarLong("SELECT sum(count) FROM " + schema.table("app_stat_1m"));
    Map<LocalDate, Long> xlogSql = new TreeMap<>();
    Map<LocalDate, Long> xlogExt = new TreeMap<>();
    for (List<String> r :
        migrator.rows(
            "SELECT toDate(ts) AS d, sum(sql_count), sum(ext_count) FROM "
                + schema.table("xlog")
                + " GROUP BY d ORDER BY d")) {
      xlogSql.put(LocalDate.parse(r.get(0)), Long.parseLong(r.get(1)));
      xlogExt.put(LocalDate.parse(r.get(0)), Long.parseLong(r.get(2)));
    }
    return new Snapshot(
        rows,
        late,
        appStat,
        xlogSql,
        xlogExt,
        perDay(migrator, schema.table("sql_stat_1m")),
        perDay(migrator, schema.table("ext_stat_1m")));
  }

  private static Map<LocalDate, Long> perDay(ClickHouseHttp migrator, String table) {
    Map<LocalDate, Long> out = new TreeMap<>();
    for (List<String> r :
        migrator.rows(
            "SELECT toDate(minute) AS d, sum(count) FROM " + table + " GROUP BY d ORDER BY d")) {
      out.put(LocalDate.parse(r.get(0)), Long.parseLong(r.get(1)));
    }
    return out;
  }

  private static Loaded load(ClickHouseHttp writer, SchemaParameters schema, Path logDir) {
    Instant now = Instant.now();
    if (source().equals("fixture")) {
      CiDataset.Summary s = CiDataset.generate(CiDataset.Config.ci(now)).load(writer, schema);
      return new Loaded(
          "fixture",
          "CiDataset " + s,
          s.rows().get("xlog"),
          s.lateRows(),
          s.resentBlocks(),
          s.start(),
          s.end(),
          s.skewedInstances().size(),
          s.duration(),
          Map.copyOf(s.rows()),
          null,
          null);
    }
    long started = System.nanoTime();
    Instant start = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(2));
    String profile = System.getProperty("argos.queries.profile", "");
    GeneratorConfig config =
        profile.isEmpty()
            ? GeneratorConfig.defaults()
                .with("seed", "42")
                .with("start", start.toString())
                .with("days", "2")
                .with("tps", "1")
                .with("profile-days", "1")
                .with("raw-days", "0.5")
                .with("metric-series", "2")
                .with("users", "5000")
                .with("block-rows", "20000")
            : GeneratorConfig.defaults().with("seed", "42").with("profile", profile);
    config = config.with("database", schema.database()).with("table-prefix", schema.tablePrefix());
    RunRange range = RunRange.bulk(config, now.toEpochMilli());
    try {
      Files.createDirectories(logDir);
      try (OutputStream file = Files.newOutputStream(logDir.resolve("generator.log"));
          PrintStream log = new PrintStream(file, true, StandardCharsets.UTF_8)) {
        Generator.Result r =
            new Generator(
                    config,
                    new ClickHouseInserter(writer, schema.database(), schema.tablePrefix()),
                    null,
                    log)
                .run(range, null);
        long resent = 0;
        Map<String, Long> truth = new LinkedHashMap<>();
        for (Table t : Table.ALL) {
          resent += r.loader().duplicateSends(t);
          truth.put(t.name(), r.manifest().rows(t));
        }
        return new Loaded(
            "generator",
            "test/storage/generator bulk, seed 42, "
                + (profile.isEmpty() ? "2 days at 1 TPS" : "profile " + profile)
                + ", run "
                + r.runId()
                + ", xlog "
                + r.manifest().rows(Table.XLOG)
                + " rows",
            r.manifest().rows(Table.XLOG),
            r.manifest().xlogLateRows(),
            resent,
            Instant.ofEpochMilli(range.start()),
            Instant.ofEpochMilli(range.end()),
            config.skewedInstances,
            Duration.ofNanos(System.nanoTime() - started),
            truth,
            r.manifest(),
            null);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    }
  }
}
