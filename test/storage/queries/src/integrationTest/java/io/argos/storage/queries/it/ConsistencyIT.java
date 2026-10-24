/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.generator.report.Manifest;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryRunner;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.Tsv;
import io.argos.storage.queries.VariantResult;
import io.argos.storage.queries.Verdict;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.queries.procedures.DedupReinsert;
import io.argos.storage.queries.procedures.LateArrival;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CI consistency tests of the storage PoC at small scale (18 §9: "CI(1/100): generation, load, Q12,
 * Q13, Q15, Q16 consistency 0"), with the SQL of test/storage/queries run against a real ClickHouse
 * loaded through the writer path (T-ST-030..033).
 */
@Tag("integration")
class ConsistencyIT {

  private static Datasets.Loaded data;
  private static BenchContext ctx;

  @BeforeAll
  static void load() {
    data = Fixture.dataset();
    ctx = Fixture.context();
  }

  @Test
  @Tag("req:D-03")
  @Tag("req:D-13")
  @DisplayName(
      "T-ST-030 the CI dataset went through the writer path: right after the load every raw table,"
          + " the late xlog rows and sum(app_stat_1m.count) equal the ground truth exactly, with"
          + " re-sent blocks, midnight and clock skew present")
  void datasetUnderTest() {
    Datasets.Snapshot snap = data.snapshot();
    assertThat(snap).as("snapshot taken right after the load").isNotNull();
    for (String t : Datasets.RAW) {
      assertThat(snap.rows().get(t))
          .as(t + " rows right after the load = ground truth (re-sent blocks dropped)")
          .isEqualTo(data.groundTruth().get(t));
      Evidence.record(
          "consistency",
          t + " rows loaded / ground truth",
          snap.rows().get(t) + " / " + data.groundTruth().get(t));
    }
    assertThat(snap.xlogLateRows()).as("xlog rows with late = 1").isEqualTo(data.lateRows());
    assertThat(snap.appStat1mCount())
        .as("sum(app_stat_1m.count) = xlog rows")
        .isEqualTo(snap.rows().get("xlog"));
    assertThat(data.lateRows()).isPositive();
    // Blocks were sent again with the same token, and the counts above are still exact (D-13).
    assertThat(data.resentBlocks()).isPositive();
    assertThat(data.crossesMidnight()).isTrue();
    assertThat(data.skewedInstances()).isPositive();
    long now =
        ctx.client(Account.MIGRATOR)
            .scalarLong("SELECT count() FROM " + Fixture.schema().table("xlog"));
    Evidence.record(
        "consistency",
        "xlog late rows / app_stat_1m sum / re-sent blocks",
        snap.xlogLateRows() + " / " + snap.appStat1mCount() + " / " + data.resentBlocks());
    // Information only: the procedures of other tests add rows to the shared dataset.
    Evidence.record("consistency", "xlog rows now (>= snapshot, information)", now);
  }

  @Test
  @Tag("req:D-03")
  @DisplayName(
      "T-ST-030 Q12 D-03: count(xlog) = app/service/instance_stat_1m per minute, 1h cascade,"
          + " error_stat_1m and the nightly form: 0 mismatches")
  void q12() {
    List<VariantResult> results = runAll("Q12");
    for (VariantResult r : results) {
      Evidence.record("consistency", r.label() + " mismatching rows", (long) r.metric("rows"));
      assertThat(r.verdict())
          .as(r.label() + " " + r.violations() + " " + firstRows(r))
          .isEqualTo(Verdict.PASS);
    }
  }

  @Test
  @Tag("req:D-12")
  @DisplayName(
      "T-ST-031 Q13 D-12: sum(xlog.sql_count / ext_count) = sum(sql/ext_stat_1m.count) over the"
          + " range, raw statistics = 1m and 1h rollups per day and = 1m per minute: 0 mismatches;"
          + " per day the loaded values equal the manifest and the daily checks report exactly the"
          + " days where the manifest's xlog and statistics differ (D-12 per-day attribution pending"
          + " PM decision (PoC 7.3))")
  void q13() {
    Datasets.Snapshot snap = data.snapshot();
    Manifest m = data.manifest();
    if (m != null) {
      // Exact per-day values, right after the load (before Q15/Q16 of any test add rows).
      assertThat(snap.xlogSqlByDay())
          .as("sum(xlog.sql_count) per day")
          .isEqualTo(m.xlogSqlCountByDay());
      assertThat(snap.statSqlByDay())
          .as("sum(sql_stat_1m.count) per day")
          .isEqualTo(m.statSqlCountByDay());
      assertThat(snap.xlogExtByDay())
          .as("sum(xlog.ext_count) per day")
          .isEqualTo(m.xlogExtCountByDay());
      assertThat(snap.statExtByDay())
          .as("sum(ext_stat_1m.count) per day")
          .isEqualTo(m.statExtCountByDay());
    }
    List<VariantResult> results = runAll("Q13");
    Map<String, VariantResult> byName = new LinkedHashMap<>();
    for (VariantResult r : results) {
      byName.put(r.variant(), r);
      Evidence.record(
          "consistency",
          r.label() + " mismatching rows",
          (long) r.metric("rows") + " " + firstRows(r));
    }
    for (String strict : List.of("total_30d", "rollups_14d", "minute_raw_1m")) {
      VariantResult r = byName.get(strict);
      assertThat(r).as(strict).isNotNull();
      assertThat(r.verdict())
          .as(r.label() + " " + r.violations() + " " + firstRows(r))
          .isEqualTo(Verdict.PASS);
    }
    // Per-day forms (13 §6.3 (14)): a statement that ends after midnight belongs to the next day's
    // statistics window (generator stat-window=end). Known open: D-12 per-day attribution pending
    // PM decision (PoC 7.3). The checks must run and report exactly the days on which the
    // manifest's xlog and statistics values differ (midnight attribution), nothing else.
    VariantResult daily = byName.get("daily_30d");
    VariantResult nightly = byName.get("nightly");
    for (VariantResult r : List.of(daily, nightly)) {
      assertThat(r.verdict())
          .as(r.label() + " " + r.violations())
          .isNotIn(Verdict.ERROR, Verdict.INVALID, Verdict.SKIPPED);
    }
    if (m == null) {
      // The fixture attributes the statistics to the transaction start (stat-window=start).
      assertThat(daily.verdict())
          .as(daily.label() + " " + firstRows(daily))
          .isEqualTo(Verdict.PASS);
      assertThat(nightly.verdict()).as(nightly.label()).isEqualTo(Verdict.PASS);
      return;
    }
    Set<LocalDate> days = new TreeSet<>(m.xlogSqlCountByDay().keySet());
    days.addAll(m.statSqlCountByDay().keySet());
    days.addAll(m.xlogExtCountByDay().keySet());
    days.addAll(m.statExtCountByDay().keySet());
    Set<LocalDate> expectedDays = new TreeSet<>();
    for (LocalDate d : days) {
      boolean sql =
          !m.xlogSqlCountByDay()
              .getOrDefault(d, 0L)
              .equals(m.statSqlCountByDay().getOrDefault(d, 0L));
      boolean ext =
          !m.xlogExtCountByDay()
              .getOrDefault(d, 0L)
              .equals(m.statExtCountByDay().getOrDefault(d, 0L));
      if (sql || ext) {
        expectedDays.add(d);
      }
    }
    Set<LocalDate> reported = new TreeSet<>();
    for (List<String> row : daily.lastRows()) {
      reported.add(LocalDate.parse(row.get(2)));
    }
    assertThat(reported)
        .as("days reported by daily_30d = days where the manifest's xlog and statistics differ")
        .isEqualTo(expectedDays);
    LocalDate fullDay = LocalDate.parse(ctx.shared().get("full_day"));
    boolean nightlyExpected =
        !m.xlogSqlCountByDay()
            .getOrDefault(fullDay, 0L)
            .equals(m.statSqlCountByDay().getOrDefault(fullDay, 0L));
    assertThat(nightly.lastRows())
        .as(
            "nightly (13 §6.3 (14), sql only, "
                + fullDay
                + ") reports the day exactly when the manifest differs")
        .hasSize(nightlyExpected ? 1 : 0);
    Evidence.record(
        "consistency",
        "Q13 per-day (D-12, PM decision pending): days with a manifest difference / reported by"
            + " daily_30d / nightly rows",
        expectedDays + " / " + reported + " / " + nightly.lastRows().size());
  }

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-ST-032 Q15: the same block_id 100 times changes no raw or rollup total; a broken view is"
          + " filled by the retry after repair; Q12 window 0")
  void q15() {
    ProcedureResult r = DedupReinsert.run(ctx, 2000, 100);
    Evidence.record("consistency", "Q15", r.metrics() + " " + r.notes());
    assertThat(r.metricValue("failed_insert_error"))
        .as("insert with the broken view fails")
        .isEqualTo(1.0);
    assertThat(r.verdict()).as(r.violations() + " " + r.tables()).isEqualTo(Verdict.PASS);
  }

  @Test
  @Tag("req:D-03")
  @DisplayName(
      "T-ST-033 Q16: 20,000 late rows (ts one hour ago, 13 §15) update exactly their minutes and"
          + " hours; Q12 0 afterwards")
  void q16() {
    ProcedureResult r = LateArrival.run(ctx, 20_000, 5_000);
    Evidence.record("consistency", "Q16", r.metrics() + " " + r.notes());
    assertThat(r.metricValue("late_rows")).isEqualTo(20000.0);
    assertThat(r.verdict()).as(r.violations() + " " + r.tables()).isEqualTo(Verdict.PASS);
  }

  @Test
  @Tag("req:D-03")
  @Tag("req:D-12")
  @DisplayName("T-ST-030/031 negative control: one extra rollup row or statistics row is detected")
  void checksDetectMismatches() {
    SchemaParameters neg = SchemaParameters.defaults().withTablePrefix("neg_");
    SchemaApplier.apply(ctx.client(Account.MIGRATOR), neg);
    Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
    CiDataset small =
        CiDataset.generate(
            new CiDataset.Config(
                7, now.minus(Duration.ofHours(3)), Duration.ofHours(3), 0.5, 2, 5000));
    small.load(ctx.client(Account.WRITER), neg);
    QueryRunner runner =
        new QueryRunner(ctx.runner().server(), neg, QueryRunner.Options.defaults(), null);
    BenchContext negCtx = BenchContext.resolve(runner, ctx.catalog());
    QueryFile q12 = ctx.file("Q12");
    QueryFile q13 = ctx.file("Q13");
    Parameters p12 = runner.resolve(q12, negCtx.shared());
    Parameters p13 = runner.resolve(q13, negCtx.shared());
    assertThat(runner.run(q12, q12.variant("minute_30d"), p12).verdict()).isEqualTo(Verdict.PASS);
    assertThat(runner.run(q13, q13.variant("total_30d"), p13).verdict()).isEqualTo(Verdict.PASS);

    String minute = negCtx.shared().get("start_minute");
    ctx.client(Account.MIGRATOR)
        .execute(
            "INSERT INTO "
                + neg.table("app_stat_1m")
                + " (app_id, env, kind, minute, count)"
                + " VALUES (1, 'prod', 'HTTP', {m:DateTime}, 1)",
            Map.of("param_m", minute));
    VariantResult r12 = runner.run(q12, q12.variant("minute_30d"), p12);
    assertThat(r12.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(r12.metric("rows")).isEqualTo(1.0);

    ctx.client(Account.WRITER)
        .insert(
            "INSERT INTO "
                + neg.table("sql_stat_raw")
                + " (app_id, env, instance_id, ts, kind, sql_id, db_id, count, buckets) FORMAT TabSeparated",
            "1\tprod\t101\t" + minute + "\tHTTP\t42\t7\t1\t[0,0,0,0,0,0,0,0,0,0,0,0,0,0,1]\n",
            Map.of("insert_deduplication_token", "argos:sql_stat_raw:negative-control"));
    VariantResult r13 = runner.run(q13, q13.variant("total_30d"), p13);
    assertThat(r13.verdict()).isEqualTo(Verdict.FAIL);
    // The raw row above went through the views (sql_stat_1m has it too): raw = 1m per minute.
    assertThat(runner.run(q13, q13.variant("minute_raw_1m"), p13).verdict())
        .isEqualTo(Verdict.PASS);
    ctx.client(Account.MIGRATOR)
        .execute(
            "INSERT INTO "
                + neg.table("ext_stat_1m")
                + " (app_id, env, kind, host_id, minute, count)"
                + " VALUES (1, 'prod', 'HTTP', 7, {m:DateTime}, 1)",
            Map.of("param_m", minute));
    VariantResult r13m = runner.run(q13, q13.variant("minute_raw_1m"), p13);
    assertThat(r13m.verdict())
        .as("an ext_stat_1m row without its raw statistics")
        .isEqualTo(Verdict.FAIL);
    assertThat(r13m.metric("rows")).isEqualTo(1.0);

    // A row found only in the third source (service_stat_1m), of an application and kind that no
    // other source has: the key, including the Enum kind, must be reported as it is (a FULL JOIN
    // USING chain labels such a key with default values; the UNION ALL form does not).
    ctx.client(Account.MIGRATOR)
        .execute(
            "INSERT INTO "
                + neg.table("service_stat_1m")
                + " (app_id, env, kind, service_id, version_id, minute, count)"
                + " VALUES (999, 'prod', 'BATCH', 1, 0, {m:DateTime}, 1)",
            Map.of("param_m", minute));
    VariantResult r12b = runner.run(q12, q12.variant("minute_30d"), p12);
    assertThat(r12b.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(r12b.metric("rows")).isEqualTo(2.0);
    List<String> only = onlyRow(r12b.lastRows(), "999");
    assertThat(only.get(1)).as("kind of a key found only in service_stat_1m").isEqualTo("BATCH");
    assertThat(only.subList(3, 7)).containsExactly("0", "0", "1", "0");
    // The UNION ALL form does not depend on join_use_nulls: forced to 1, the same rows come back.
    QueryFile.Section v = q12.variant("minute_30d");
    Measurement forced =
        runner.execute(
            Account.MIGRATOR,
            SqlTemplate.render(v.sql(), runner.textual(Map.of())),
            Map.of("max_execution_time", "1800", "join_use_nulls", "1"),
            p12.with(v.params()),
            "Q12/minute_30d-join_use_nulls_1",
            "once",
            true);
    assertThat(forced.failed()).as(forced.errorMessage()).isFalse();
    List<List<String>> forcedRows = Tsv.rows(forced.body());
    assertThat(forcedRows).hasSize(2);
    assertThat(onlyRow(forcedRows, "999").get(1)).isEqualTo("BATCH");
    Evidence.record(
        "consistency",
        "negative control Q12 / Q13 total / Q13 minute_raw_1m rows; third-source-only key",
        r12.metric("rows")
            + " / "
            + r13.metric("rows")
            + " / "
            + r13m.metric("rows")
            + "; "
            + only);
  }

  private static List<String> onlyRow(List<List<String>> rows, String app) {
    return rows.stream()
        .filter(r -> r.get(0).equals(app))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row of app " + app + " in " + rows));
  }

  private static List<VariantResult> runAll(String id) {
    QueryFile f = ctx.file(id);
    Parameters p = ctx.runner().resolve(f, ctx.shared());
    return ctx.runner().runAll(f, p);
  }

  private static List<List<String>> firstRows(VariantResult r) {
    List<List<String>> rows = r.lastRows();
    return new ArrayList<>(rows.subList(0, Math.min(3, rows.size())));
  }
}
