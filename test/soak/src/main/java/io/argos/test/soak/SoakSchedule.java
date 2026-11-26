/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.time.Duration;

/**
 * The measurement intervals of a soak run (18 §11.2 table): hourly (agent heap, NMT, agent threads,
 * app p99, batch run) and every 10 minutes (FD, server, WAL, ClickHouse) for the 72-hour run. A
 * shorter run scales both by {@code duration / 72 h} so that it keeps the shape of the full run,
 * with lower bounds so that a probe never runs back to back ({@link #HOURLY_FLOOR}, {@link
 * #TEN_MINUTE_FLOOR}, {@link #BATCH_FLOOR}); a run longer than 72 hours keeps the nominal
 * intervals. The nightly consistency check is not scaled: it runs for every UTC day the run
 * finished, and once at the end for the days still open (18 §11.2 "매일").
 *
 * @param duration the measured run time
 * @param scale {@code min(1, duration / 72 h)}
 * @param hourly the "1시간" interval
 * @param tenMinute the "10분" interval
 * @param batch the shop-batch run interval ("배치 매시간")
 * @param warmUp load before the first sample (excluded from every series)
 */
public record SoakSchedule(
    Duration duration,
    double scale,
    Duration hourly,
    Duration tenMinute,
    Duration batch,
    Duration warmUp) {

  /** Lower bound of the scaled hourly interval (a GC.run and a class histogram each time). */
  public static final Duration HOURLY_FLOOR = Duration.ofMinutes(1);

  /** Lower bound of the scaled 10-minute interval. */
  public static final Duration TEN_MINUTE_FLOOR = Duration.ofSeconds(15);

  /** Lower bound of the scaled batch interval (one settlementJob run takes seconds). */
  public static final Duration BATCH_FLOOR = Duration.ofMinutes(2);

  /** Baseline of the p99 comparison in the full run: the first 6 hourly points (18 §11.2). */
  public static final int P99_BASELINE_POINTS = 6;

  /** The schedule of a run of {@code duration}. */
  public static SoakSchedule of(Duration duration) {
    double scale =
        Math.min(1.0, (double) duration.toMillis() / SoakOptions.FULL_DURATION.toMillis());
    return new SoakSchedule(
        duration,
        scale,
        scaled(Duration.ofHours(1), scale, HOURLY_FLOOR),
        scaled(Duration.ofMinutes(10), scale, TEN_MINUTE_FLOOR),
        scaled(Duration.ofHours(1), scale, BATCH_FLOOR),
        warmUp(duration));
  }

  /** {@code nominal x scale}, at least {@code floor} (and never above {@code nominal}). */
  static Duration scaled(Duration nominal, double scale, Duration floor) {
    Duration d = Duration.ofMillis(Math.round(nominal.toMillis() * scale));
    if (d.compareTo(floor) < 0) {
      d = floor;
    }
    return d.compareTo(nominal) > 0 ? nominal : d;
  }

  /**
   * Warm-up before the first sample: 10 minutes for a run of a day or more, else 5% of the run
   * between 30 seconds and 2 minutes (the app's JIT and the agent's dictionaries settle).
   */
  static Duration warmUp(Duration duration) {
    if (duration.compareTo(SoakOptions.LONG_RUN) >= 0) {
      return Duration.ofMinutes(10);
    }
    long ms = duration.toMillis() / 20;
    return Duration.ofMillis(Math.max(30_000, Math.min(120_000, ms)));
  }

  /** Whether the intervals differ from 18 §11.2 (a run shorter than 72 hours). */
  public boolean scaledDown() {
    return scale < 1.0;
  }

  /** The number of hourly points the run collects. */
  public long hourlyPoints() {
    return Math.max(1, duration.toMillis() / hourly.toMillis());
  }

  /** The number of 10-minute points the run collects. */
  public long tenMinutePoints() {
    return Math.max(1, duration.toMillis() / tenMinute.toMillis());
  }

  /**
   * The p99 baseline: the first {@value #P99_BASELINE_POINTS} hourly points when the run has twice
   * as many, else the first half (at least 1).
   */
  public static int p99BaselinePoints(int points) {
    if (points >= 2 * P99_BASELINE_POINTS) {
      return P99_BASELINE_POINTS;
    }
    return Math.max(1, points / 2);
  }

  /** One line for the report. */
  public String describe() {
    String base =
        "hourly measurements every "
            + SoakOptions.format(hourly)
            + ", 10-minute measurements every "
            + SoakOptions.format(tenMinute)
            + ", batch every "
            + SoakOptions.format(batch)
            + ", warm-up "
            + SoakOptions.format(warmUp);
    return scaledDown()
        ? base
            + String.format(
                java.util.Locale.ROOT,
                " (scaled by %.5f = %s / 72h from 18 §11.2's 1 h and 10 min, lower bounds %s, %s"
                    + " and %s)",
                scale,
                SoakOptions.format(duration),
                SoakOptions.format(HOURLY_FLOOR),
                SoakOptions.format(TEN_MINUTE_FLOOR),
                SoakOptions.format(BATCH_FLOOR))
        : base + " (18 §11.2 intervals)";
  }
}
