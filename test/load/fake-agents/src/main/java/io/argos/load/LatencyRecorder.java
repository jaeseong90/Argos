/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Client-side latencies of the virtual dashboard users, by endpoint: every observation of the
 * measured window is kept (N-06 at full scale: 10 users x 3 calls per 5 s for 1 hour = 21,600), and
 * the quantiles are exact (nearest rank). Thread-safe.
 */
public final class LatencyRecorder {

  private final Map<String, double[]> byEndpoint = new TreeMap<>();
  private final Map<String, Integer> sizes = new TreeMap<>();
  private final Map<String, Long> errors = new TreeMap<>();
  private volatile boolean measuring;

  /** Starts or stops recording (calls outside the measured window are not kept). */
  public void measuring(boolean on) {
    measuring = on;
  }

  /**
   * Records one call of {@code endpoint} that took {@code millis}; {@code ok} false counts it as an
   * error, NaN (no answer) counts the error only.
   */
  public synchronized void record(String endpoint, double millis, boolean ok) {
    if (!measuring) {
      return;
    }
    if (Double.isNaN(millis)) {
      errors.merge(endpoint, 1L, Long::sum);
      return;
    }
    double[] values = byEndpoint.computeIfAbsent(endpoint, k -> new double[256]);
    int size = sizes.getOrDefault(endpoint, 0);
    if (size == values.length) {
      values = Arrays.copyOf(values, values.length * 2);
      byEndpoint.put(endpoint, values);
    }
    values[size] = millis;
    sizes.put(endpoint, size + 1);
    if (!ok) {
      errors.merge(endpoint, 1L, Long::sum);
    }
  }

  /** Calls recorded, all endpoints. */
  public synchronized long count() {
    return sizes.values().stream().mapToLong(Integer::longValue).sum();
  }

  /** Calls that did not answer 200, all endpoints. */
  public synchronized long errors() {
    return errors.values().stream().mapToLong(Long::longValue).sum();
  }

  /** The {@code q} quantile in ms over every endpoint (nearest rank); NaN without calls. */
  public synchronized double quantile(double q) {
    double[] all =
        byEndpoint.entrySet().stream()
            .flatMapToDouble(e -> Arrays.stream(e.getValue(), 0, sizes.getOrDefault(e.getKey(), 0)))
            .toArray();
    return quantile(all, q);
  }

  /** Per endpoint: calls, errors, p50, p95, max (ms). */
  public synchronized Map<String, double[]> summary() {
    Map<String, double[]> out = new LinkedHashMap<>();
    byEndpoint.forEach(
        (endpoint, values) -> {
          double[] v = Arrays.copyOf(values, sizes.getOrDefault(endpoint, 0));
          out.put(
              endpoint,
              new double[] {
                v.length,
                errors.getOrDefault(endpoint, 0L),
                quantile(v, 0.50),
                quantile(v, 0.95),
                quantile(v, 1.0)
              });
        });
    return out;
  }

  /** Nearest-rank quantile of {@code values}; NaN when empty. */
  static double quantile(double[] values, double q) {
    if (values.length == 0) {
      return Double.NaN;
    }
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(q * sorted.length);
    return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
  }
}
