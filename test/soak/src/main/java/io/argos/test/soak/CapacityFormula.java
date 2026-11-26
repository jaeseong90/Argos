/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The N-19 disk growth formula (01 N-19, 13 §14, 17 §7.4) applied to the rows the soak run stored:
 * the expected growth is {@code Σ rows(table) × B(table)} with the expected bytes per row after
 * compression of 13 §14 (B_x 70 B for {@code xlog}, B_p 25 B for {@code profile_step}, B_m 4 B for
 * {@code metric}, B_s = B_e 30 B for {@code sql_stat_raw} and {@code ext_stat_raw}, B_r 60 B for a
 * 1m or 1h rollup row and 300 B for the {@code app_stat} and {@code service_stat} rollups with
 * their percentile states). The formula's TPS, capture rate, step count, series and window terms
 * are the row rates of those tables, so the run's own rows stand in for them. Tables outside the
 * formula (dictionaries, events) are left out of both sides. The verdict of 18 §11.2 is "디스크 증가율 =
 * N-19 공식 ±30%": {@code actual / expected} within {@code [0.7, 1.3]}.
 */
public final class CapacityFormula {

  /** The tolerance of 18 §11.2 and 13 §14 ("±30%"). */
  public static final double TOLERANCE = 0.30;

  private CapacityFormula() {}

  /**
   * The expected bytes per row of {@code table} after compression (13 §14), or empty for a table
   * outside the formula.
   */
  public static OptionalDouble bytesPerRow(String table) {
    return switch (table) {
      case "xlog" -> OptionalDouble.of(70);
      case "profile_step" -> OptionalDouble.of(25);
      case "metric" -> OptionalDouble.of(4);
      case "sql_stat_raw", "ext_stat_raw" -> OptionalDouble.of(30);
      default -> {
        if (table.matches("(app|service)_stat_1[mh]")) {
          yield OptionalDouble.of(300);
        }
        if (table.matches("[a-z_]+_1[mh]")) {
          yield OptionalDouble.of(60);
        }
        yield OptionalDouble.empty();
      }
    };
  }

  /** {@code Σ rows × B} over the tables of the formula. */
  public static double expectedBytes(Map<String, Long> rowGrowthByTable) {
    double sum = 0;
    for (Map.Entry<String, Long> e : rowGrowthByTable.entrySet()) {
      OptionalDouble b = bytesPerRow(e.getKey());
      if (b.isPresent()) {
        sum += Math.max(0, e.getValue()) * b.getAsDouble();
      }
    }
    return sum;
  }

  /** The bytes on disk of the tables of the formula. */
  public static long formulaBytes(Map<String, Long> bytesByTable) {
    long sum = 0;
    for (Map.Entry<String, Long> e : bytesByTable.entrySet()) {
      if (bytesPerRow(e.getKey()).isPresent()) {
        sum += e.getValue();
      }
    }
    return sum;
  }

  /** {@code later - earlier} per table (tables of either map; a missing side counts as 0). */
  public static Map<String, Long> growth(Map<String, Long> earlier, Map<String, Long> later) {
    Map<String, Long> d = new LinkedHashMap<>();
    for (String t : later.keySet()) {
      d.put(t, later.get(t) - earlier.getOrDefault(t, 0L));
    }
    for (String t : earlier.keySet()) {
      d.putIfAbsent(t, -earlier.get(t));
    }
    return d;
  }

  /** {@code actual / expected}; NaN when nothing was expected. */
  public static double ratio(double actualBytes, double expectedBytes) {
    return expectedBytes <= 0 ? Double.NaN : actualBytes / expectedBytes;
  }

  /** Whether {@code ratio} is within {@code 1 ± }{@value #TOLERANCE}. */
  public static boolean withinTolerance(double ratio) {
    return !Double.isNaN(ratio) && ratio >= 1 - TOLERANCE && ratio <= 1 + TOLERANCE;
  }

  /** Bytes per day from {@code bytes} over {@code seconds}. */
  public static double perDay(double bytes, double seconds) {
    return seconds <= 0 ? Double.NaN : bytes * 86_400.0 / seconds;
  }
}
