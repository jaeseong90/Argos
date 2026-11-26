/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.LatencyBuckets;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The compact slots of the alert windows (C-244 (1)) keep the window values of 12 §15 #7: every
 * value read equals the one computed from scratch over the raw samples of the window, with
 * percentiles from an HdrHistogram (1 ms to 1 h, 2 significant digits) that recorded every sample,
 * which is what the former per-slot histograms merged to.
 */
@Tag("req:DSN-12-07")
class WindowPercentileEquivalenceTest {

  private static final long START = 1_800_000_000_000L;
  private static final int APP = 1;
  private static final long SERVICE = 77;
  private static final long HOST = 555;

  record Tx(long ms, int instance, int elapsed, boolean error, int apdexT) {}

  record Ext(long ms, long count, long errors, long timeouts, List<Integer> buckets, long maxMs) {}

  @Test
  @DisplayName(
      "Compact alert window slots (C-244 (1), no catalogued ID): p50/p95/p99, TPS, error rate,"
          + " Apdex, outgoing call p95 and ratios and batch maximum equal those recomputed from the"
          + " raw samples of 1, 5 and 15-minute windows, including repeated buckets, outliers above"
          + " 1 h and slot reuse after the retention")
  void valuesEqualTheRawSamples() {
    WindowProvider w = new WindowProvider(Duration.ofMinutes(15));
    SplittableRandom random = new SplittableRandom(7);
    List<Tx> txs = new ArrayList<>();
    List<Ext> exts = new ArrayList<>();
    int seconds = 1_500;
    for (int s = 0; s < seconds; s++) {
      int n = random.nextInt(10) == 0 ? 40 + random.nextInt(200) : random.nextInt(4);
      for (int i = 0; i < n; i++) {
        long ms = START + s * 1_000L + random.nextInt(1_000);
        int elapsed =
            switch (random.nextInt(20)) {
              case 0 -> 3_600_000 + random.nextInt(1_000_000);
              case 1 -> 1 + random.nextInt(3);
              case 2 -> 250;
              default -> WindowHeapBudgetTest.elapsed(random);
            };
        Tx tx = new Tx(ms, 1 + random.nextInt(3), elapsed, random.nextInt(25) == 0, 300);
        txs.add(tx);
        w.recordTransaction(APP, tx.instance(), Kind.HTTP, SERVICE, elapsed, tx.error(), 300, ms);
        w.recordTransaction(APP, tx.instance(), Kind.BATCH, SERVICE, elapsed, tx.error(), 0, ms);
      }
      if (s % 7 == 0) {
        List<Integer> buckets = new ArrayList<>();
        long count = 0;
        for (int b = 0; b < LatencyBuckets.COUNT; b++) {
          int c = random.nextInt(3) == 0 ? random.nextInt(1_000) : 0;
          buckets.add(c);
          count += c;
        }
        Ext e = new Ext(START + s * 1_000L, count, count / 9, count / 13, buckets, 70_000);
        exts.add(e);
        w.recordExt(APP, HOST, e.count(), e.errors(), e.timeouts(), buckets, e.maxMs(), e.ms());
      }
      if (s >= 60 && s % 37 == 0) {
        long now = START + s * 1_000L + 999;
        for (Duration window :
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))) {
          check(w, txs, exts, now, window);
        }
      }
    }
  }

  private static void check(
      WindowProvider w, List<Tx> txs, List<Ext> exts, long now, Duration window) {
    long toSec = Math.floorDiv(now, 1000);
    long fromSec = toSec - Math.min(900, window.toSeconds());
    Histogram h = new Histogram(WindowProvider.HIGHEST_MS, WindowProvider.DIGITS);
    long count = 0;
    long errors = 0;
    double satisfied = 0;
    double tolerating = 0;
    int maxMs = 0;
    for (Tx tx : txs) {
      long sec = Math.floorDiv(tx.ms(), 1000);
      if (sec > fromSec && sec <= toSec) {
        count++;
        h.recordValue(WindowProvider.clamp(tx.elapsed()));
        maxMs = Math.max(maxMs, tx.elapsed());
        if (tx.error()) {
          errors++;
        } else if (tx.elapsed() <= tx.apdexT()) {
          satisfied++;
        } else if (tx.elapsed() <= 4L * tx.apdexT()) {
          tolerating++;
        }
      }
    }
    String at = window + " at " + now;
    if (count == 0) {
      return;
    }
    assertThat(value(w, AlertMetric.RESPONSE_TIME_P50, Kind.HTTP, TargetType.SERVICE, now, window))
        .as("p50 " + at)
        .isEqualTo((double) h.getValueAtPercentile(50.0));
    assertThat(value(w, AlertMetric.RESPONSE_TIME_P95, Kind.HTTP, TargetType.APP, now, window))
        .as("p95 " + at)
        .isEqualTo((double) h.getValueAtPercentile(95.0));
    assertThat(value(w, AlertMetric.RESPONSE_TIME_P99, Kind.HTTP, TargetType.SERVICE, now, window))
        .as("p99 " + at)
        .isEqualTo((double) h.getValueAtPercentile(99.0));
    assertThat(value(w, AlertMetric.TPS, Kind.HTTP, TargetType.APP, now, window))
        .as("tps " + at)
        .isEqualTo((double) count / Math.max(1, window.toSeconds()));
    assertThat(value(w, AlertMetric.ERROR_RATE, Kind.HTTP, TargetType.SERVICE, now, window))
        .as("error rate " + at)
        .isEqualTo((double) errors / count);
    assertThat(value(w, AlertMetric.APDEX, Kind.HTTP, TargetType.SERVICE, now, window))
        .as("apdex " + at)
        .isEqualTo((satisfied + tolerating / 2.0) / count);
    assertThat(
            value(
                w, AlertMetric.BATCH_ELAPSED_SECONDS, Kind.BATCH, TargetType.SERVICE, now, window))
        .as("batch max " + at)
        .isEqualTo(maxMs / 1000.0);

    Histogram eh = new Histogram(WindowProvider.HIGHEST_MS, WindowProvider.DIGITS);
    long calls = 0;
    long failures = 0;
    long timeouts = 0;
    for (Ext e : exts) {
      long sec = Math.floorDiv(e.ms(), 1000);
      if (sec > fromSec && sec <= toSec) {
        calls += e.count();
        failures += e.errors();
        timeouts += e.timeouts();
        for (int i = 0; i < e.buckets().size(); i++) {
          long n = e.buckets().get(i);
          if (n > 0) {
            long bound = LatencyBuckets.upperBoundMs(i);
            long v = bound == Long.MAX_VALUE ? Math.max(e.maxMs(), 1) : bound;
            eh.recordValueWithCount(WindowProvider.clamp(v), n);
          }
        }
      }
    }
    if (calls > 0) {
      assertThat(value(w, AlertMetric.EXT_CALL_P95, Kind.HTTP, TargetType.EXT_HOST, now, window))
          .as("ext p95 " + at)
          .isEqualTo((double) eh.getValueAtPercentile(95.0));
      assertThat(
              value(
                  w, AlertMetric.EXT_CALL_ERROR_RATIO, Kind.HTTP, TargetType.EXT_HOST, now, window))
          .as("ext error ratio " + at)
          .isEqualTo((double) failures / calls);
      assertThat(
              value(
                  w,
                  AlertMetric.EXT_CALL_TIMEOUT_RATIO,
                  Kind.HTTP,
                  TargetType.EXT_HOST,
                  now,
                  window))
          .as("ext timeout ratio " + at)
          .isEqualTo((double) timeouts / calls);
    }
  }

  private static Double value(
      WindowProvider w, AlertMetric metric, Kind kind, TargetType type, long now, Duration window) {
    String target =
        switch (type) {
          case SERVICE -> Long.toString(SERVICE);
          case EXT_HOST -> Long.toString(HOST);
          default -> null;
        };
    MetricValue v =
        w.read(new MetricRequest(metric, APP, kind, type, target, window, now, Set.of()));
    return v.value();
  }
}
