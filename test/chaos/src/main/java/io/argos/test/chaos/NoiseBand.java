/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * The p99 noise band of a scenario (18 §11.1 "앱 p99 변화가 벤치 신뢰구간 안"). The load generator prints one
 * p99 per report interval (10 s); the intervals of the baseline window give the band:
 *
 * <pre>
 * upper = max(baseline p99) + max(spread, 10 % of median(baseline p99)), spread = max - min
 * </pre>
 *
 * The fault window is judged by the median of its interval p99 values (one interval with a GC pause
 * of the measuring jcmd does not decide it): {@code median(fault p99) <= upper}. The 10 % floor
 * keeps a baseline whose intervals happen to agree within a millisecond from turning ordinary
 * scheduling jitter into a failure. Without at least 3 baseline intervals there is no band.
 *
 * @param baseline the baseline intervals' p99 values (ms)
 */
public record NoiseBand(List<Double> baseline) {

  /** Fewest baseline intervals that make a band. */
  public static final int MIN_INTERVALS = 3;

  /** Floor of the band's width as a share of the baseline median. */
  public static final double MIN_WIDTH_SHARE = 0.10;

  /** Copies; NaN values (intervals without answers) are left out. */
  public NoiseBand {
    List<Double> values = new ArrayList<>();
    for (Double v : baseline) {
      if (v != null && !v.isNaN()) {
        values.add(v);
      }
    }
    baseline = List.copyOf(values);
  }

  /** Whether the baseline has enough intervals. */
  public boolean defined() {
    return baseline.size() >= MIN_INTERVALS;
  }

  /** The upper bound (ms), or empty without a band. */
  public OptionalDouble upper() {
    if (!defined()) {
      return OptionalDouble.empty();
    }
    double max = baseline.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    double min = baseline.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    double width = Math.max(max - min, MIN_WIDTH_SHARE * median(baseline));
    return OptionalDouble.of(max + width);
  }

  /** Whether {@code faultP99} (the median of the fault window's interval p99) is in the band. */
  public boolean contains(double faultP99) {
    OptionalDouble upper = upper();
    return upper.isPresent() && !Double.isNaN(faultP99) && faultP99 <= upper.getAsDouble();
  }

  /** The median of {@code values} (NaN for none). */
  public static double median(List<Double> values) {
    List<Double> sorted =
        new ArrayList<>(values.stream().filter(v -> v != null && !v.isNaN()).sorted().toList());
    if (sorted.isEmpty()) {
      return Double.NaN;
    }
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  /** {@code min..max (n intervals), upper U ms}. */
  public String describe() {
    if (baseline.isEmpty()) {
      return "no baseline intervals";
    }
    double max = baseline.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    double min = baseline.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    return String.format(
        Locale.ROOT,
        "baseline p99 %.1f..%.1f ms (%d intervals), upper %s",
        min,
        max,
        baseline.size(),
        upper().isPresent() ? String.format(Locale.ROOT, "%.1f ms", upper().getAsDouble()) : "-");
  }
}
