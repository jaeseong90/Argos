/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

  @Test
  @DisplayName("Below 128 us every value has its own bucket")
  void smallValuesAreExact() {
    for (long v = 0; v < 128; v++) {
      assertThat(LatencyHistogram.upperBound(LatencyHistogram.index(v))).isEqualTo(v);
    }
  }

  @Test
  @DisplayName("Bucket bounds: never below the value, at most 1/64 above it")
  void relativeErrorIsBounded() {
    SplittableRandom random = new SplittableRandom(42);
    for (int i = 0; i < 200_000; i++) {
      long v = random.nextLong(1, LatencyHistogram.MAX_MICROS);
      long bound = LatencyHistogram.upperBound(LatencyHistogram.index(v));
      assertThat(bound).isGreaterThanOrEqualTo(v);
      assertThat((double) (bound - v) / v).isLessThanOrEqualTo(1.0 / 64);
    }
    // Bucket indexes are monotonic and the last bucket holds the largest value.
    int previous = -1;
    for (long v = 0; v < 5_000_000; v += 997) {
      int index = LatencyHistogram.index(v);
      assertThat(index).isGreaterThanOrEqualTo(previous);
      previous = index;
    }
    assertThat(LatencyHistogram.index(LatencyHistogram.MAX_MICROS))
        .isEqualTo(LatencyHistogram.BUCKETS - 1);
  }

  @Test
  @DisplayName("Percentiles of 1..1000 ms within the bucket error; mean and max exact")
  void percentiles() {
    LatencyHistogram h = new LatencyHistogram();
    for (long ms = 1; ms <= 1000; ms++) {
      h.recordNanos(ms * 1_000_000L);
    }
    LatencyHistogram.Snapshot s = h.snapshot();
    assertThat(s.count()).isEqualTo(1000);
    assertThat(s.percentileMillis(50)).isCloseTo(500, within(500 / 64.0));
    assertThat(s.percentileMillis(95)).isCloseTo(950, within(950 / 64.0));
    assertThat(s.percentileMillis(99)).isCloseTo(990, within(990 / 64.0));
    assertThat(s.percentileMillis(99.9)).isCloseTo(999, within(999 / 64.0));
    assertThat(s.percentileMillis(100)).isEqualTo(1000.0);
    assertThat(s.meanMillis()).isEqualTo(500.5);
    assertThat(s.maxMillis()).isEqualTo(1000.0);
  }

  @Test
  @DisplayName("Empty: NaN; negative and huge values are clamped")
  void edges() {
    LatencyHistogram h = new LatencyHistogram();
    assertThat(h.snapshot().percentileMillis(50)).isNaN();
    assertThat(h.snapshot().meanMillis()).isNaN();
    h.recordNanos(-5);
    h.recordMicros(Long.MAX_VALUE);
    LatencyHistogram.Snapshot s = h.snapshot();
    assertThat(s.count()).isEqualTo(2);
    assertThat(s.percentileMillis(50)).isZero();
    assertThat(s.maxMillis()).isEqualTo(LatencyHistogram.MAX_MICROS / 1000.0);
  }

  @Test
  @DisplayName("minus() gives the interval: counts and percentiles of the later values only")
  void interval() {
    LatencyHistogram h = new LatencyHistogram();
    for (int i = 0; i < 100; i++) {
      h.recordMicros(100);
    }
    LatencyHistogram.Snapshot before = h.snapshot();
    for (int i = 0; i < 10; i++) {
      h.recordMicros(5_000);
    }
    LatencyHistogram.Snapshot interval = h.snapshot().minus(before);
    assertThat(interval.count()).isEqualTo(10);
    assertThat(interval.percentileMillis(50)).isCloseTo(5.0, within(5.0 / 64));
    assertThat(interval.meanMillis()).isEqualTo(5.0);
    assertThat(LatencyHistogram.Snapshot.empty().minus(LatencyHistogram.Snapshot.empty()).count())
        .isZero();
  }
}
