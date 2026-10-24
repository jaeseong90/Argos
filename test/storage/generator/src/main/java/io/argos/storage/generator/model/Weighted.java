/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import java.util.random.RandomGenerator;

/**
 * Discrete distribution over indexes {@code 0..n-1} given by weights (cumulative table, binary
 * search). {@link #zipf(int, double)} gives rank {@code k} (index {@code k-1}) the probability
 * {@code k^-s / H(n, s)}.
 */
public final class Weighted {

  private final double[] cdf;

  private Weighted(double[] cdf) {
    this.cdf = cdf;
  }

  /** Zipf distribution with exponent {@code s} over {@code n} ranks. */
  public static Weighted zipf(int n, double s) {
    if (n < 1 || s < 0) {
      throw new IllegalArgumentException("Invalid Zipf parameters n=" + n + " s=" + s);
    }
    double[] weights = new double[n];
    for (int k = 1; k <= n; k++) {
      weights[k - 1] = Math.pow(k, -s);
    }
    return of(weights);
  }

  /** Distribution proportional to {@code weights} (non-negative, positive sum). */
  public static Weighted of(double[] weights) {
    if (weights.length == 0) {
      throw new IllegalArgumentException("No weights");
    }
    double[] cdf = new double[weights.length];
    double sum = 0;
    for (int i = 0; i < weights.length; i++) {
      if (!(weights[i] >= 0) || Double.isInfinite(weights[i])) {
        throw new IllegalArgumentException("Invalid weight " + weights[i] + " at " + i);
      }
      sum += weights[i];
      cdf[i] = sum;
    }
    if (!(sum > 0)) {
      throw new IllegalArgumentException("Weights sum to zero");
    }
    for (int i = 0; i < cdf.length; i++) {
      cdf[i] /= sum;
    }
    cdf[cdf.length - 1] = 1.0;
    return new Weighted(cdf);
  }

  /** Number of indexes. */
  public int size() {
    return cdf.length;
  }

  /** Probability of {@code index}. */
  public double probability(int index) {
    return index == 0 ? cdf[0] : cdf[index] - cdf[index - 1];
  }

  /** A random index. */
  public int sample(RandomGenerator random) {
    return indexOf(random.nextDouble());
  }

  /** The index whose cumulative interval contains {@code u} in [0, 1). */
  public int indexOf(double u) {
    int lo = 0;
    int hi = cdf.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (cdf[mid] > u) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }
}
