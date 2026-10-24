/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe histogram of millisecond values with relative error below 1 % (log-linear buckets:
 * exact below 128 ms, then 64 sub-buckets per power of two), for ingest lag and insert latency
 * percentiles.
 */
public final class LatencyHistogram {

  private static final int SUB = 64;
  private static final int EXACT = 128;
  private static final int MAX_POWER = 40;

  private final long[] counts = new long[EXACT + (MAX_POWER - 7) * SUB];
  private long total;
  private long max;
  private double sum;

  /** Records one value (negative values count as 0). */
  public synchronized void record(long ms) {
    recordMany(ms, 1);
  }

  /** Records {@code n} occurrences of a value. */
  public synchronized void recordMany(long ms, long n) {
    long v = Math.max(0, ms);
    counts[index(v)] += n;
    total += n;
    sum += (double) v * n;
    max = Math.max(max, v);
  }

  /** Number of values. */
  public synchronized long count() {
    return total;
  }

  /** The value at quantile {@code q} (upper bound of its bucket), 0 when empty. */
  public synchronized long quantile(double q) {
    if (total == 0) {
      return 0;
    }
    long rank = (long) Math.ceil(q * total);
    long seen = 0;
    for (int i = 0; i < counts.length; i++) {
      seen += counts[i];
      if (seen >= rank && counts[i] > 0) {
        return Math.min(max, upper(i));
      }
    }
    return max;
  }

  /** count, mean, p50, p95, p99, max. */
  public synchronized Map<String, Object> summary() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("count", total);
    m.put("mean", total == 0 ? 0.0 : sum / total);
    m.put("p50", quantile(0.50));
    m.put("p95", quantile(0.95));
    m.put("p99", quantile(0.99));
    m.put("max", max);
    return m;
  }

  private static int index(long v) {
    if (v < EXACT) {
      return (int) v;
    }
    int power = 63 - Long.numberOfLeadingZeros(v); // >= 7
    if (power >= MAX_POWER) {
      return EXACT + (MAX_POWER - 8) * SUB + SUB - 1;
    }
    int sub = (int) ((v >>> (power - 6)) & (SUB - 1));
    return EXACT + (power - 7) * SUB + sub;
  }

  private static long upper(int index) {
    if (index < EXACT) {
      return index;
    }
    int power = 7 + (index - EXACT) / SUB;
    int sub = (index - EXACT) % SUB;
    return ((long) (SUB + sub + 1) << (power - 6)) - 1;
  }
}
