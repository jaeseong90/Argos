/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.BenchResults;
import io.argos.storage.queries.BenchRun;
import io.argos.storage.queries.ColdStrategy;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.QueryRunner;
import io.argos.storage.queries.ReportWriter;
import io.argos.storage.queries.VariantResult;
import io.argos.storage.queries.Verdict;
import io.argos.storage.queries.procedures.CompressionComparison;
import io.argos.storage.queries.procedures.ConcurrentLoad;
import io.argos.storage.queries.procedures.PercentileAccuracy;
import io.argos.storage.queries.procedures.RetentionOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The whole catalog on the CI dataset: every query file and every procedure runs (small sizes),
 * system.query_log is collected and the Markdown report is written to
 * build/reports/storage-queries/ci/. Times on the CI dataset are not judged here; the run proves
 * that the runner, the procedures and the report work end to end against a real ClickHouse.
 *
 * <p>On the 1/100 profile ({@code -Pqueries.profile=ci}, the PR integration job) the run is a
 * harness smoke test so that the job stays within its time limit: Q01..Q11 with one run per
 * variant, the procedures at their smallest sizes, and without the copy procedures (Q05/bloom, Q18,
 * Q20), whose costs the bench measures. {@code -Pqueries.benchrun=full} runs the whole catalog on
 * any profile; the automatic criteria of the profile (T-ST-030..033, T-ST-053) are ConsistencyIT
 * and ReaderLimitsIT.
 */
@Tag("integration")
class BenchRunIT {

  /** Smoke mode: the 1/100 profile without {@code -Pqueries.benchrun=full}. */
  static boolean smoke() {
    return System.getProperty("argos.queries.profile", "").equals("ci")
        && !System.getProperty("argos.queries.benchrun", "").equals("full");
  }

