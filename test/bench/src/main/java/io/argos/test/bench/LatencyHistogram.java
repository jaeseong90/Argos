/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HDR-style latency histogram (18 §7.2: the runner re-aggregates the per-request latencies of k6's
 * raw output). Values are microseconds in log-linear buckets: exact below 2,048 µs, then 1,024
 * buckets per power of two, so every bucket is narrower than 1/1,024 of its value (three
 * significant decimal digits, as HdrHistogram with {@code numberOfSignificantValueDigits = 3}). A
 * bucket stands for its midpoint, so a reported value is within ±0.05% of a recorded one. Values of
 * 2^41 µs (25 days) and more are clamped into the last bucket.
 *
 * <p>Quantiles use the nearest-rank definition: the value at rank {@code ceil(q * n)}. Not thread
 * safe; one histogram per reader.
 */
final class LatencyHistogram {
  /** Sub-bucket bits: 2^11 = 2,048 exact microsecond buckets, then 1,024 per power of two. */
  static final int SUB_BUCKET_BITS = 11;

  private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
  private static final int HALF = SUB_BUCKETS / 2;
  private static final int MAX_SHIFT = 40 - SUB_BUCKET_BITS + 1;
  static final int BUCKETS = SUB_BUCKETS + MAX_SHIFT * HALF;

  private final long[] counts = new long[BUCKETS];
  private long total;
  private long maxRecorded = -1;
  private long minRecorded = Long.MAX_VALUE;

  /** Bucket index of {@code micros} (negative values count as 0). */
  static int index(long micros) {
    long v = Math.max(0, micros);
    if (v < SUB_BUCKETS) {
      return (int) v;
    }
    int exponent = 63 - Long.numberOfLeadingZeros(v);
    int shift = exponent - (SUB_BUCKET_BITS - 1);
    if (shift > MAX_SHIFT) {
      return BUCKETS - 1;
    }
    long sub = v >>> shift;
    return SUB_BUCKETS + (shift - 1) * HALF + (int) (sub - HALF);
  }

  /** Lowest value (µs) of bucket {@code index}. */
  static long lowest(int index) {
    if (index < SUB_BUCKETS) {
      return index;
    }
    int shift = (index - SUB_BUCKETS) / HALF + 1;
    long sub = (long) (index - SUB_BUCKETS) % HALF + HALF;
    return sub << shift;
  }

  /** Width (µs) of bucket {@code index}. */
  static long width(int index) {
    return index < SUB_BUCKETS ? 1 : 1L << ((index - SUB_BUCKETS) / HALF + 1);
  }

  /** The value (µs) a bucket stands for: its midpoint. */
  static double representative(int index) {
    return lowest(index) + (width(index) - 1) / 2.0;
  }

  void recordMicros(long micros) {
    recordMicros(micros, 1);
  }

  void recordMicros(long micros, long count) {
    if (count <= 0) {
      return;
    }
    counts[index(micros)] += count;
    total += count;
    long v = Math.max(0, micros);
    maxRecorded = Math.max(maxRecorded, v);
    minRecorded = Math.min(minRecorded, v);
  }

  /** Records a latency in milliseconds (k6's unit), rounded to the microsecond. */
  void recordMillis(double millis) {
    if (Double.isNaN(millis)) {
      return;
    }
    recordMicros(Math.round(millis * 1000));
  }

  long count() {
    return total;
  }

  /** Adds every count of {@code other}. */
  void add(LatencyHistogram other) {
    for (int i = 0; i < BUCKETS; i++) {
      counts[i] += other.counts[i];
    }
    total += other.total;
    maxRecorded = Math.max(maxRecorded, other.maxRecorded);
    minRecorded = Math.min(minRecorded, other.minRecorded);
  }

