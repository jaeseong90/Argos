/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.storage.query.QueryRow;

/**
 * Number helpers of the time-series and statistics responses: undefined values are null, never 0 or
 * NaN (D-23; JSON has no NaN), and values are rounded to a fixed number of decimals.
 */
public final class Values {

  private Values() {}

  /** {@code value} rounded to {@code digits} decimals; null for null, NaN or infinity. */
  public static Double round(Double value, int digits) {
    if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
      return null;
    }
    double scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
  }

  /** {@code numerator / denominator} rounded; null when the denominator is 0. */
  public static Double ratio(double numerator, double denominator, int digits) {
    if (denominator == 0 || Double.isNaN(numerator) || Double.isNaN(denominator)) {
      return null;
    }
    return round(numerator / denominator, digits);
  }

  /** {@code numerator / denominator} of counts rounded; null when the denominator is 0. */
  public static Double ratio(long numerator, long denominator, int digits) {
    return ratio((double) numerator, (double) denominator, digits);
  }

  /** {@code numerator / denominator} of a count rounded; null when the denominator is 0. */
  public static Double ratio(long numerator, double denominator, int digits) {
    return ratio((double) numerator, denominator, digits);
  }

  /** {@code numerator / denominator} rounded; null when the denominator is 0. */
  public static Double ratio(double numerator, long denominator, int digits) {
    return ratio(numerator, (double) denominator, digits);
  }

  /**
   * Transactions per second: {@code count / seconds} with 6 decimals, so that the integral of a
   * series ({@code Σ tps × bucket seconds}) gives the count back closely (D-03: the rounding is at
   * most {@code 0.5e-6 × bucket seconds} per bucket, 3e-5 for a minute; 3 decimals put 1/60 s off
   * by 2 %); the exact count is {@code series.count}. Null without covered seconds.
   */
  public static Double tps(long count, double seconds) {
    return ratio(count, seconds, 6);
  }

  /** A milliseconds value (3 decimals). */
  public static Double ms(Double value) {
    return round(value, 3);
  }

  /**
   * Element {@code index} (0 = p50, 1 = p95, 2 = p99) of a {@code quantilesDDMerge(0.01, 0.5, 0.95,
   * 0.99)} column (the first parameter is the accuracy, so the array has three values); null for an
   * empty state.
   */
  public static Double quantile(QueryRow row, String column, int index) {
    double[] q = row.getDoubleArray(column);
    if (index >= q.length) {
      return null;
    }
    return ms(q[index]);
  }

  /** A 64-bit dictionary key as the API writes it: an unsigned decimal string. */
  public static String id(long bits) {
    return Long.toUnsignedString(bits);
  }

  /**
   * The relative change {@code (current - previous) / previous} rounded to 4 decimals; null when
   * either is null or the previous value is 0.
   */
  public static Double relative(Double current, Double previous) {
    if (current == null || previous == null || previous == 0) {
      return null;
    }
    return round((current - previous) / previous, 4);
  }

  /** The absolute change {@code current - previous} (rates); null when either is null. */
  public static Double absolute(Double current, Double previous) {
    if (current == null || previous == null) {
      return null;
    }
    return round(current - previous, 6);
  }
}
