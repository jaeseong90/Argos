/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Percentile bootstrap of latency quantiles (18 §7.2: 95% confidence interval from 1,000 resamples;
 * 01 §7.3 item 6: an interval that contains 0 means "no difference").
 *
 * <p>A resample of {@code n} requests drawn with replacement has as its nearest-rank quantile the
 * original order statistic X(J), where J is the k-th smallest of n uniform draws from 1..n (k =
 * ceil(q n)). Drawing the n indices as ceil(n U) with U uniform on (0, 1) makes J = ceil(n U(k)),
 * and the k-th order statistic U(k) of n uniforms is Beta(k, n - k + 1) distributed. Each replicate
 * therefore needs one Beta draw instead of n index draws: the same bootstrap distribution, exactly,
 * at O(1) cost per replicate (600,000 requests x 1,000 replicates would otherwise be 6e8 draws per
 * run). Deterministic for a given seed ({@link SplittableRandom}; Gamma by Marsaglia and Tsang,
 * normals by Box-Muller).
 */
final class Bootstrap {
  private Bootstrap() {}

  /** Replicates per interval (18 §7.2). */
  static final int REPLICATES = 1000;

  /** Default seed (18 §15: generators use a fixed seed, 42 by default). */
  static final long DEFAULT_SEED = 42;

  /** A point estimate with its two-sided 95% percentile interval. */
  record Interval(double estimate, double low, double high, int replicates, long seed) {
    /** True when the interval contains 0; false when it is unknown (NaN). */
    boolean containsZero() {
      return low <= 0 && high >= 0;
    }
  }

  /** The 95% interval of quantile {@code q} (ms) of one histogram. */
  static Interval quantile(LatencyHistogram h, double q, int replicates, long seed) {
    double estimate = h.quantileMillis(q);
    if (h.count() == 0) {
      return new Interval(Double.NaN, Double.NaN, Double.NaN, replicates, seed);
    }
    SplittableRandom random = new SplittableRandom(seed);
    double[] values = new double[replicates];
    for (int i = 0; i < replicates; i++) {
      values[i] = replicate(h, q, random);
    }
    return interval(estimate, values, seed);
  }

  /**
   * The 95% interval of (mean over {@code on} runs of their quantile q) minus (the same over {@code
   * off} runs), in ms: per replicate each run is resampled on its own (requests within a run), so
   * the interval reflects the request-level sampling noise of the windows. The round-to-round
   * variation is the Student t interval over the paired rounds ({@link Stats#meanInterval}).
   */
  static Interval meanQuantileDifference(
      List<LatencyHistogram> on, List<LatencyHistogram> off, double q, int replicates, long seed) {
    double estimate = meanQuantile(on, q) - meanQuantile(off, q);
    if (on.isEmpty() || off.isEmpty() || Double.isNaN(estimate)) {
      return new Interval(Double.NaN, Double.NaN, Double.NaN, replicates, seed);
    }
    SplittableRandom random = new SplittableRandom(seed);
    double[] values = new double[replicates];
    for (int i = 0; i < replicates; i++) {
      double a = 0;
      for (LatencyHistogram h : on) {
        a += replicate(h, q, random);
      }
      double b = 0;
      for (LatencyHistogram h : off) {
        b += replicate(h, q, random);
      }
      values[i] = a / on.size() - b / off.size();
    }
    return interval(estimate, values, seed);
  }

  static double meanQuantile(List<LatencyHistogram> runs, double q) {
    if (runs.isEmpty()) {
      return Double.NaN;
    }
    double sum = 0;
    for (LatencyHistogram h : runs) {
      sum += h.quantileMillis(q);
    }
    return sum / runs.size();
  }

  /** One bootstrap replicate of quantile {@code q} (ms) of {@code h}. */
  static double replicate(LatencyHistogram h, double q, SplittableRandom random) {
    long n = h.count();
    long k = LatencyHistogram.rank(q, n);
    double u = beta((double) k, (double) (n - k + 1), random);
    long j = (long) Math.ceil(n * u);
    return h.valueAtRankMicros(Math.min(Math.max(j, 1), n)) / 1000.0;
  }

  private static Interval interval(double estimate, double[] values, long seed) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    return new Interval(
        estimate, nearestRank(sorted, 0.025), nearestRank(sorted, 0.975), values.length, seed);
  }

  /** Nearest-rank percentile of sorted values: the value at rank ceil(p * n). */
  static double nearestRank(double[] sorted, double p) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    int rank = (int) LatencyHistogram.rank(p, sorted.length);
    return sorted[rank - 1];
  }

  /** Beta(a, b) for a, b >= 1 as X / (X + Y) with X ~ Gamma(a), Y ~ Gamma(b). */
  static double beta(double a, double b, SplittableRandom random) {
    double x = gamma(a, random);
    double y = gamma(b, random);
    return x / (x + y);
  }

  /** Gamma(shape, 1) for shape >= 1 (Marsaglia and Tsang 2000). */
  static double gamma(double shape, SplittableRandom random) {
    if (shape < 1) {
      throw new IllegalArgumentException("shape " + shape + " < 1");
    }
    double d = shape - 1.0 / 3;
    double c = 1 / Math.sqrt(9 * d);
    while (true) {
      double x = normal(random);
      double v = 1 + c * x;
      if (v <= 0) {
        continue;
      }
      v = v * v * v;
      double u = random.nextDouble();
      if (u < 1 - 0.0331 * x * x * x * x) {
        return d * v;
      }
      if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
        return d * v;
      }
    }
  }

  /** Standard normal by Box-Muller (one value per call; deterministic per seed). */
  static double normal(SplittableRandom random) {
    double u1;
    do {
      u1 = random.nextDouble();
    } while (u1 <= 0);
    double u2 = random.nextDouble();
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }
}
