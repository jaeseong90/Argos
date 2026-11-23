/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.MetricPoint.Series;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The counters of the {@code counter} and {@code overview} topics (12 §3.7 "실시간 TPS"): 1-second
 * buckets by {@code ts}, not by arrival, closed on the 5-second grid after a grace period; points
 * of closed seconds are excluded.
 */
@Tag("req:F-S03")
class CounterAggregatorTest {

  private static final int APP = 3;

  private static BusPoint p(long tsMs, int elapsed, boolean error, int kind) {
    return new BusPoint(1, tsMs, elapsed, error, 12, 17, kind);
  }

  @Test
  @DisplayName(
      "a window holds the points whose ts is in [end - 5 s, end), whenever they arrived before it"
          + " closed")
  void bucketsByTs() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500); // first window [T0, T0 + 5 s)
    List<BusPoint> points = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      points.add(p(T0 + 4_999 - i, 100 * (i + 1), i == 0, 1));
    }
    // Arrives at T0 + 6.9 s, after the window's end but within the grace period.
    c.add(APP, points, T0 + 6_900);
    // ts in the next window although it arrives now.
    c.add(APP, List.of(p(T0 + 5_000, 7, false, 1)), T0 + 6_900);
    assertThat(c.close(T0 + 6_999)).isNull(); // grace: closes at end + 2 s
    CounterAggregator.Window w = c.close(T0 + 7_000);
    assertThat(w.startMs()).isEqualTo(T0);
    assertThat(w.endMs()).isEqualTo(T0 + 5_000);
    CounterAggregator.Stats s = w.stats(APP, TxKind.HTTP);
    assertThat(s.count()).isEqualTo(10);
    assertThat(s.tps()).isEqualTo(2.0);
    assertThat(s.avgMs()).isEqualTo(550.0);
    assertThat(s.p95Ms()).isEqualTo(1_000.0); // nearest rank: ceil(0.95 * 10) = 10th
    assertThat(s.p99Ms()).isEqualTo(1_000.0);
    assertThat(s.errorRate()).isEqualTo(0.1);
    assertThat(c.close(T0 + 7_100)).isNull(); // nothing new to close

    CounterAggregator.Window next = c.close(T0 + 12_000);
    assertThat(next.startMs()).isEqualTo(T0 + 5_000);
    assertThat(next.stats(APP, TxKind.HTTP).count()).isEqualTo(1);
  }

  @Test
  @Tag("req:D-15")
  @DisplayName("a point of a closed second is excluded from the counters and counted as late")
  void latePointsExcluded() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    assertThat(c.close(T0 + 7_000)).isNotNull();
    int excluded =
        c.add(APP, List.of(p(T0 + 4_999, 5, false, 1), p(T0 + 5_000, 5, false, 1)), T0 + 7_100);
    assertThat(excluded).isEqualTo(1);
    assertThat(c.late()).isEqualTo(1);
    CounterAggregator.Window w = c.close(T0 + 12_000);
    assertThat(w.stats(APP, TxKind.HTTP).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("a point more than 5 s ahead of the server clock is excluded")
  void futurePointsExcluded() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    int excluded =
        c.add(
            APP,
            List.of(p(T0 + 3_000 + 6_000, 5, false, 1), p(T0 + 4_000, 5, false, 1)),
            T0 + 3_000);
    assertThat(excluded).isEqualTo(1);
    assertThat(c.future()).isEqualTo(1);
    assertThat(c.close(T0 + 7_000).stats(APP, TxKind.HTTP).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("kinds are counted apart, ALL adds them; an empty window is 0 TPS, rest null")
  void kindsAndEmpty() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    c.add(
        APP,
        List.of(
            p(T0 + 1_000, 10, false, 1), p(T0 + 1_000, 30, true, 2), p(T0 + 2_000, 20, false, 3)),
        T0 + 3_000);
    CounterAggregator.Window w = c.close(T0 + 7_000);
    assertThat(w.stats(APP, TxKind.HTTP).count()).isEqualTo(1);
    assertThat(w.stats(APP, TxKind.BATCH).errorRate()).isEqualTo(1.0);
    assertThat(w.stats(APP, TxKind.MESSAGE).avgMs()).isEqualTo(20.0);
    CounterAggregator.Stats all = w.stats(APP, TxKind.ALL);
    assertThat(all.count()).isEqualTo(3);
    assertThat(all.tps()).isEqualTo(0.6);
    assertThat(all.p95Ms()).isEqualTo(30.0);
    assertThat(all.errorRate()).isEqualTo(0.333333);

    CounterAggregator.Stats empty = w.stats(99, TxKind.HTTP);
    assertThat(empty.count()).isZero();
    assertThat(empty.tps()).isEqualTo(0.0);
    assertThat(empty.avgMs()).isNull();
    assertThat(empty.p95Ms()).isNull();
    assertThat(empty.errorRate()).isNull();
  }

  @Test
  @DisplayName(
      "after a clock jump only the newest window closes; older seconds are discarded, not merged")
  void clockJump() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    c.add(APP, List.of(p(T0 + 1_000, 5, false, 1)), T0 + 3_000);
    CounterAggregator.Window w = c.close(T0 + 300_000);
    assertThat(w.endMs()).isEqualTo(T0 + 295_000);
    assertThat(w.stats(APP, TxKind.HTTP).count()).isZero();
    assertThat(w.apps()).isEmpty();
  }

  @Test
  @DisplayName("percentiles use at most 20,000 samples per second while counts stay exact")
  void sampleCap() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    List<BusPoint> points = new ArrayList<>();
    for (int i = 0; i < 50_000; i++) {
      points.add(p(T0 + 1_000 + (i % 1000), i < 49_000 ? 10 : 5_000, false, 1));
    }
    c.add(APP, points, T0 + 3_000);
    CounterAggregator.Stats s = c.close(T0 + 7_000).stats(APP, TxKind.HTTP);
    assertThat(s.count()).isEqualTo(50_000);
    assertThat(s.tps()).isEqualTo(10_000.0);
    assertThat(s.avgMs()).isEqualTo((49_000 * 10 + 1_000 * 5_000) / 50_000.0);
    assertThat(s.p95Ms()).isEqualTo(10.0);
    assertThat(s.p99Ms()).isIn(10.0, 5_000.0);
  }

  private static MetricPoint m(Series series, long tsMs, double value) {
    return new MetricPoint(series, tsMs, value);
  }

  @Test
  @Tag("req:D-23")
  @DisplayName(
      "T-SV-020f counter JVM values over the 5 s window by ts: heap = sum over the instances of"
          + " their average, gc_pause_ms = summed increase x 1000, cpu = mean over the instances"
          + " (the REST series definitions); an application without samples has null values")
  void jvmValues() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500); // first window [T0, T0 + 5 s)
    // Instance 12: two heap samples (avg 300 MB), cpu 0.2 and 0.4 (avg 0.3), two GC increases.
    c.addMetrics(
        APP,
        12,
        List.of(
            m(Series.HEAP_USED, T0, 200_000_000),
            m(Series.HEAP_USED, T0 + 4_000, 400_000_000),
            m(Series.CPU, T0, 0.2),
            m(Series.CPU, T0 + 4_000, 0.4),
            m(Series.GC_PAUSE, T0, 0.0125),
            m(Series.GC_PAUSE, T0 + 4_000, 0.0005)),
        T0 + 4_500);
    // Instance 13: heap 100 MB, cpu 0.6, no GC sample.
    c.addMetrics(
        APP, 13, List.of(m(Series.HEAP_USED, T0, 100_000_000), m(Series.CPU, T0, 0.6)), T0 + 4_500);
    // Next window: not part of this one.
    c.addMetrics(APP, 12, List.of(m(Series.HEAP_USED, T0 + 5_000, 1)), T0 + 5_500);

    CounterAggregator.Window w = c.close(T0 + 7_000);
    CounterAggregator.Jvm j = w.jvm(APP);
    assertThat(j.heapUsed()).isEqualTo(400_000_000.0); // 300 MB + 100 MB
    assertThat(j.gcPauseMs()).isEqualTo(13.0); // (0.0125 + 0.0005) s
    assertThat(j.cpu()).isEqualTo(0.45); // (0.3 + 0.6) / 2
    // JVM values without transactions: the transaction values stay empty.
    assertThat(w.stats(APP, TxKind.ALL).count()).isZero();
    assertThat(w.jvm(99)).isEqualTo(CounterAggregator.Jvm.EMPTY);

    CounterAggregator.Window next = c.close(T0 + 12_000);
    assertThat(next.jvm(APP)).isEqualTo(new CounterAggregator.Jvm(1.0, null, null));
    CounterAggregator.Window empty = c.close(T0 + 17_000);
    assertThat(empty.jvm(APP)).isEqualTo(CounterAggregator.Jvm.EMPTY);
  }

  @Test
  @Tag("req:D-23")
  @DisplayName(
      "T-SV-020f a GC series with a 0 increase is 0 ms, not null; a series without samples is null")
  void jvmZeroIsNotMissing() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    c.addMetrics(APP, 12, List.of(m(Series.GC_PAUSE, T0, 0.0)), T0 + 3_000);
    CounterAggregator.Jvm j = c.close(T0 + 7_000).jvm(APP);
    assertThat(j.gcPauseMs()).isEqualTo(0.0);
    assertThat(j.heapUsed()).isNull();
    assertThat(j.cpu()).isNull();
  }

  @Test
  @Tag("req:D-15")
  @DisplayName(
      "T-SV-020f JVM samples of a closed second or more than 5 s ahead are excluded and counted")
  void jvmLateAndFutureExcluded() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    assertThat(c.close(T0 + 7_000)).isNotNull(); // [T0, T0 + 5 s) closed
    int excluded =
        c.addMetrics(
            APP,
            12,
            List.of(
                m(Series.HEAP_USED, T0 + 4_999, 1), // closed second
                m(Series.HEAP_USED, T0 + 7_100 + 6_000, 2), // ahead
                m(Series.HEAP_USED, T0 + 5_000, 3)),
            T0 + 7_100);
    assertThat(excluded).isEqualTo(2);
    assertThat(c.jvmExcluded()).isEqualTo(2);
    assertThat(c.late()).as("transaction counters unchanged").isZero();
    assertThat(c.close(T0 + 12_000).jvm(APP).heapUsed()).isEqualTo(3.0);
  }

  @Test
  @DisplayName(
      "T-SV-020f at most 1,000 instances per second and application keep JVM samples; the rest"
          + " are excluded")
  void jvmInstanceBound() {
    CounterAggregator c = new CounterAggregator(T0 + 2_500);
    int excluded = 0;
    for (int i = 0; i < CounterAggregator.MAX_JVM_INSTANCES + 5; i++) {
      excluded += c.addMetrics(APP, i, List.of(m(Series.HEAP_USED, T0 + 1_000, 1)), T0 + 3_000);
    }
    // An instance already kept still adds in that second.
    excluded += c.addMetrics(APP, 0, List.of(m(Series.HEAP_USED, T0 + 1_000, 3)), T0 + 3_000);
    assertThat(excluded).isEqualTo(5);
    // 999 instances at 1 + instance 0 at (1 + 3) / 2.
    assertThat(c.close(T0 + 7_000).jvm(APP).heapUsed())
        .isEqualTo(CounterAggregator.MAX_JVM_INSTANCES - 1 + 2.0);
  }

  @Test
  @DisplayName(
      "T-SV-020f metric names map to the push series exactly as the dashboard keys match them")
  void seriesNames() {
    assertThat(Series.of("jvm_memory_used_bytes{area=\"heap\"}")).isEqualTo(Series.HEAP_USED);
    assertThat(Series.of("jvm_memory_used_bytes{area=\"nonheap\"}")).isNull();
    assertThat(Series.of("jvm_memory_max_bytes{area=\"heap\"}")).isNull();
    assertThat(Series.of("jvm_gc_pause_seconds_total{gc=\"G1 Young Generation\"}"))
        .isEqualTo(Series.GC_PAUSE);
    assertThat(Series.of("process_cpu_usage_ratio")).isEqualTo(Series.CPU);
    assertThat(Series.of("process_cpu_usage")).isEqualTo(Series.CPU);
    assertThat(Series.of("system_cpu_usage_ratio")).isNull();
    assertThat(Series.of("argos_active_transactions")).isNull();
  }
}
