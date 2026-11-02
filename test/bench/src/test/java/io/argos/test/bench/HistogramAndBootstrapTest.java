/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HDR re-aggregation and the bootstrap of 18 §7.2. */
@Tag("req:DSN-18-07")
class HistogramAndBootstrapTest {

  @Test
  @DisplayName("bucket boundaries round-trip and stay within 1/1024 of the value")
  void bucketsArePrecise() {
    SplittableRandom random = new SplittableRandom(7);
    for (int i = 0; i < 100_000; i++) {
      long v = (long) Math.exp(random.nextDouble() * Math.log(3.6e9)); // 1 µs .. 1 h
      int index = LatencyHistogram.index(v);
      assertThat(LatencyHistogram.lowest(index)).isLessThanOrEqualTo(v);
      assertThat(LatencyHistogram.lowest(index) + LatencyHistogram.width(index)).isGreaterThan(v);
      assertThat(LatencyHistogram.index(LatencyHistogram.lowest(index))).isEqualTo(index);
      double rep = LatencyHistogram.representative(index);
      assertThat(Math.abs(rep - v)).isLessThanOrEqualTo(Math.max(0.5, v / 2048.0));
    }
    assertThat(LatencyHistogram.index(0)).isZero();
    assertThat(LatencyHistogram.index(-5)).isZero();
    assertThat(LatencyHistogram.index(2047)).isEqualTo(2047);
    assertThat(LatencyHistogram.index(Long.MAX_VALUE)).isEqualTo(LatencyHistogram.BUCKETS - 1);
  }

  @Test
  @DisplayName("quantiles follow the nearest rank within the bucket precision")
  void quantilesMatchExactNearestRank() {
    SplittableRandom random = new SplittableRandom(11);
    double[] values = new double[50_001];
    LatencyHistogram h = new LatencyHistogram();
    for (int i = 0; i < values.length; i++) {
      values[i] = 0.3 + random.nextDouble() * random.nextDouble() * 400; // ms, skewed
      h.recordMillis(values[i]);
    }
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    for (double q : List.of(0.5, 0.95, 0.99, 0.999)) {
      double exact = sorted[(int) LatencyHistogram.rank(q, sorted.length) - 1];
      assertThat(h.quantileMillis(q)).isCloseTo(exact, within(exact / 1000 + 0.001));
    }
    assertThat(h.count()).isEqualTo(values.length);
    assertThat(h.maxMillis()).isCloseTo(sorted[sorted.length - 1], within(0.001));
    assertThat(LatencyHistogram.rank(0.99, 600_000)).isEqualTo(594_000);
    assertThat(LatencyHistogram.rank(0.5, 1)).isEqualTo(1);
    assertThat(new LatencyHistogram().quantileMillis(0.5)).isNaN();
  }

  @Test
  @DisplayName("histograms merge and survive their JSON form")
  void mergeAndJsonRoundTrip() {
    LatencyHistogram a = new LatencyHistogram();
    LatencyHistogram b = new LatencyHistogram();
    for (long v = 100; v < 5_000; v += 7) {
      a.recordMicros(v);
      b.recordMicros(v * 50);
    }
    LatencyHistogram merged = new LatencyHistogram();
    merged.add(a);
    merged.add(b);
    assertThat(merged.count()).isEqualTo(a.count() + b.count());
    LatencyHistogram copy = LatencyHistogram.fromJson(Json.parse(Json.write(merged.toJson())));
    assertThat(copy.count()).isEqualTo(merged.count());
    for (double q : List.of(0.1, 0.5, 0.9, 0.99)) {
      assertThat(copy.quantileMillis(q)).isEqualTo(merged.quantileMillis(q));
    }
    assertThat(copy.maxMillis()).isEqualTo(merged.maxMillis());
    assertThat(merged.cdf(50)).hasSize(50);
    assertThat(merged.cdf(50).get(49)[1]).isEqualTo(1.0);
  }

  private static LatencyHistogram uniform(long from, long to) {
    LatencyHistogram h = new LatencyHistogram();
    for (long v = from; v <= to; v++) {
      h.recordMicros(v);
    }
    return h;
  }

  @Test
  @DisplayName("bootstrap intervals are deterministic for a fixed seed")
  void bootstrapIsDeterministic() {
    LatencyHistogram h = uniform(1, 100_000);
    Bootstrap.Interval a = Bootstrap.quantile(h, 0.99, 1000, 42);
    Bootstrap.Interval b = Bootstrap.quantile(h, 0.99, 1000, 42);
    assertThat(b).isEqualTo(a);
    // The replicate sequence is a function of the seed: equal for 42 twice, different for 43.
    double[] first = new double[200];
    double[] second = new double[200];
    double[] other = new double[200];
    SplittableRandom r1 = new SplittableRandom(42);
    SplittableRandom r2 = new SplittableRandom(42);
    SplittableRandom r3 = new SplittableRandom(43);
    LatencyHistogram fine = uniform(1, 2_000); // exact 1 µs buckets
    for (int i = 0; i < first.length; i++) {
      first[i] = Bootstrap.replicate(fine, 0.5, r1);
      second[i] = Bootstrap.replicate(fine, 0.5, r2);
      other[i] = Bootstrap.replicate(fine, 0.5, r3);
    }
    assertThat(second).containsExactly(first);
    assertThat(Arrays.equals(other, first)).isFalse();
    LatencyHistogram on = uniform(1_001, 101_000);
    assertThat(Bootstrap.meanQuantileDifference(List.of(on), List.of(h), 0.99, 1000, 42))
        .isEqualTo(Bootstrap.meanQuantileDifference(List.of(on), List.of(h), 0.99, 1000, 42));
  }

