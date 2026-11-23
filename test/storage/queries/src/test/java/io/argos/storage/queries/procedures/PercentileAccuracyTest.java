/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.Verdict;
import io.argos.storage.queries.procedures.PercentileAccuracy.StoredBucket;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-ST-042 stored-state p99 judgment per bucket: ADR-0010 C-219 and, on the ci profile, C-221. */
class PercentileAccuracyTest {

  private static StoredBucket bucket(long n, double valueErr, double rankErr, boolean rankOnly) {
    return new StoredBucket(
        "day",
        "1",
        "BATCH",
        "2026-01-05",
        n,
        100.0,
        100.0 * (1 + valueErr),
        valueErr,
        rankErr,
        rankOnly);
  }

  private static ProcedureResult judge(List<StoredBucket> buckets, boolean rankOnly) {
    ProcedureResult r =
        new ProcedureResult(
            "Q14/dd_stored", "Q14 dd_stored", PercentileAccuracy.storedCriterion(rankOnly));
    PercentileAccuracy.judgeStoredBuckets(r, buckets, 1000, rankOnly);
    return r.judge();
  }

  @Test
  @DisplayName(
      "C-219 (default/local/full): tail n x 0.01 < 100 by rank error <= 0.5 pp, otherwise value"
          + " error <= 5 %")
  void c219JudgesByTailSize() {
    StoredBucket rank = bucket(9_999, 0.30, 0.004, false);
    StoredBucket value = bucket(10_000, 0.049, 0.02, false);
    assertThat(rank.valueJudged()).isFalse();
    assertThat(rank.pass()).isTrue();
    assertThat(value.valueJudged()).isTrue();
    assertThat(value.pass()).isTrue();
    assertThat(bucket(19_300, 0.05788, 0.00005, false).pass()).isFalse();
    assertThat(bucket(500, 0.0, 0.0051, false).pass()).isFalse();

    ProcedureResult r = judge(List.of(rank, value), false);
    assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.PASS);
    assertThat(r.metricValue("p99_value_judged_buckets")).isEqualTo(1.0);
    assertThat(r.metricValue("p99_rank_judged_buckets")).isEqualTo(1.0);
    assertThat(r.metricValue("p99_value_err_recorded_max")).isEqualTo(0.30);
    assertThat(r.tables().get(0).title()).startsWith("C-219");
  }

  @Test
  @DisplayName("C-219: no value-judged bucket fails the criterion")
  void c219RequiresAValueJudgedBucket() {
    ProcedureResult r = judge(List.of(bucket(5_000, 0.30, 0.001, false)), false);
    assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(r.notes()).anySatisfy(n -> assertThat(n).startsWith("C-219: no stored bucket"));
  }

  @Test
  @DisplayName(
      "C-221 (ci profile): every bucket by rank error <= 0.5 pp, value error recorded only, no"
          + " value-judged bucket required")
  void c221JudgesEveryBucketByRank() {
    // The ci-profile 1d BATCH bucket: 193 tail values, value error 5.788 %, rank error 0.005 pp.
    StoredBucket batch = bucket(19_300, 0.05788, 0.00005, true);
    assertThat(batch.valueJudged()).isFalse();
    assertThat(batch.pass()).isTrue();
    assertThat(bucket(19_300, 0.0, 0.0051, true).pass()).isFalse();

    ProcedureResult r = judge(List.of(batch, bucket(2_000, 0.26, 0.003, true)), true);
    assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.PASS);
    assertThat(r.metricValue("p99_value_judged_buckets")).isZero();
    assertThat(r.metricValue("p99_rank_judged_buckets")).isEqualTo(2.0);
    assertThat(r.metricValue("p99_value_err_recorded_max")).isEqualTo(0.26);
    ProcedureResult.Table table = r.tables().get(0);
    assertThat(table.title()).startsWith("C-221");
    int judgedBy = table.headers().indexOf("judged by");
    int valueErr = table.headers().indexOf("value err");
    assertThat(table.rows()).allSatisfy(row -> assertThat(row.get(judgedBy)).isEqualTo("rank"));
    assertThat(table.rows().get(0).get(valueErr)).isEqualTo("5.788 %");
    assertThat(r.notes()).anySatisfy(n -> assertThat(n).startsWith("ci profile (C-221)"));

    ProcedureResult failing = judge(List.of(bucket(19_300, 0.0, 0.0051, true)), true);
    assertThat(failing.verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  @DisplayName("Options: only the ci profile selects C-221; default, local and full keep C-219")
  void profileSelectsTheRule() {
    for (String profile : List.of("", "local", "full")) {
      PercentileAccuracy.Options o =
          new PercentileAccuracy.Options(
              PercentileAccuracy.Source.DATASET, 0, 0, 0, 1, 1, 1, 1000, profile);
      assertThat(o.storedRankOnly()).as(profile).isFalse();
      assertThat(PercentileAccuracy.storedCriterion(o.storedRankOnly()))
          .isEqualTo(PercentileAccuracy.STORED_CRITERION);
    }
    PercentileAccuracy.Options ci =
        new PercentileAccuracy.Options(
            PercentileAccuracy.Source.DATASET, 0, 0, 0, 1, 1, 1, 1000, "ci");
    assertThat(ci.storedRankOnly()).isTrue();
    assertThat(PercentileAccuracy.storedCriterion(ci.storedRankOnly()))
        .isEqualTo(PercentileAccuracy.STORED_CRITERION_CI);
    assertThat(PercentileAccuracy.Options.bench().storedRankOnly()).isFalse();
  }
}
