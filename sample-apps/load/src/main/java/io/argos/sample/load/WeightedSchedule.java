/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

/**
 * Deterministic interleaving of weighted routes (smooth weighted round robin). One cycle has {@code
 * sum(weights) / gcd(weights)} slots and contains every route exactly {@code weight / gcd} times,
 * spread evenly; request {@code n} uses slot {@code n mod cycle}. The mix is therefore exact over
 * every whole cycle instead of being right only on average, and runs are reproducible.
 *
 * <p>bench.js computes the same schedule from {@code exec.scenario.iterationInTest} (same
 * algorithm, same tie rule: the lowest route index wins), so k6 and this generator send the same
 * request sequence for the same mix.
 */
final class WeightedSchedule {

  private final int[] slots;
  private final int[] occurrence;
  private final int[] perCycle;

  WeightedSchedule(int[] weights) {
    if (weights.length == 0) {
      throw new IllegalArgumentException("no routes");
    }
    int gcd = 0;
    for (int weight : weights) {
      if (weight <= 0) {
        throw new IllegalArgumentException("weights must be positive");
      }
      gcd = gcd(gcd, weight);
    }
    perCycle = new int[weights.length];
    long total = 0;
    for (int i = 0; i < weights.length; i++) {
      perCycle[i] = weights[i] / gcd;
      total += perCycle[i];
    }
    if (total > 100_000) {
      throw new IllegalArgumentException("schedule cycle too long: " + total);
    }
    int length = (int) total;
    slots = new int[length];
    occurrence = new int[length];
    long[] current = new long[weights.length];
    int[] seen = new int[weights.length];
    for (int slot = 0; slot < length; slot++) {
      int best = 0;
      for (int i = 0; i < weights.length; i++) {
        current[i] += perCycle[i];
        if (current[i] > current[best]) {
          best = i;
        }
      }
      current[best] -= length;
      slots[slot] = best;
      occurrence[slot] = seen[best]++;
    }
  }

  int cycleLength() {
    return slots.length;
  }

  /** Route index of request {@code sequence} (0-based). */
  int routeIndex(long sequence) {
    return slots[slot(sequence)];
  }

  /**
   * How many requests of the same route came before request {@code sequence}: the input of the
   * route's deterministic parameters (order id, page, sleep time).
   */
  long ordinal(long sequence) {
    int slot = slot(sequence);
    return (sequence / slots.length) * perCycle[slots[slot]] + occurrence[slot];
  }

  private int slot(long sequence) {
    if (sequence < 0) {
      throw new IllegalArgumentException("negative sequence");
    }
    return (int) (sequence % slots.length);
  }

  private static int gcd(int a, int b) {
    int x = a;
    int y = b;
    while (y != 0) {
      int t = x % y;
      x = y;
      y = t;
    }
    return x;
  }
}
