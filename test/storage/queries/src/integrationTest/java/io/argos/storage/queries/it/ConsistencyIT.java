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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // The 1h instance cascade of ADR-0010 C-212 (1) (schema V003): per (app, instance, kind, hour)
    // count(xlog) = sum(instance_stat_1m) = sum(instance_stat_1h), in the C-65 form.
    SchemaParameters p = Fixture.schema();
    String sql =
        "SELECT count() FROM (SELECT app_id, instance_id, kind, h,"
            + " sumIf(c, src = 'xlog') AS x, sumIf(c, src = 'inst_1m') AS m,"
            + " sumIf(c, src = 'inst_1h') AS hr FROM ("
            + "SELECT 'xlog' AS src, app_id, instance_id, kind, toStartOfHour(ts) AS h, count() AS c"
            + " FROM "
            + p.table("xlog")
            + " GROUP BY app_id, instance_id, kind, h"
            + " UNION ALL SELECT 'inst_1m', app_id, instance_id, kind, toStartOfHour(minute) AS h,"
            + " sum(count) FROM "
            + p.table("instance_stat_1m")
            + " GROUP BY app_id, instance_id, kind, h"
            + " UNION ALL SELECT 'inst_1h', app_id, instance_id, kind, hour, sum(count) FROM "
            + p.table("instance_stat_1h")
            + " GROUP BY app_id, instance_id, kind, hour)"
            + " GROUP BY app_id, instance_id, kind, h HAVING x != m OR x != hr)";
    long instanceHours =
        ctx.client(Account.MIGRATOR)
            .scalarLong(
                "SELECT count() FROM (SELECT DISTINCT app_id, instance_id, kind, hour FROM "
                    + p.table("instance_stat_1h")
                    + ")");
    long mismatches = ctx.client(Account.MIGRATOR).scalarLong(sql);
    Evidence.record(
        "consistency",
        "Q12 instance 1h cascade (C-212): (app, instance, kind, hour) keys / mismatching keys",
        instanceHours + " / " + mismatches);
    assertThat(instanceHours).as("instance_stat_1h keys").isPositive();
    assertThat(mismatches).as("xlog vs instance_stat_1m vs instance_stat_1h per hour").isZero();
  }

  @Test
  @Tag("req:D-12")
  @DisplayName(
      "T-ST-031 Q13 D-12: sum(xlog.sql_count / ext_count) = sum(sql/ext_stat_1m.count) over the"
          + " range, raw statistics = 1m and 1h rollups per day and = 1m per minute: 0 mismatches;"
          + " per day (daily_30d, nightly) the C-57 rule: 0 days beyond the bound for every instance,"
          + " the +60 s instances included (ADR-0009 C-84 as fixed by ADR-0010 C-129)")
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
    // Per-day forms (13 §6.3 (14)), ADR-0007 C-57: a statement that ends after midnight belongs to
    // the next day's statistics window (agent attribution, generator stat-window=end). A day passes
    // when |xlog - statistics| is at most the sql_count / ext_count of the transactions that cross
    // one of its midnights; a larger difference is a mismatch (recorded, F-S22).
    VariantResult daily = byName.get("daily_30d");
    VariantResult nightly = byName.get("nightly");
    for (VariantResult r : List.of(daily, nightly)) {
      assertThat(r.verdict())
          .as(r.label() + " " + r.violations())
          .isNotIn(Verdict.ERROR, Verdict.INVALID, Verdict.SKIPPED);
    }
    if (m == null) {
      // The fixture attributes the statistics to the transaction start (stat-window=start) and
      // has no midnight-crossing statement: every day is exact.
      assertThat(daily.verdict())
          .as(daily.label() + " " + firstRows(daily))
          .isEqualTo(Verdict.PASS);
      assertThat(nightly.verdict()).as(nightly.label()).isEqualTo(Verdict.PASS);
      return;
    }
    // Independent per-day values from the raw tables (raw = 1m per minute, minute_raw_1m above),
    // once for every instance and once for the instances whose clock is 60 s ahead only.
    List<Integer> skewed = data.skewedInstanceIds();
    assertThat(skewed).hasSize(data.skewedInstances()).isNotEmpty();
    String skewedList = String.join(",", skewed.stream().map(String::valueOf).toList());
    Map<List<String>, long[]> all = perDay("");
    Map<List<String>, long[]> onlySkewed = perDay(" AND instance_id IN (" + skewedList + ")");
    // (1) C-57 is sound for every instance (ADR-0009 C-84, ADR-0010 C-129): the server corrects
    // the ts, the statistics window and the metric ts of a session more than 5 s ahead by the same
    // 10 s multiple instead of clamping the transaction to the receive time and the window to the
    // flush window, so a statement that ends just before midnight on the agent clock stays on the
    // day of its transaction. No day exceeds the bound, and the bound is needed (days with a
    // difference exist).
    assertThat(exceeding(all)).as("days beyond the C-57 bound, all instances").isEmpty();
    assertThat(onlySkewed).as("per-day values of the skewed instances").isNotEmpty();
    assertThat(exceeding(onlySkewed))
        .as("days beyond the C-57 bound, skewed instances " + skewed)
        .isEmpty();
    long differing = all.values().stream().filter(v -> v[0] != v[1] || v[2] != v[3]).count();
    assertThat(differing).as("days whose xlog and statistics differ (all instances)").isPositive();
    // (2) daily_30d applies the rule: it reports no group (the independent values above put none
    // beyond the bound).
    assertThat(daily.lastRows())
        .as("daily_30d groups beyond the C-57 bound " + firstRows(daily))
        .isEmpty();
    assertThat(daily.verdict()).as(daily.label()).isEqualTo(Verdict.PASS);
    // (3) The correction is what the server applied: no row of the load is clamped (the skewed
    // instances are corrected by 60 s, the others are within 2 s), and the corrected transactions
    // exist (C-129 (7) counts them as session_offset).
    for (io.argos.storage.generator.rows.Table table : io.argos.storage.generator.rows.Table.ALL) {
      assertThat(m.clampedRows(table)).as("clamped rows of " + table.name()).isZero();
    }
    assertThat(m.correctedTransactions()).as("transactions of corrected sessions").isPositive();
    // (4) nightly (13 §6.3 (14): one day, every application, SQL only): |sum of the differences| is
    // at most the sum of the bounds, so the day is not reported.
    String fullDay = ctx.shared().get("full_day");
    long deltaSql = 0;
    long boundSql = 0;
    for (Map.Entry<List<String>, long[]> e : all.entrySet()) {
      if (e.getKey().get(2).equals(fullDay)) {
        deltaSql += e.getValue()[0] - e.getValue()[1];
        boundSql += e.getValue()[4];
      }
    }
    assertThat(Math.abs(deltaSql)).as("nightly delta on " + fullDay).isLessThanOrEqualTo(boundSql);
    assertThat(nightly.lastRows())
        .as("nightly on " + fullDay + ": |delta| " + Math.abs(deltaSql) + ", bound " + boundSql)
        .isEmpty();
    assertThat(nightly.verdict()).as(nightly.label()).isEqualTo(Verdict.PASS);
    Evidence.record(
        "consistency",
        "Q13 per day (C-57, C-84/C-129): groups beyond the bound (all instances) / skewed"
            + " instances "
            + skewed
            + " / days with a difference / nightly rows",
        exceeding(all)
            + " / "
            + exceeding(onlySkewed)
            + " / "
            + differing
            + " / "
            + nightly.lastRows());
  }

  /**
   * Per (app, kind, day) of the dataset: sum(xlog.sql_count), sum(sql_stat_raw.count),
   * sum(xlog.ext_count), sum(ext_stat_raw.count), and the C-57 bound (sql_count, ext_count of the
   * transactions crossing a midnight of the day), each source restricted by {@code filter}.
   */
  private static Map<List<String>, long[]> perDay(String filter) {
    String xlog = Fixture.schema().table("xlog");
    String crossDays =
        "arrayJoin(arrayMap(i -> toDate(ts) + i, range(toUInt32(dateDiff('day', toDate(ts),"
            + " toDate(ts + toIntervalMillisecond(elapsed_ms)))) + 1)))";
    String sql =
        "SELECT app_id, kind, d, sum(a), sum(b), sum(c), sum(e), sum(f), sum(g) FROM ("
            + "SELECT app_id, kind, toDate(ts) AS d, toInt64(sum(sql_count)) AS a, toInt64(0) AS b,"
            + " toInt64(sum(ext_count)) AS c, toInt64(0) AS e, toInt64(0) AS f, toInt64(0) AS g"
            + " FROM "
            + xlog
            + " WHERE 1"
            + filter
            + " GROUP BY app_id, kind, d"
            + " UNION ALL SELECT app_id, kind, toDate(ts), 0, toInt64(sum(count)), 0, 0, 0, 0 FROM "
            + Fixture.schema().table("sql_stat_raw")
            + " WHERE 1"
            + filter
            + " GROUP BY app_id, kind, toDate(ts)"
            + " UNION ALL SELECT app_id, kind, toDate(ts), 0, 0, 0, toInt64(sum(count)), 0, 0 FROM "
            + Fixture.schema().table("ext_stat_raw")
            + " WHERE 1"
            + filter
            + " GROUP BY app_id, kind, toDate(ts)"
            + " UNION ALL SELECT app_id, kind, "
            + crossDays
            + " AS d, 0, 0, 0, 0,"
            + " toInt64(sum(sql_count)), toInt64(sum(ext_count)) FROM "
            + xlog
            + " WHERE toDate(ts + toIntervalMillisecond(elapsed_ms)) > toDate(ts)"
            + filter
            + " GROUP BY app_id, kind, d) GROUP BY app_id, kind, d";
    Map<List<String>, long[]> out = new java.util.LinkedHashMap<>();
    for (List<String> r : ctx.client(Account.MIGRATOR).rows(sql)) {
      long[] v = new long[6];
      for (int i = 0; i < 6; i++) {
        v[i] = Long.parseLong(r.get(3 + i));
      }
      out.put(List.of(r.get(0), r.get(1), r.get(2)), v);
    }
    return out;
  }

  /** Groups whose |xlog - statistics| exceeds the C-57 bound (SQL or outgoing calls). */
  private static List<List<String>> exceeding(Map<List<String>, long[]> perDay) {
    List<List<String>> out = new ArrayList<>();
    perDay.forEach(
        (key, v) -> {
          if (Math.abs(v[0] - v[1]) > v[4] || Math.abs(v[2] - v[3]) > v[5]) {
            out.add(key);
          }
        });
    return out;
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
            Map.of("insert_deduplication_token", "negative-control:1:0-0"));
    VariantResult r13 = runner.run(q13, q13.variant("total_30d"), p13);
    assertThat(r13.verdict()).isEqualTo(Verdict.FAIL);
    // C-57: one statistics row without a transaction is a difference beyond the midnight bound.
    VariantResult r13d = runner.run(q13, q13.variant("daily_30d"), p13);
    assertThat(r13d.verdict()).as("daily_30d " + r13d.lastRows()).isEqualTo(Verdict.FAIL);
    assertThat(r13d.metric("rows")).isEqualTo(1.0);
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
                + " (app_id, env, kind, service_id, app_version_id, minute, count)"
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
