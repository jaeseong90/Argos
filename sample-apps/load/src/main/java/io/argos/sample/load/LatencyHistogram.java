/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-size log-linear latency histogram in microseconds (HDR-style, no dependency): values below
 * 128 us are exact, larger values fall into buckets of at most 1/64 relative width, so a reported
 * percentile is at most 1.6% above the true value. Memory is constant (2,240 counters) however long
 * the generator runs. Recording is lock-free and safe from any thread.
 */
final class LatencyHistogram {

  private static final int SUB_BITS = 7;
  private static final int SUB = 1 << SUB_BITS;
  private static final int HALF = SUB >> 1;

  /** Largest recordable value (about 12.7 days); larger values are clamped. */
  static final long MAX_MICROS = (1L << 40) - 1;

  static final int BUCKETS = index(MAX_MICROS) + 1;

  private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
  private final LongAdder count = new LongAdder();
  private final LongAdder sum = new LongAdder();
  private final LongAccumulator max = new LongAccumulator(Math::max, 0);

  void recordNanos(long nanos) {
    recordMicros(Math.max(0, nanos) / 1_000);
  }

  void recordMicros(long micros) {
    long value = Math.min(Math.max(0, micros), MAX_MICROS);
    counts.incrementAndGet(index(value));
    count.increment();
    sum.add(value);
    max.accumulate(value);
  }

  Snapshot snapshot() {
    long[] copy = new long[BUCKETS];
    long total = 0;
    for (int i = 0; i < BUCKETS; i++) {
      copy[i] = counts.get(i);
      total += copy[i];
    }
    return new Snapshot(copy, total, sum.sum(), max.get());
  }

  static int index(long value) {
    if (value < SUB) {
      return (int) value;
    }
    int msb = 63 - Long.numberOfLeadingZeros(value);
    int shift = msb - (SUB_BITS - 1);
    long sub = value >>> shift;
    return SUB + (shift - 1) * HALF + (int) (sub - HALF);
  }

  /** Largest value that falls into bucket {@code index}. */
  static long upperBound(int index) {
    if (index < SUB) {
      return index;
    }
    int shift = (index - SUB) / HALF + 1;
    long sub = (long) ((index - SUB) % HALF) + HALF;
    return ((sub + 1) << shift) - 1;
  }

  /** A copy of the counters; {@link #minus} gives the counts of an interval. */
  static final class Snapshot {

    private final long[] counts;
    private final long count;
    private final long sumMicros;
    private final long maxMicros;

    Snapshot(long[] counts, long count, long sumMicros, long maxMicros) {
      this.counts = counts;
      this.count = count;
      this.sumMicros = sumMicros;
      this.maxMicros = maxMicros;
    }

    static Snapshot empty() {
      return new Snapshot(new long[BUCKETS], 0, 0, 0);
    }

    long count() {
      return count;
    }

    /** Counts recorded after {@code earlier} (the maximum is the bucket bound of the interval). */
    Snapshot minus(Snapshot earlier) {
      long[] diff = new long[BUCKETS];
      long total = 0;
      int highest = -1;
      for (int i = 0; i < BUCKETS; i++) {
        diff[i] = Math.max(0, counts[i] - earlier.counts[i]);
        total += diff[i];
        if (diff[i] > 0) {
          highest = i;
        }
      }
      long intervalMax = highest < 0 ? 0 : Math.min(upperBound(highest), maxMicros);
      return new Snapshot(diff, total, Math.max(0, sumMicros - earlier.sumMicros), intervalMax);
    }

    /** Value at percentile {@code percent} (0-100] in milliseconds; NaN when empty. */
    double percentileMillis(double percent) {
      if (count == 0) {
        return Double.NaN;
      }
      long rank = (long) Math.ceil(percent / 100.0 * count);
      rank = Math.max(1, Math.min(count, rank));
      long seen = 0;
      for (int i = 0; i < counts.length; i++) {
        seen += counts[i];
        if (seen >= rank) {
          return Math.min(upperBound(i), maxMicros) / 1000.0;
        }
      }
      return maxMicros / 1000.0;
    }

    double meanMillis() {
      return count == 0 ? Double.NaN : sumMicros / 1000.0 / count;
    }

    double maxMillis() {
      return count == 0 ? Double.NaN : maxMicros / 1000.0;
    }
  }
}
