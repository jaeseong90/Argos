/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The cumulative buckets of one Prometheus histogram ({@code le} upper bound in seconds → count of
 * observations at or below it), its {@code _sum} and its {@code _max} (Micrometer's decaying
 * maximum). Two scrapes give the observations between them with {@link #minus}; quantiles are
 * linearly interpolated inside the bucket that holds the rank, as Prometheus' {@code
 * histogram_quantile} does.
 */
public final class CumulativeHistogram {

  private final NavigableMap<Double, Double> buckets;
  private final double sum;
  private final double max;

  /**
   * A histogram.
   *
   * @param buckets cumulative counts by upper bound ({@code +Inf} included when scraped)
   * @param sum the {@code _sum} series (NaN when unknown)
   * @param max the {@code _max} series (NaN when unknown)
   */
  public CumulativeHistogram(Map<Double, Double> buckets, double sum, double max) {
    this.buckets = Collections.unmodifiableNavigableMap(new TreeMap<>(buckets));
    this.sum = sum;
    this.max = max;
  }

  /** An empty histogram. */
  public static CumulativeHistogram empty() {
    return new CumulativeHistogram(Map.of(), 0, Double.NaN);
  }

  /** The cumulative buckets. */
  public NavigableMap<Double, Double> buckets() {
    return buckets;
  }

  /** The {@code _sum} series. */
  public double sum() {
    return sum;
  }

  /** The {@code _max} series at the time of the scrape (a decaying maximum). */
  public double max() {
    return max;
  }

  /** Observations in total (the last, normally {@code +Inf}, bucket). */
  public double count() {
    return buckets.isEmpty() ? 0 : buckets.lastEntry().getValue();
  }

  /**
   * The observations between {@code earlier} and this scrape: bucket counts and sum subtracted, the
   * {@code _max} of this scrape kept. A counter reset (a bucket smaller than before) yields this
   * scrape's counts unchanged.
   */
  public CumulativeHistogram minus(CumulativeHistogram earlier) {
    boolean reset = false;
    for (Map.Entry<Double, Double> e : buckets.entrySet()) {
      if (e.getValue() < earlier.buckets.getOrDefault(e.getKey(), 0.0)) {
        reset = true;
        break;
      }
    }
    if (reset) {
      return this;
    }
    TreeMap<Double, Double> delta = new TreeMap<>();
    buckets.forEach((le, c) -> delta.put(le, c - earlier.buckets.getOrDefault(le, 0.0)));
    double sumDelta =
        Double.isNaN(sum) || Double.isNaN(earlier.sum) ? Double.NaN : sum - earlier.sum;
    return new CumulativeHistogram(delta, sumDelta, max);
  }

  /** The mean in seconds ({@code sum / count}), NaN without observations. */
  public double mean() {
    double count = count();
    return count <= 0 || Double.isNaN(sum) ? Double.NaN : sum / count;
  }

  /**
   * The {@code q} quantile in seconds, linearly interpolated inside its bucket (the lower bound of
   * the first bucket is 0); the upper bound of the last finite bucket when the rank falls into
   * {@code +Inf}; NaN without observations.
   */
  public double quantile(double q) {
    if (q < 0 || q > 1) {
      throw new IllegalArgumentException("quantile outside [0, 1]: " + q);
    }
    double total = count();
    if (total <= 0) {
      return Double.NaN;
    }
    double rank = q * total;
    double prevBound = 0;
    double prevCount = 0;
    for (Map.Entry<Double, Double> e : buckets.entrySet()) {
      if (e.getValue() >= rank) {
        if (e.getKey().isInfinite()) {
          return prevBound;
        }
        double inBucket = e.getValue() - prevCount;
        double fraction = inBucket <= 0 ? 1 : (rank - prevCount) / inBucket;
        return prevBound + (e.getKey() - prevBound) * fraction;
      }
      prevBound = e.getKey();
      prevCount = e.getValue();
    }
    return prevBound;
  }

  @Override
  public String toString() {
    return "CumulativeHistogram[count=" + count() + ", sum=" + sum + ", max=" + max + "]";
  }
}
