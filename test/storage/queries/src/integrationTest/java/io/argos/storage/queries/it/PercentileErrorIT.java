/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.Verdict;
import io.argos.storage.queries.procedures.PercentileAccuracy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ST-042: the value error of the stored percentile states at 1m, 1h and 1d, asserted on the
 * criteria of ADR-0007 C-53 (which redefines the 13 §15 criterion) through the Q14 procedure
 * ({@link PercentileAccuracy}) against {@code quantileExact}:
 *
 * <ul>
 *   <li>synthetic source ({@code Q14/dd}, the {@code quantilesDD(0.01, ...)} state merge of the
 *       rollups at full-scale per-minute density: 1 application x 3,000 values per minute, 1 day):
 *       the p95 over the 1m groups of the value error of p50 and of p95 at most 2 %, the maximum
 *       value error of p99 at 1h and 1d at most 5 %, the maximum rank error of the 1m p99 at most
 *       0.5 percentage points;
 *   <li>loaded data ({@code Q14/dd_stored}, the states the views stored in {@code app_stat_1m} and
 *       cascaded to {@code app_stat_1h}, merged to 1d): the p99 of every 1h and 1d bucket judged
 *       per ADR-0010 C-219, which applies the C-53 reasoning by sample size: a bucket with fewer
 *       than 100 tail values (n x 0.01) by the rank error (at most 0.5 percentage points), a bucket
 *       with 100 or more by the value error (at most 5 %); at least one bucket must be
 *       value-judged, otherwise the test fails. With {@code -Pqueries.profile=ci} (the generator's
 *       1/100 profile, 18 §9) ADR-0010 C-221 applies instead: every bucket's p99 by the rank error
 *       (at most 0.5 percentage points), the value error recorded but not judged, no value-judged
 *       bucket required (the local and full profiles keep C-219). The per-bucket n, judgment mode
 *       and values are recorded in {@code percentile.md}. With any generator profile groups with at
 *       least 1,000 values are judged; the default 2-day 1 TPS dataset has fewer values per group,
 *       so groups with at least 300 values are judged there.
 * </ul>
 *
 * On the loaded data the procedure also rebuilds the last full day with the C-59 procedure twice
 * and compares the stored DD states byte for byte ({@code Q14/d14_rebuild}, D-14).
 */
@Tag("integration")
@Tag("req:A-11")
@Tag("req:D-14")
class PercentileErrorIT {

  private static BenchContext ctx;

  @BeforeAll
  static void load() {
    Fixture.dataset();
    ctx = Fixture.context();
  }

  private static Map<String, ProcedureResult> byId(List<ProcedureResult> results) {
    return results.stream().collect(Collectors.toMap(ProcedureResult::id, r -> r, (a, b) -> a));
  }

  @Test
  @DisplayName(
      "T-ST-042 synthetic Q14/dd: 1m p95-of-groups value error p50 and p95 <= 2 %, 1h/1d p99 max"
          + " value error <= 5 %, 1m p99 max rank error <= 0.5 pp (C-53)")
  void syntheticStateMerge() {
    Map<String, ProcedureResult> results =
        byId(
            PercentileAccuracy.run(
                ctx,
                new PercentileAccuracy.Options(
                    PercentileAccuracy.Source.SYNTHETIC, 1, 3000, 1, 1, 1, 1, 1000, "")));
    ProcedureResult dd = results.get("Q14/dd");
    assertThat(dd).as("Q14/dd result").isNotNull();
    Evidence.record("percentile", "Q14/dd synthetic 3000/min, 1 day: metrics", dd.metrics());
    assertThat(dd.metricValue("p50_err_1m_p95")).isFinite().isBetween(0.0, 0.02);
    assertThat(dd.metricValue("p95_err_1m_p95")).isFinite().isBetween(0.0, 0.02);
    assertThat(dd.metricValue("p99_err_1h_max")).isFinite().isBetween(0.0, 0.05);
    assertThat(dd.metricValue("p99_rank_err_minute")).isFinite().isBetween(0.0, 0.005);
    assertThat(dd.verdict()).as(dd.toString()).isEqualTo(Verdict.PASS);
  }

