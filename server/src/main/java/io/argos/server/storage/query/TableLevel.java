/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.time.Duration;

/**
 * The three storage levels a time-range query reads (13 §6.1, 14 §3, DSN-14-06): the raw tables
 * ({@code xlog}, {@code metric}, {@code *_stat_raw}), the minute rollups ({@code *_1m}) and the
 * hour rollups ({@code *_1h}).
 */
public enum TableLevel {
  /** Raw rows, millisecond (xlog) or 5 s / 10 s (metric, statistics) resolution. */
  RAW(Duration.ofSeconds(10), "raw"),
  /** Minute rollups: one row per key and minute ({@code minute} column). */
  MINUTE(Duration.ofMinutes(1), "1m"),
  /** Hour rollups: one row per key and hour ({@code hour} column). */
  HOUR(Duration.ofHours(1), "1h");

  private final Duration granularity;
  private final String label;

  TableLevel(Duration granularity, String label) {
    this.granularity = granularity;
    this.label = label;
  }

  /**
   * The smallest bucket step this level supports: 10 s for raw rows (the statistics window), 1
   * minute and 1 hour for the rollups.
   */
  public Duration granularity() {
    return granularity;
  }

  /** The {@code source} value of 14 §2.5: {@code raw}, {@code 1m} or {@code 1h}. */
  public String label() {
    return label;
  }
}
