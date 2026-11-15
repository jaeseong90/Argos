/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.SourceSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The time grid, the series filling, deltas and outliers of the dashboard (14 §4.4). */
@Tag("req:D-03")
@Tag("req:D-23")
class DashboardSeriesTest {

  private static final SourceSelector.Family FAMILY =
      new SourceSelector.Family(
          "transactions",
          new SourceSelector.Retention(
              Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(365)),
          true);

  private static SourcePlan plan(Instant from, Instant to, Instant now, String step) {
    return SourceSelector.plan(
        new SourceSelector.Request(
            from, to, SourceSelector.parseStep(step), Duration.ofDays(366), FAMILY, now, null));
  }

  @Test
  @DisplayName(
      "D-03 the TPS series integrates back to the count: one transaction in each of 720 one-minute"
          + " buckets gives Σ tps × 60 s = 720 within 0.025 (6 decimals: at most 3e-5 per bucket; 3"
          + " decimals gave 14.4 too many)")
  void tpsIntegral() {
    Instant from = Instant.parse("2026-11-08T00:00:00Z");
    Instant to = from.plus(Duration.ofHours(12));
    SourcePlan plan = plan(from, to, to.plus(Duration.ofHours(1)), "1m");
    SeriesGrid grid = new SeriesGrid(plan, to.plus(Duration.ofHours(1)));
    Map<Integer, TxSeries.Aggregate> buckets = new HashMap<>();
    for (int i = 0; i < grid.size(); i++) {
      buckets.put(i, new TxSeries.Aggregate(1, 0, 10, 0, 10.0, 10.0, 10.0));
    }
    int n = grid.size();
    List<Double> tps = SeriesGrid.nulls(n);
    DashboardService.fillTx(
        grid,
        new TxSeries.Result(buckets, new TxSeries.Aggregate(n, 0, 10L * n, 0, 10.0, 10.0, 10.0)),
        SeriesGrid.nulls(n),
        tps,
        SeriesGrid.nulls(n),
        SeriesGrid.nulls(n),
        SeriesGrid.nulls(n),
        SeriesGrid.nulls(n));
    assertThat(n).isEqualTo(720);
    double integral = 0;
    for (int i = 0; i < n; i++) {
      integral += tps.get(i) * grid.seconds(i);
    }
    assertThat(integral).isCloseTo(720.0, Offset.offset(0.025));
    assertThat(Values.tps(1, 60)).isEqualTo(0.016667);
  }

  @Test
  @DisplayName(
      "D-03 partial edge buckets count only their seconds inside the range and before now; future"
          + " buckets are null, covered buckets without rows are 0")
  void gridAndFill() {
    Instant from = Instant.parse("2026-11-08T00:00:20Z");
    Instant to = Instant.parse("2026-11-08T00:05:00Z");
    Instant now = Instant.parse("2026-11-08T00:03:30Z");
    SourcePlan plan = plan(from, to, now, "1m");
    SeriesGrid grid = new SeriesGrid(plan, now);
    assertThat(grid.size()).isEqualTo(5);
    assertThat(grid.ts().get(0)).isEqualTo(Instant.parse("2026-11-08T00:00:00Z"));
    assertThat(grid.seconds(0)).isEqualTo(40.0);
    assertThat(grid.seconds(1)).isEqualTo(60.0);
    assertThat(grid.seconds(3)).isEqualTo(30.0);
    assertThat(grid.seconds(4)).isZero();
    assertThat(grid.future(3)).isFalse();
    assertThat(grid.future(4)).isTrue();
    assertThat(grid.rangeSeconds()).isEqualTo(190.0);

    TxSeries.Result result =
        new TxSeries.Result(
            Map.of(
                0, new TxSeries.Aggregate(80, 8, 8_000, 0, 50.0, 90.0, 99.0),
                3, new TxSeries.Aggregate(30, 0, 3_000, 0, 60.0, 95.0, 99.0)),
            new TxSeries.Aggregate(110, 8, 11_000, 0, 55.0, 93.0, 99.0));
    int n = grid.size();
    List<Long> count = SeriesGrid.nulls(n);
    List<Double> tps = SeriesGrid.nulls(n);
    List<Double> avg = SeriesGrid.nulls(n);
    List<Double> p95 = SeriesGrid.nulls(n);
    List<Double> p99 = SeriesGrid.nulls(n);
    List<Double> err = SeriesGrid.nulls(n);
    DashboardService.fillTx(grid, result, count, tps, avg, p95, p99, err);
    assertThat(count).containsExactly(80L, 0L, 0L, 30L, null);
    assertThat(tps).containsExactly(2.0, 0.0, 0.0, 1.0, null);
    assertThat(avg).containsExactly(100.0, null, null, 100.0, null);
    assertThat(err).containsExactly(0.1, null, null, 0.0, null);
    assertThat(p95).containsExactly(90.0, null, null, 95.0, null);
    long sum = count.stream().filter(c -> c != null).mapToLong(Long::longValue).sum();
    assertThat(sum).as("Σ count = the range total (D-03)").isEqualTo(result.total().count());
  }