  @Test
  @DisplayName(
      "Q01..Q20 and every procedure run on the CI dataset (1/100 profile: harness smoke, Q16 with"
          + " 1,000 late rows); system.query_log collected; report.md and runs.tsv written")
  void wholeCatalog() throws Exception {
    Fixture.dataset();
    boolean smoke = smoke();
    BenchContext ctx =
        smoke
            ? BenchContext.resolve(
                new QueryRunner(
                    Fixture.server(),
                    Fixture.schema(),
                    QueryRunner.Options.defaults().withWarmRuns(0),
                    Fixture.load()),
                Fixture.catalog())
            : Fixture.context();
    Set<String> files = new TreeSet<>();
    for (int i = 1; i <= (smoke ? 11 : 13); i++) {
      files.add(String.format(Locale.ROOT, "Q%02d", i));
    }
    Set<String> selected = new TreeSet<>(BenchRun.PROCEDURES);
    if (smoke) {
      selected.removeAll(List.of("Q05/bloom", "Q18", "Q20"));
    }
    BenchRun.Plan plan =
        new BenchRun.Plan(
            files,
            selected,
            new PercentileAccuracy.Options(
                PercentileAccuracy.Source.SYNTHETIC, 1, 600, 1, 1, 1, 1, 500),
            500,
            smoke ? 10 : 100,
            1000,
            new ConcurrentLoad.Options(
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                "none (CI)",
                ConcurrentLoad.Window.FIXED),
            new RetentionOps.Options(Duration.ofSeconds(20), 2, 24, Duration.ofSeconds(20)),
            new CompressionComparison.Options(1, Duration.ofSeconds(20)),
            1);
    BenchResults results = BenchRun.run(ctx, plan, Fixture.load());
    Path report = ReportWriter.write(results, Fixture.reportDir().resolve("ci"));
    String md = Files.readString(report, StandardCharsets.UTF_8);

    assertThat(results.errors()).isEmpty();
    for (VariantResult v : results.variants()) {
      assertThat(v.verdict())
          .as(v.toString())
          .isNotIn(Verdict.ERROR, Verdict.INVALID, Verdict.SKIPPED);
    }
    Map<String, ProcedureResult> procedures =
        results.procedures().stream()
            .collect(Collectors.toMap(ProcedureResult::id, p -> p, (a, b) -> a));
    List<String> expected =
        new ArrayList<>(
            List.of(
                "Q11/cost",
                "Q14",
                "Q14/tdigest",
                "Q14/bfloat16",
                "Q14/dd",
                "Q15",
                "Q16",
                "Q17",
                "Q19"));
    if (!smoke) {
      expected.addAll(List.of("Q05/bloom", "Q18", "Q20"));
    }
    assertThat(procedures.keySet()).containsAll(expected);
    for (ProcedureResult p : results.procedures()) {
      assertThat(p.verdict()).as(p.toString()).isNotEqualTo(Verdict.ERROR);
    }
    assertThat(procedures.get("Q15").verdict()).isEqualTo(Verdict.PASS);
    assertThat(procedures.get("Q16").verdict()).isEqualTo(Verdict.PASS);
    assertThat(procedures.get("Q19").verdict()).isEqualTo(Verdict.PASS);
    for (String fn : PercentileAccuracy.FUNCTIONS) {
      ProcedureResult q14 = procedures.get("Q14/" + fn);
      for (String q : List.of("p50_err", "p95_err", "p99_err")) {
        assertThat(q14.metricValue(q)).as(fn + " " + q).isFinite().isGreaterThanOrEqualTo(0);
      }
      Evidence.record(
          "bench-ci",
          "Q14 " + fn + " max relative error p50/p95/p99 (synthetic 600/min, 1 day)",
          q14.metrics());
    }
    assertThat(procedures.get("Q11/cost").metricValue("sql_ids_bytes_per_row")).isPositive();
    assertThat(procedures.get("Q17").metricValue("query_p95_ms")).isFinite();
    assertThat(procedures.get("Q19").metricValue("ttl_change_ms")).isFinite().isPositive();
    if (!smoke) {
      ProcedureResult q20 = procedures.get("Q20");
      assertThat(q20.metricValue("ratio_xlog")).isGreaterThan(1);
      for (String variant : List.of("base", "nocodec", "zstd", "zstd_nocodec", "proposed")) {
        assertThat(q20.metricValue("ratio_" + variant + "_xlog")).as(variant).isGreaterThan(1);
      }
      ProcedureResult q18 = procedures.get("Q18");
      assertThat(q18.metricValue("copy_rows")).isPositive();
      assertThat(q18.metricValue("mutation_where_ms")).isFinite();
      assertThat(q18.metricValue("mutation_in_partition_ms")).isFinite();
      assertThat(procedures.get("Q05/bloom").tables().get(0).rows()).hasSize(4);
    }
    // D-14 determinism table of Q14: every function at 1m and 1h compared.
    ProcedureResult.Table det =
        procedures.get("Q14").tables().stream()
            .filter(t -> t.title().startsWith("D-14"))
            .findFirst()
            .orElseThrow();
    assertThat(det.rows()).hasSize(6);
    Evidence.record("bench-ci", "Q14 D-14 determinism (rebuild a vs b)", det.rows());

    long collected = results.queryLog().size();
    assertThat(collected).as("system.query_log records").isGreaterThan(100);
    for (int i = 1; i <= 20; i++) {
      String id = String.format(Locale.ROOT, "Q%02d", i);
      if (!smoke || !List.of("Q12", "Q13", "Q18", "Q20").contains(id)) {
        assertThat(md).contains(id);
      }
    }
    List<String> tsv = Files.readAllLines(Fixture.reportDir().resolve("ci/runs.tsv"));
    assertThat(tsv).hasSizeGreaterThan(100);
    // The CPU load of every statement is known once the report is written (sampled each second,
    // computed at write time: also for statements shorter than a second).
    long withoutCpu = tsv.stream().skip(1).filter(l -> l.split("\t", -1)[7].equals("-")).count();
    assertThat(withoutCpu).as("runs.tsv lines without a machine CPU load").isZero();
    Evidence.record("bench-ci", "report", report.toAbsolutePath());
    Evidence.record("bench-ci", "query_log records", collected);
  }

  @Test
  @DisplayName(
      "Q14 on the loaded data: stored tDigest states of app_stat_1m / app_stat_1h are compared too")
  void percentileAccuracyOnTheDataset() {
    Fixture.dataset();
    BenchContext ctx = Fixture.context();
    List<ProcedureResult> results =
        PercentileAccuracy.run(
            ctx,
            new PercentileAccuracy.Options(
                PercentileAccuracy.Source.DATASET, 0, 0, 0, 1, 1, 1, 50));
    Map<String, ProcedureResult> byId =
        results.stream().collect(Collectors.toMap(ProcedureResult::id, p -> p));
    assertThat(byId)
        .containsKeys("Q14", "Q14/tdigest", "Q14/bfloat16", "Q14/dd", "Q14/tdigest_stored");
    assertThat(byId.get("Q14/tdigest_stored").metricValue("p50_err")).isFinite();
    for (ProcedureResult r : results) {
      assertThat(r.verdict()).as(r.toString()).isNotEqualTo(Verdict.ERROR);
      Evidence.record("bench-ci", r.id() + " (CI dataset, groups n >= 50)", r.metrics());
    }
  }

  @Test
  @DisplayName("Cold strategy restart needs a restartable server; none runs the first run as cold")
  void coldStrategies() {
    assertThat(Fixture.server().canRestart()).isFalse();
    assertThat(ColdStrategy.parse("none")).isEqualTo(ColdStrategy.NONE);
    assertThat(ColdStrategy.parse("command:true").mode()).isEqualTo(ColdStrategy.Mode.COMMAND);
  }
}
