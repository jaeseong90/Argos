/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import java.util.Objects;

/**
 * One value of an alert metric for one evaluation (12 §5.2).
 *
 * @param status whether there is a value
 * @param value the value ({@link Status#VALUE} only)
 * @param samples requests (or calls) in the window for request metrics, else 0; {@code min_samples}
 *     compares with it
 */
public record MetricValue(Status status, Double value, long samples) {

  private static final MetricValue UNAVAILABLE = new MetricValue(Status.UNAVAILABLE, null, 0);

  /** Validates. */
  public MetricValue {
    Objects.requireNonNull(status, "status");
    if ((status == Status.VALUE) != (value != null)) {
      throw new IllegalArgumentException("a value exactly when the status is VALUE");
    }
    if (value != null && !Double.isFinite(value)) {
      throw new IllegalArgumentException("not finite: " + value);
    }
  }

  /** A value with its sample count. */
  public static MetricValue of(double value, long samples) {
    return Double.isFinite(value)
        ? new MetricValue(Status.VALUE, value, samples)
        : new MetricValue(Status.NO_DATA, null, samples);
  }

  /** A value of a metric without samples (gauges, counters, self metrics). */
  public static MetricValue of(double value) {
    return of(value, 0);
  }

  /** No value: the source exists but had nothing in the window. */
  public static MetricValue noData(long samples) {
    return new MetricValue(Status.NO_DATA, null, samples);
  }

  /**
   * No value because the source does not exist in this installation or version (a meter another
   * work order adds, an S feature): the engine never turns it into NO_DATA.
   */
  public static MetricValue unavailable() {
    return UNAVAILABLE;
  }

  /** Whether there is a value. */
  public boolean present() {
    return status == Status.VALUE;
  }

  /** Whether a value exists. */
  public enum Status {
    /** A value. */
    VALUE,
    /** The source had no data in the window (the rule's {@code no_data} policy applies). */
    NO_DATA,
    /** Not computable here (the rule is skipped, never NO_DATA). */
    UNAVAILABLE
  }
}
