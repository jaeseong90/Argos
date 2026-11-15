/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import io.argos.proto.LatencyBuckets;
import io.argos.server.api.dashboard.Values;
import java.util.List;

/**
 * Percentiles of SQL and outgoing calls estimated from the 15 latency buckets (C-14, 13 §2 bounds
 * {@code [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000, +∞]} ms; {@link
 * LatencyBuckets}). Transactions have DD states instead (C-53); these are estimates and the
 * responses say so ({@code estimated: true}).
 *
 * <p>Method: the bucket holding rank {@code q × total} (the smallest {@code i} with cumulative
 * count ≥ rank), linear interpolation inside it between its lower bound (the previous upper bound,
 * 0 for the first) and its upper bound; the open last bucket's upper bound is the observed maximum.
 * The result never exceeds the observed maximum.
 */
public final class LatencyEstimate {

  private LatencyEstimate() {}

  /**
   * The {@code q} quantile (0 < q ≤ 1) of the bucket counts, rounded to 0.1 ms; null without
   * executions.
   *
   * @param buckets the 15 counts (fewer are treated as 0)
   * @param maxMs the observed maximum, or a negative value when unknown
   */
  public static Double quantile(List<Long> buckets, double q, double maxMs) {
    long total = 0;
    for (Long c : buckets) {
      total += c == null ? 0 : c;
    }
    if (total == 0 || q <= 0 || q > 1) {
      return null;
    }
    double rank = q * total;
    long cumulative = 0;
    for (int i = 0; i < Math.min(buckets.size(), LatencyBuckets.COUNT); i++) {
      long c = buckets.get(i) == null ? 0 : buckets.get(i);
      if (c > 0 && cumulative + c >= rank) {
        double lower = i == 0 ? 0 : LatencyBuckets.upperBoundMs(i - 1);
        double upper =
            i == LatencyBuckets.COUNT - 1 ? Math.max(lower, maxMs) : LatencyBuckets.upperBoundMs(i);
        double fraction = (rank - cumulative) / c;
        double value = lower + (upper - lower) * fraction;
        if (maxMs >= 0) {
          value = Math.min(value, maxMs);
        }
        return Values.round(value, 1);
      }
      cumulative += c;
    }
    return maxMs >= 0 ? Values.round(maxMs, 1) : null;
  }
}