  @Test
  @DisplayName(
      "T-ST-042 loaded data Q14/dd_stored: the stored app_stat_1m/1h states merged to 1h and 1d,"
          + " p99 per bucket by rank error <= 0.5 pp (n x 0.01 < 100) or value error <= 5 %"
          + " (C-219), at least one value-judged bucket; ci profile: every bucket by rank error"
          + " <= 0.5 pp, value error recorded (C-221); the C-59 rebuild keeps the states byte for"
          + " byte")
  void storedStates() {
    String profile = System.getProperty("argos.queries.profile", "");
    long minCount = profile.isEmpty() ? 300 : 1000;
    boolean rankOnly = profile.equals(PercentileAccuracy.PROFILE_CI);
    Map<String, ProcedureResult> results =
        byId(
            PercentileAccuracy.run(
                ctx,
                new PercentileAccuracy.Options(
                    PercentileAccuracy.Source.DATASET, 0, 0, 0, 1, 1, 1, minCount, profile)));
    ProcedureResult stored = results.get("Q14/" + PercentileAccuracy.STORED);
    assertThat(stored).as("Q14/dd_stored result").isNotNull();
    Evidence.record(
        "percentile", "Q14/dd_stored (groups n >= " + minCount + "): metrics", stored.metrics());
    List<List<String>> storedRows =
        results.get("Q14").tables().get(0).rows().stream()
            .filter(r -> r.get(0).equals(PercentileAccuracy.STORED))
            .toList();
    Evidence.record(
        "percentile",
        "Q14/dd_stored per level (function, level, quantile, groups, mean, p95, max)",
        storedRows);
    // D-14 first: the C-59 rebuild of the last full day keeps the stored DD states byte for byte.
    ProcedureResult rebuild = results.get("Q14/d14_rebuild");
    assertThat(rebuild.metricValue("rebuild_state_mismatches")).isZero();
    assertThat(rebuild.metricValue("view_state_mismatches")).isZero();
    // C-219: per bucket, p99 by rank error below 100 tail values, by value error from 100.
    // C-221 (ci profile): every bucket by rank error, the value error recorded only.
    String rule = rankOnly ? "C-221" : "C-219";
    ProcedureResult.Table buckets =
        stored.tables().stream().filter(t -> t.title().startsWith(rule)).findFirst().orElseThrow();
    Evidence.record("percentile", "Q14/dd_stored " + buckets.title(), "");
    Evidence.record("percentile", "  columns", String.join(" | ", buckets.headers()));
    for (List<String> row : buckets.rows()) {
      Evidence.record("percentile", "  bucket", String.join(" | ", row));
    }
    List<List<String>> failed =
        buckets.rows().stream().filter(r -> !r.get(r.size() - 1).equals("pass")).toList();
    long valueJudged = (long) stored.metricValue("p99_value_judged_buckets");
    long rankJudged = (long) stored.metricValue("p99_rank_judged_buckets");
    Evidence.record(
        "percentile",
        "Q14/dd_stored " + rule + " summary (profile '" + profile + "')",
        "value-judged buckets "
            + valueJudged
            + " (max value err "
            + stored.metricValue("p99_value_err_judged_max")
            + "), rank-judged buckets "
            + rankJudged
            + " (max rank err "
            + stored.metricValue("p99_rank_err_judged_max")
            + "), recorded max value err "
            + stored.metricValue("p99_value_err_recorded_max")
            + ", failed "
            + failed.size());
    for (String note : stored.notes()) {
      Evidence.record("percentile", "Q14/dd_stored note", note);
    }
    assertThat(valueJudged + rankJudged)
        .as(rule + " judged buckets equal the table rows")
        .isEqualTo(buckets.rows().size());
    assertThat(buckets.rows()).as(rule + " stored buckets").isNotEmpty();
    if (rankOnly) {
      // C-221: no bucket is value-judged; the value error stays in the table and the metric.
      assertThat(valueJudged).as("C-221: every bucket rank-judged").isZero();
      assertThat(buckets.rows())
          .as("C-221 judged-by column")
          .allSatisfy(r -> assertThat(r.get(6)).isEqualTo("rank"));
      assertThat(stored.metricValue("p99_value_err_recorded_max")).isFinite().isNotNegative();
      assertThat(stored.notes()).anySatisfy(n -> assertThat(n).startsWith("ci profile (C-221)"));
      assertThat(failed).as("C-221 buckets outside their criterion").isEmpty();
      assertThat(stored.metricValue("p99_rank_err_judged_max"))
          .as("C-221 rank-judged p99, per level " + storedRows)
          .isFinite()
          .isBetween(0.0, 0.005);
      assertThat(stored.verdict()).as(stored.toString()).isEqualTo(Verdict.PASS);
      return;
    }
    assertThat(valueJudged)
        .as(
            "C-219: at least one stored bucket must have 100 or more tail values and be"
                + " value-judged; buckets "
                + buckets.rows())
        .isPositive();
    assertThat(failed).as("C-219 buckets outside their criterion").isEmpty();
    assertThat(stored.metricValue("p99_value_err_judged_max"))
        .as("C-219 value-judged p99 (n x 0.01 >= 100), per level " + storedRows)
        .isFinite()
        .isBetween(0.0, 0.05);
    assertThat(stored.metricValue("p99_rank_err_judged_max"))
        .as("C-219 rank-judged p99 (n x 0.01 < 100), per level " + storedRows)
        .isFinite()
        .isBetween(0.0, 0.005);
    assertThat(stored.verdict()).as(stored.toString()).isEqualTo(Verdict.PASS);
  }
}
