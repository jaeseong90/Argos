/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Criteria, placeholders, parameters, statistics, TSV and the bucket estimate (no Docker). */
class RunnerPartsTest {

  @Test
  @DisplayName("Criterion: terms, record, missing metric = INVALID, violations")
  void criterion() {
    Criterion c = Criterion.parse("warm_ms <= 1000, rows >= 1");
    assertThat(c.evaluate(Map.of("warm_ms", 999.0, "rows", 1.0))).isEqualTo(Verdict.PASS);
    assertThat(c.evaluate(Map.of("warm_ms", 1000.5, "rows", 1.0))).isEqualTo(Verdict.FAIL);
    assertThat(c.evaluate(Map.of("warm_ms", 10.0))).isEqualTo(Verdict.INVALID);
    assertThat(c.violations(Map.of("warm_ms", 1500.0, "rows", 0.0)))
        .containsExactly("warm_ms = 1500 (warm_ms <= 1000)", "rows = 0.000 (rows >= 1)");
    assertThat(Criterion.parse("record").evaluate(Map.of())).isEqualTo(Verdict.RECORDED);
    assertThat(Criterion.parse("p99_err <= 0.05").terms().get(0).value()).isEqualTo(0.05);
    assertThatThrownBy(() -> Criterion.parse("warm_ms ~ 3"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Textual placeholders render identifiers only; typed ones stay for the server")
  void templates() {
    String sql =
        "SELECT * FROM {db}.{p}xlog WHERE app_id = {app:UInt32} AND ts < {end:DateTime64(3, 'UTC')}";
    assertThat(SqlTemplate.render(sql, Map.of("db", "argos", "p", "ci_")))
        .isEqualTo(
            "SELECT * FROM argos.ci_xlog WHERE app_id = {app:UInt32} AND ts < {end:DateTime64(3, 'UTC')}");
    assertThat(SqlTemplate.typedPlaceholders(sql)).containsEntry("end", "DateTime64(3, 'UTC')");
    assertThatThrownBy(() -> SqlTemplate.render(sql, Map.of("db", "argos; DROP TABLE x", "p", "")))
        .hasMessageContaining("not an identifier");
    assertThatThrownBy(() -> SqlTemplate.render(sql, Map.of("db", "argos")))
        .hasMessageContaining("{p}");
    assertThatThrownBy(() -> SqlTemplate.typedPlaceholders("{a:UInt32} {a:String}"))
        .hasMessageContaining("two types");
  }

  @Test
  @DisplayName("Parameters bind as param_<name>, report missing ones, inline only safe literals")
  void parameters() {
    Parameters p = Parameters.of(Map.of("app", "3", "day", "2026-10-23", "s", "a'b\\c"));
    assertThat(p.bind("SELECT {app:UInt32}, {day:Date}"))
        .containsEntry("param_app", "3")
        .containsEntry("param_day", "2026-10-23");
    assertThatThrownBy(() -> p.bind("SELECT {nope:UInt8}")).hasMessageContaining("nope");
    assertThat(p.inline("SELECT {app:UInt32}, {day:Date}, {s:String}"))
        .isEqualTo("SELECT 3, toDate('2026-10-23'), 'a\\'b\\\\c'");
    assertThatThrownBy(() -> Parameters.of(Map.of("app", "1 OR 1")).inline("{app:UInt32}"))
        .hasMessageContaining("integer");
  }

  @Test
  @DisplayName("Median and percentiles interpolate between ranks; relative error")
  void stats() {
    assertThat(Stats.median(3, 1, 2)).isEqualTo(2);
    assertThat(Stats.median(4, 1, 2, 3)).isEqualTo(2.5);
    assertThat(Stats.percentile(new double[] {1, 2, 3, 4, 5}, 0.95)).isCloseTo(4.8, within(1e-9));
    assertThat(Stats.median()).isNaN();
    assertThat(Stats.relativeError(102, 100)).isCloseTo(0.02, within(1e-12));
    assertThat(Stats.relativeError(0, 0)).isZero();
  }

  @Test
  @DisplayName(
      "TSV: raw fields keep escapes (parameter form), rows unescape, count ignores the last newline")
  void tsv() {
    String body = "a\\tb\t[1,2]\nx\\\\y\t\n";
    assertThat(Tsv.rawRows(body)).containsExactly(List.of("a\\tb", "[1,2]"), List.of("x\\\\y", ""));
    assertThat(Tsv.rows(body)).containsExactly(List.of("a\tb", "[1,2]"), List.of("x\\y", ""));
    assertThat(Tsv.count(body)).isEqualTo(2);
    assertThat(Tsv.count("")).isZero();
  }

  @Test
  @DisplayName("Bucket p95: linear interpolation inside the bucket; open bucket returns 30,000 ms")
  void bucketQuantile() {
    long[] h = new long[15];
    h[6] = 100; // (50, 100]
    assertThat(BucketQuantile.estimate(h, 0.95)).isCloseTo(97.5, within(1e-9));
    assertThat(BucketQuantile.estimate(h, 0.5)).isCloseTo(75, within(1e-9));
    long[] two = new long[15];
    two[0] = 90; // [0, 1]
    two[9] = 10; // (500, 1000]
    assertThat(BucketQuantile.estimate(two, 0.95)).isCloseTo(750, within(1e-9));
    long[] open = new long[15];
    open[14] = 5;
    assertThat(BucketQuantile.estimate(open, 0.95)).isEqualTo(30000);
    assertThat(BucketQuantile.estimate(new long[15], 0.95)).isNaN();
    assertThat(BucketQuantile.bucket(1)).isZero();
    assertThat(BucketQuantile.bucket(30000)).isEqualTo(13);
    assertThat(BucketQuantile.bucket(30001)).isEqualTo(14);
    assertThat(BucketQuantile.bound(13)).isEqualTo(30000);
  }

  @Test
  @DisplayName("Report cells cannot break the Markdown table or inject HTML")
  void reportCells() {
    assertThat(ReportWriter.cell("a|b\n<script>")).isEqualTo("a\\|b &lt;script&gt;");
    assertThat(ReportWriter.num(Double.NaN, 1)).isEqualTo("-");
    assertThat(ReportWriter.num(1.25, 1)).isEqualTo("1.3");
  }

  @Test
  @DisplayName("CLI options and plan selection")
  void cli() {
    Map<String, String> o =
        QueryBench.parse(
            new String[] {
              "--volume", "v", "--apply-schema", "--queries", "Q01,Q12", "--procedures", "none"
            });
    assertThat(o).containsEntry("volume", "v").containsEntry("apply-schema", "true");
    BenchRun.Plan plan = QueryBench.plan(o);
    assertThat(plan.files()).containsExactly("Q01", "Q12");
    assertThat(plan.procedures()).isEmpty();
    assertThatThrownBy(() -> QueryBench.plan(Map.of("queries", "Q99"))).hasMessageContaining("Q99");
    assertThat(ColdStrategy.parse("restart+dropcache").mode())
        .isEqualTo(ColdStrategy.Mode.RESTART_DROP_OS_CACHE);
  }
}
