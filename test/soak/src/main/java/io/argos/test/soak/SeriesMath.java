/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.util.List;

/**
 * The arithmetic of the soak verdicts (18 §11.2): the least-squares slope of a series per hour
 * ("72점 선형회귀 기울기"), growth and flatness against the first point, and the noise band of a baseline
 * (mean and sample standard deviation).
 */
public final class SeriesMath {

  /** One point of a series: seconds since the start of the measured window and a value. */
  public record Point(double seconds, double value) {}

  /** The relative floor of the noise band: 5% of the baseline mean. */
  static final double MIN_RELATIVE_NOISE = 0.05;

  private SeriesMath() {}

  /**
   * The least-squares slope of {@code points} in value units per hour; NaN with fewer than two
   * points or when every point has the same time.
   */
  public static double slopePerHour(List<Point> points) {
    int n = points.size();
    if (n < 2) {
      return Double.NaN;
    }
    double mx = 0;
    double my = 0;
    for (Point p : points) {
      mx += p.seconds();
      my += p.value();
    }
    mx /= n;
    my /= n;
    double sxy = 0;
    double sxx = 0;
    for (Point p : points) {
      double dx = p.seconds() - mx;
      sxy += dx * (p.value() - my);
      sxx += dx * dx;
    }
    return sxx == 0 ? Double.NaN : sxy / sxx * 3600.0;
  }

  /** The largest value; NaN for an empty series. */
  public static double max(List<Point> points) {
    return points.stream().mapToDouble(Point::value).max().orElse(Double.NaN);
  }

  /** The largest value minus the first; NaN for an empty series. */
  public static double growth(List<Point> points) {
    return points.isEmpty() ? Double.NaN : max(points) - points.get(0).value();
  }

  /** The largest absolute difference to the first value; NaN for an empty series. */
  public static double maxDeviation(List<Point> points) {
    if (points.isEmpty()) {
      return Double.NaN;
    }
    double first = points.get(0).value();
    double d = 0;
    for (Point p : points) {
      d = Math.max(d, Math.abs(p.value() - first));
    }
    return d;
  }

  /** Whether every value stays within {@code tolerance} of the first (non-empty series). */
  public static boolean flat(List<Point> points, double tolerance) {
    return !points.isEmpty() && maxDeviation(points) <= tolerance;
  }

  /** The mean; NaN for no values. */
  public static double mean(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
  }

  /** The sample standard deviation (n - 1); 0 for a single value, NaN for none. */
  public static double sampleStdDev(List<Double> values) {
    int n = values.size();
    if (n == 0) {
      return Double.NaN;
    }
    if (n == 1) {
      return 0;
    }
    double m = mean(values);
    double s = 0;
    for (double v : values) {
      s += (v - m) * (v - m);
    }
    return Math.sqrt(s / (n - 1));
  }

  /**
   * The noise of a baseline: two sample standard deviations (about 95% of single points of a stable
   * series), at least {@value #MIN_RELATIVE_NOISE} of the mean so that a quiet baseline does not
   * turn every later millisecond into a worsening.
   */
  public static double noise(List<Double> baseline) {
    double m = mean(baseline);
    return Math.max(2 * sampleStdDev(baseline), MIN_RELATIVE_NOISE * Math.abs(m));
  }
}