  @Test
  @DisplayName("D-09 a grid of a coarser family maps each bucket to the bucket that contains it")
  void coarserGrid() {
    Instant from = Instant.parse("2026-11-08T00:00:00Z");
    Instant to = Instant.parse("2026-11-08T00:10:00Z");
    Instant now = Instant.parse("2026-11-09T00:00:00Z");
    SeriesGrid fine = new SeriesGrid(plan(from, to, now, "30s"), now);
    SourcePlan coarse = plan(from, to, now, "1m");
    assertThat(fine.indexIn(coarse, 0)).isZero();
    assertThat(fine.indexIn(coarse, 1)).isZero();
    assertThat(fine.indexIn(coarse, 2)).isEqualTo(1);
    assertThat(fine.indexIn(coarse, 19)).isEqualTo(9);
    assertThat(TimeQueries.stepText(Duration.ofSeconds(30))).isEqualTo("30s");
    assertThat(TimeQueries.stepText(Duration.ofMinutes(15))).isEqualTo("15m");
    assertThat(TimeQueries.stepText(Duration.ofHours(6))).isEqualTo("6h");
    assertThat(TimeQueries.stepText(Duration.ofDays(1))).isEqualTo("1d");
  }

  @Test
  @DisplayName(
      "D-23 deltas: relative for tps and p95, absolute for the error rate, null without basis")
  void deltas() {
    assertThat(Values.relative(110.0, 100.0)).isEqualTo(0.1);
    assertThat(Values.relative(1.0, 0.0)).isNull();
    assertThat(Values.relative(null, 1.0)).isNull();
    assertThat(Values.absolute(0.012, 0.010)).isEqualTo(0.002);
    assertThat(Values.ratio(1, 0, 3)).isNull();
    assertThat(Values.round(Double.NaN, 2)).isNull();
    assertThat(
            DashboardService.sumIncrease(
                List.of(
                    new MetricKeys.BucketValue(Instant.EPOCH, "agent_drops", "", null, null, 2.0),
                    new MetricKeys.BucketValue(Instant.EPOCH, "agent_drops", "q", null, null, 3.4),
                    new MetricKeys.BucketValue(Instant.EPOCH, "cpu", "", 1.0, 1.0, 0.0)),
                "agent_drops"))
        .isEqualTo(5L);
    assertThat(DashboardService.sumIncrease(List.of(), "agent_drops")).isNull();
  }

  @Test
  @DisplayName(
      "F-U20 an instance far from the other instances is an outlier (leave-one-out, ADR-0010"
          + " C-177); equal values flag nobody, a missing value is unknown")
  void outliers() {
    List<DashboardViews.Comparison> rows = new ArrayList<>();
    for (int i = 1; i <= 9; i++) {
      rows.add(row(i, 100.0, 0.01, i == 9 ? null : 0.2));
    }
    rows.add(row(10, 1_000.0, 0.01, 0.9));
    List<DashboardViews.Comparison> out = CompareService.withOutliers(rows);
    assertThat(out.get(9).outlier().p95()).isTrue();
    assertThat(out.get(0).outlier().p95()).isFalse();
    assertThat(out.get(0).outlier().errorRate()).as("no spread: nobody is an outlier").isFalse();
    assertThat(out.get(8).outlier().cpu()).as("no value: unknown").isNull();
    assertThat(out.get(9).outlier().cpu()).isTrue();
  }

  private static DashboardViews.Comparison row(int id, Double p95, Double err, Double cpu) {
    return new DashboardViews.Comparison(
        id, "i" + id, "UP", 10, 1.0, 50.0, p95, err, null, null, cpu, null, null, null);
  }
}
