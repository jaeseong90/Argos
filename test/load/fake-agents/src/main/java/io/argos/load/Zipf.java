/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A Zipf distribution over ranks {@code 0..n-1} (rank 0 the most frequent) with exponent {@code s}:
 * P(k) proportional to 1 / (k + 1)^s. Sampled by binary search in the cumulative table, so a sample
 * costs O(log n). Immutable and thread-safe; the caller brings its random source.
 */
public final class Zipf {

  private final double[] cumulative;

  /** A distribution over {@code n} ranks with exponent {@code s} (18 §9: services, s = 1.1). */
  public Zipf(int n, double s) {
    if (n < 1) {
      throw new IllegalArgumentException("n < 1");
    }
    cumulative = new double[n];
    double total = 0;
    for (int k = 0; k < n; k++) {
      total += 1.0 / Math.pow(k + 1, s);
      cumulative[k] = total;
    }
    for (int k = 0; k < n; k++) {
      cumulative[k] /= total;
    }
    cumulative[n - 1] = 1.0;
  }

  /** Ranks. */
  public int size() {
    return cumulative.length;
  }

  /** The probability of rank {@code k}. */
  public double probability(int k) {
    return k == 0 ? cumulative[0] : cumulative[k] - cumulative[k - 1];
  }

  /** One rank. */
  public int sample(SplittableRandom random) {
    double u = random.nextDouble();
    int i = Arrays.binarySearch(cumulative, u);
    int rank = i >= 0 ? i : -i - 1;
    return Math.min(rank, cumulative.length - 1);
  }
}
