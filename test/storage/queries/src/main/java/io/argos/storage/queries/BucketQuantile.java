/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

/**
 * Percentile estimate from the 15 latency buckets of 13 §2 (SQL and outgoing call statistics, C-14:
 * shown as "estimated"). Linear interpolation inside the bucket that holds the target rank {@code q
 * x total}: {@code lower + (upper - lower) x (target - below) / count}, with lower bound 0 for the
 * first bucket. The open bucket above 30 s returns 30,000 ms, its lower bound. The SQL form of the
 * same estimate is in Q08.sql (p95_est); the API function argosBucketQuantile (13 §18) is expected
 * to compute the same.
 */
public final class BucketQuantile {

  /** Bucket upper bounds in ms (13 §2); bucket 15 is above the last bound. */
  private static final int[] BOUNDS = {
    1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000
  };

  /** Number of buckets. */
  public static final int BUCKETS = BOUNDS.length + 1;

  private BucketQuantile() {}

  /** Upper bound (ms) of bucket {@code i} (0..13); bucket 14 has none. */
  public static int bound(int i) {
    return BOUNDS[i];
  }

  /**
   * The estimate of quantile {@code q} (0..1) from bucket counts; NaN when all counts are 0.
   *
   * @param counts 15 bucket counts (value <= bound; the last bucket is above 30,000 ms)
   */
  public static double estimate(long[] counts, double q) {
    if (counts.length != BUCKETS) {
      throw new IllegalArgumentException("Expected " + BUCKETS + " buckets, got " + counts.length);
    }
    long total = 0;
    for (long c : counts) {
      total += c;
    }
    if (total == 0) {
      return Double.NaN;
    }
    double target = q * total;
    long cumulative = 0;
    for (int j = 0; j < BUCKETS; j++) {
      long below = cumulative;
      cumulative += counts[j];
      if (cumulative >= target && counts[j] > 0) {
        double lower = j == 0 ? 0 : BOUNDS[j - 1];
        double upper = j == BUCKETS - 1 ? BOUNDS[BOUNDS.length - 1] : BOUNDS[j];
        return lower + (upper - lower) * (target - below) / counts[j];
      }
    }
    return BOUNDS[BOUNDS.length - 1];
  }

  /** Bucket index (0..14) of a value in ms. */
  public static int bucket(long ms) {
    for (int i = 0; i < BOUNDS.length; i++) {
      if (ms <= BOUNDS[i]) {
        return i;
      }
    }
    return BOUNDS.length;
  }
}
