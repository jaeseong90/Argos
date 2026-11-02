/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small statistics used by the reports (01 §7.3 items 4 and 6, 18 §7.4). */
final class Stats {
  private Stats() {}

  /** Two-sided 95% Student t quantiles (0.975) for 1..30 degrees of freedom. */
  private static final double[] T975 = {
    Double.NaN,
    12.706,
    4.303,
    3.182,
    2.776,
    2.571,
    2.447,
    2.365,
    2.306,
    2.262,
    2.228,
    2.201,
    2.179,
    2.160,
    2.145,
    2.131,
    2.120,
    2.110,
    2.101,
    2.093,
    2.086,
    2.080,
    2.074,
    2.069,
    2.064,
    2.060,
    2.056,
    2.052,
    2.048,
    2.045,
    2.042
  };

  /** Mean, NaN for no values. */
  static double mean(double[] values) {
    if (values.length == 0) {
      return Double.NaN;
    }
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  /** Sample standard deviation (n - 1), NaN below two values. */
  static double sd(double[] values) {
    if (values.length < 2) {
      return Double.NaN;
    }
    double mean = mean(values);
    double sum = 0;
    for (double v : values) {
      sum += (v - mean) * (v - mean);
    }
    return Math.sqrt(sum / (values.length - 1));
  }

  /** Coefficient of variation sd / mean (01 §7.3 item 4: off runs above 0.02 are INVALID). */
  static double cv(double[] values) {
    return sd(values) / mean(values);
  }

  static double median(double[] values) {
    if (values.length == 0) {
      return Double.NaN;
    }
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int mid = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  }

  static double min(double[] values) {
    return values.length == 0 ? Double.NaN : Arrays.stream(values).min().orElse(Double.NaN);
  }

  static double max(double[] values) {
    return values.length == 0 ? Double.NaN : Arrays.stream(values).max().orElse(Double.NaN);
  }

  /**
   * The 0.975 quantile of Student's t for {@code df} degrees of freedom. Between tabulated values
   * the next lower degree of freedom is used (a slightly wider, conservative interval).
   */
  static double t975(int df) {
    if (df < 1) {
      return Double.NaN;
    }
    if (df < T975.length) {
      return T975[df];
    }
    if (df < 40) {
      return T975[30];
    }
    if (df < 60) {
      return 2.021;
    }
    if (df < 120) {
      return 2.000;
    }
    return df < 1000 ? 1.980 : 1.960;
  }

  /** A mean with its two-sided 95% confidence interval (t distribution, n - 1 df). */
  record Interval(double mean, double low, double high, int n) {
    /**
     * True when the interval contains 0 ("no difference", 01 §7.3 item 6); false when the interval
     * is unknown (fewer than two values).
     */
    boolean containsZero() {
      return low <= 0 && high >= 0;
    }
  }

  static Interval meanInterval(double[] values) {
    double mean = mean(values);
    if (values.length < 2) {
      return new Interval(mean, Double.NaN, Double.NaN, values.length);
    }
    double half = t975(values.length - 1) * sd(values) / Math.sqrt(values.length);
    return new Interval(mean, mean - half, mean + half, values.length);
  }

  /** {@code on[i] - off[i]} for the first min(on, off) pairs (round i: off then on). */
  static double[] pairedDifferences(double[] on, double[] off) {
    int n = Math.min(on.length, off.length);
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      d[i] = on[i] - off[i];
    }
    return d;
  }

  /** Linear interpolation of {@code y} at {@code x} (t ascending), clamped to the ends. */
  static double interpolate(double[] t, double[] y, double x) {
    if (t.length == 0) {
      return Double.NaN;
    }
    if (x <= t[0]) {
      return y[0];
    }
    for (int i = 1; i < t.length; i++) {
      if (x <= t[i]) {
        double span = t[i] - t[i - 1];
        return span <= 0 ? y[i] : y[i - 1] + (y[i] - y[i - 1]) * (x - t[i - 1]) / span;
      }
    }
    return y[y.length - 1];
  }

  /**
   * Average rates of a cumulative counter (CPU seconds) over windows of {@code width} seconds that
   * start every {@code step} seconds from 0 while the window fits before the last sample (18 §7.4:
   * peak = largest 1-minute average among windows started every 10 s).
   */
  static double[] windowRates(double[] t, double[] cumulative, double width, double step) {
    List<Double> rates = new ArrayList<>();
    if (t.length < 2) {
      return new double[0];
    }
    double end = t[t.length - 1];
    for (double start = 0; start + width <= end + 1e-9; start += step) {
      double a = interpolate(t, cumulative, start);
      double b = interpolate(t, cumulative, start + width);
      rates.add((b - a) / width);
    }
    return rates.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** {@code values} as a primitive array. */
  static double[] toArray(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }
}