  @Test
  @DisplayName("bootstrap interval of the median has the binomial width and contains the median")
  void bootstrapIntervalWidth() {
    // n = 100,000 values 1..100,000 µs: rank sd sqrt(n p (1-p)) = 158, so the 95% interval of the
    // median is about +-310 µs around 50 ms.
    Bootstrap.Interval i = Bootstrap.quantile(uniform(1, 100_000), 0.5, 1000, 42);
    assertThat(i.estimate()).isCloseTo(50.0, within(0.001));
    assertThat(i.low()).isLessThan(50.0);
    assertThat(i.high()).isGreaterThan(50.0);
    assertThat(i.high() - i.low()).isBetween(0.45, 0.80);
  }

  @Test
  @DisplayName("the Beta shortcut gives the same bootstrap distribution as resampling")
  void betaShortcutMatchesResampling() {
    // 40 values; brute force resampling vs one Beta draw per replicate, 40,000 replicates each.
    long[] data = new long[40];
    LatencyHistogram h = new LatencyHistogram();
    for (int i = 0; i < data.length; i++) {
      data[i] = 1000L * (i + 1) * (i + 1); // distinct, spread out (exact buckets not needed)
      h.recordMicros(data[i]);
    }
    long[] sorted = data.clone();
    Arrays.sort(sorted);
    int reps = 40_000;
    SplittableRandom brute = new SplittableRandom(1);
    SplittableRandom shortcut = new SplittableRandom(2);
    double q = 0.9;
    int k = (int) LatencyHistogram.rank(q, data.length);
    double[] byResampling = new double[reps];
    double[] byBeta = new double[reps];
    long[] sample = new long[data.length];
    for (int r = 0; r < reps; r++) {
      for (int j = 0; j < sample.length; j++) {
        sample[j] = data[brute.nextInt(data.length)];
      }
      Arrays.sort(sample);
      byResampling[r] =
          LatencyHistogram.representative(LatencyHistogram.index(sample[k - 1])) / 1000;
      byBeta[r] = Bootstrap.replicate(h, q, shortcut);
    }
    assertThat(Stats.mean(byBeta))
        .isCloseTo(Stats.mean(byResampling), within(0.01 * Stats.mean(byResampling)));
    assertThat(Stats.sd(byBeta))
        .isCloseTo(Stats.sd(byResampling), within(0.03 * Stats.sd(byResampling)));
    Arrays.sort(byBeta);
    Arrays.sort(byResampling);
    for (double p : List.of(0.025, 0.5, 0.975)) {
      // percentiles land on the same order statistic of the data
      assertThat(Bootstrap.nearestRank(byBeta, p))
          .isEqualTo(Bootstrap.nearestRank(byResampling, p));
    }
  }

  @Test
  @DisplayName("difference interval contains 0 for equal windows and excludes it for a shift")
  void differenceInterval() {
    LatencyHistogram off = uniform(1, 50_000);
    Bootstrap.Interval same =
        Bootstrap.meanQuantileDifference(
            List.of(uniform(1, 50_000), uniform(1, 50_000)),
            List.of(off, uniform(1, 50_000)),
            0.99,
            1000,
            42);
    assertThat(same.estimate()).isZero();
    assertThat(same.containsZero()).isTrue();
    Bootstrap.Interval shifted =
        Bootstrap.meanQuantileDifference(
            List.of(uniform(2_001, 52_000)), List.of(off), 0.99, 1000, 42);
    // 2 ms within the bucket precision at 50 ms (buckets of 32 µs: +-0.05% per value)
    assertThat(shifted.estimate()).isCloseTo(2.0, within(0.04));
    assertThat(shifted.containsZero()).isFalse();
    assertThat(shifted.low()).isGreaterThan(1.5);
    assertThat(shifted.high()).isLessThan(2.5);
    assertThat(Bootstrap.meanQuantileDifference(List.of(), List.of(off), 0.99, 1000, 42).estimate())
        .isNaN();
  }

  @Test
  @DisplayName("Gamma and Beta draws have the right means")
  void gammaAndBetaMeans() {
    SplittableRandom random = new SplittableRandom(5);
    double[] g = new double[20_000];
    double[] b = new double[20_000];
    for (int i = 0; i < g.length; i++) {
      g[i] = Bootstrap.gamma(3.5, random);
      b[i] = Bootstrap.beta(594_000, 6_001, random);
    }
    assertThat(Stats.mean(g)).isCloseTo(3.5, within(0.05));
    assertThat(Stats.mean(b)).isCloseTo(594_000.0 / 600_001, within(1e-5));
  }
}
