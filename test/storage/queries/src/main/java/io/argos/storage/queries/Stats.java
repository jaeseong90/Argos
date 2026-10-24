/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.Arrays;
import java.util.Collection;

/** Small statistics helpers for measurements. */
public final class Stats {

  private Stats() {}

  /** Median (mean of the two middle values for an even count); NaN when empty. */
  public static double median(double... values) {
    return percentile(values, 0.5);
  }

  /** Median of a collection; NaN when empty. */
  public static double median(Collection<Double> values) {
    return median(values.stream().mapToDouble(Double::doubleValue).toArray());
  }

  /**
   * Percentile with linear interpolation between closest ranks (the R-7 / NumPy default), p in [0,
   * 1]; NaN when empty.
   */
  public static double percentile(double[] values, double p) {
    if (values.length == 0) {
      return Double.NaN;
    }
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    double rank = p * (sorted.length - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
  }

  /** Percentile of a collection. */
  public static double percentile(Collection<Double> values, double p) {
    return percentile(values.stream().mapToDouble(Double::doubleValue).toArray(), p);
  }

  /** Maximum; NaN when empty. */
  public static double max(Collection<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
  }

  /** Mean; NaN when empty. */
  public static double mean(Collection<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
  }

  /** Relative error |estimate - exact| / exact (0 when both are 0, infinity when only exact is). */
  public static double relativeError(double estimate, double exact) {
    if (exact == 0) {
      return estimate == 0 ? 0 : Double.POSITIVE_INFINITY;
    }
    return Math.abs(estimate - exact) / Math.abs(exact);
  }
}