  /** Representative value (µs) of the value at {@code rank} (1..count), NaN when empty. */
  double valueAtRankMicros(long rank) {
    if (total == 0) {
      return Double.NaN;
    }
    long r = Math.min(Math.max(rank, 1), total);
    long seen = 0;
    for (int i = 0; i < BUCKETS; i++) {
      seen += counts[i];
      if (seen >= r) {
        return representative(i);
      }
    }
    return representative(BUCKETS - 1);
  }

  /** Nearest rank of quantile {@code q} among {@code n} values: ceil(q * n), within 1..n. */
  static long rank(double q, long n) {
    long r = (long) Math.ceil(q * n - 1e-9);
    return Math.min(Math.max(r, 1), n);
  }

  /** The quantile {@code q} (0..1) in milliseconds, NaN when empty. */
  double quantileMillis(double q) {
    return total == 0 ? Double.NaN : valueAtRankMicros(rank(q, total)) / 1000.0;
  }

  /** Mean in milliseconds (bucket midpoints), NaN when empty. */
  double meanMillis() {
    if (total == 0) {
      return Double.NaN;
    }
    double sum = 0;
    for (int i = 0; i < BUCKETS; i++) {
      if (counts[i] > 0) {
        sum += counts[i] * representative(i);
      }
    }
    return sum / total / 1000.0;
  }

  /** Largest recorded value in milliseconds (exact), NaN when empty. */
  double maxMillis() {
    return total == 0 ? Double.NaN : maxRecorded / 1000.0;
  }

  /**
   * Cumulative distribution for a graph: rows of [latency ms, fraction of requests at or below],
   * one per non-empty bucket, thinned to at most {@code maxPoints} rows (the last row is kept).
   */
  List<double[]> cdf(int maxPoints) {
    List<double[]> rows = new ArrayList<>();
    if (total == 0) {
      return rows;
    }
    long seen = 0;
    for (int i = 0; i < BUCKETS; i++) {
      if (counts[i] > 0) {
        seen += counts[i];
        rows.add(new double[] {representative(i) / 1000.0, (double) seen / total});
      }
    }
    if (rows.size() <= maxPoints) {
      return rows;
    }
    List<double[]> thin = new ArrayList<>();
    double step = (double) rows.size() / (maxPoints - 1);
    for (int k = 0; k < maxPoints - 1; k++) {
      thin.add(rows.get((int) Math.floor(k * step)));
    }
    thin.add(rows.get(rows.size() - 1));
    return thin;
  }

  /** Sparse JSON form: [[lowest µs of bucket, count], ...] plus the count and the exact max. */
  Map<String, Object> toJson() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("unit", "us");
    out.put("subBucketBits", SUB_BUCKET_BITS);
    out.put("count", total);
    out.put("minUs", total == 0 ? null : minRecorded);
    out.put("maxUs", total == 0 ? null : maxRecorded);
    List<Object> buckets = new ArrayList<>();
    for (int i = 0; i < BUCKETS; i++) {
      if (counts[i] > 0) {
        buckets.add(List.of(lowest(i), counts[i]));
      }
    }
    out.put("buckets", buckets);
    return out;
  }

  /** Reads {@link #toJson()}; a histogram of other precision is rejected. */
  static LatencyHistogram fromJson(Object json) {
    Map<String, Object> map = Json.object(json);
    Object bits = map.get("subBucketBits");
    if (!(bits instanceof Number n) || n.intValue() != SUB_BUCKET_BITS) {
      throw new IllegalArgumentException("histogram with subBucketBits " + bits + " not supported");
    }
    LatencyHistogram h = new LatencyHistogram();
    for (Object b : Json.array(map.get("buckets"))) {
      List<Object> pair = Json.array(b);
      long low = (long) Json.number(pair.get(0));
      long count = (long) Json.number(pair.get(1));
      int i = index(low);
      h.counts[i] += count;
      h.total += count;
    }
    if (h.total > 0) {
      h.maxRecorded = (long) Json.number(map.get("maxUs"));
      h.minRecorded = (long) Json.number(map.get("minUs"));
    }
    return h;
  }
}
