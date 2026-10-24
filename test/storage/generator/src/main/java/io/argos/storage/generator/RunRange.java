/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import java.time.Instant;

/**
 * Time range of one run (UTC, ms, whole seconds): xlog and statistics cover {@code [start, end)},
 * profile_step the last {@code profileFrom..end}, metric the last {@code metricFrom..end} (the
 * retention shape of 13 §8 at the chosen scale). The version switch happens at {@code
 * versionSwitchAt} (day {@code version-switch-day} of {@code days}, scaled), followed by a rolling
 * restart of every instance starting at {@code deployAt}.
 *
 * @param start first instant
 * @param end end (exclusive)
 * @param profileFrom first instant with profile_step rows
 * @param metricFrom first instant with metric rows
 * @param versionSwitchAt start of the switch day
 * @param deployAt first instance restart of the rolling deploy
 * @param restartGapMs gap between two restarts within an app
 * @param restartDurationMs downtime of one instance
 */
public record RunRange(
    long start,
    long end,
    long profileFrom,
    long metricFrom,
    long versionSwitchAt,
    long deployAt,
    long restartGapMs,
    long restartDurationMs) {

  /** First instant the tx_id format can encode (2026-01-01T00:00:00Z, 13 §2). */
  public static final long TX_EPOCH_MS = 1_767_225_600_000L;

  public RunRange {
    if (start % 1000 != 0 || end % 1000 != 0 || end <= start) {
      throw new IllegalArgumentException("Invalid range " + start + ".." + end);
    }
    if (start < TX_EPOCH_MS) {
      throw new IllegalArgumentException(
          "Start before the tx_id epoch: " + Instant.ofEpochMilli(start));
    }
  }

  /** Range of a bulk run: {@code config.start} or {@link #defaultStart(long, long)}. */
  public static RunRange bulk(GeneratorConfig config, long nowMs) {
    long span = config.xlogSpanMs();
    long start =
        config.start != null ? floorSecond(config.start.toEpochMilli()) : defaultStart(nowMs, span);
    return of(config, start, start + span);
  }

  /** Range of a replay run starting at {@code startMs} for the sum of the TPS steps. */
  public static RunRange replay(GeneratorConfig config, long startMs) {
    long start = floorSecond(startMs);
    long span = config.stepDuration.toMillis() * config.tpsSteps.size();
    long end = start + Math.max(1000L, floorSecond(span));
    return new RunRange(start, end, start, start, end, end, 60_000L, 30_000L);
  }

  /** Range for an explicit start and end with the spans of {@code config}. */
  public static RunRange of(GeneratorConfig config, long start, long end) {
    long span = end - start;
    long profileFrom = end - Math.min(span, config.profileSpanMs());
    long metricFrom = end - Math.min(span, config.metricSpanMs());
    long switchAt =
        floorSecond(
            start + (long) (span * Math.min(1.0, (config.versionSwitchDay - 1) / config.days)));
    long deployAt = switchAt + (span >= 2 * GeneratorConfig.DAY_MS ? 9 * 3_600_000L : 0);
    long gap = Math.max(60_000L, Math.min(600_000L, floorSecond(span / 500)));
    long downtime = Math.min(45_000L, gap / 2);
    return new RunRange(start, end, profileFrom, metricFrom, switchAt, deployAt, gap, downtime);
  }

  /**
   * Default start so that the data is recent (inside every retention) and crosses a UTC midnight: a
   * span shorter than a day is centred on the last midnight at least half a span ago; a longer span
   * ends at the last full hour.
   */
  public static long defaultStart(long nowMs, long spanMs) {
    if (spanMs < GeneratorConfig.DAY_MS) {
      long midnight =
          Math.floorDiv(nowMs - spanMs / 2, GeneratorConfig.DAY_MS) * GeneratorConfig.DAY_MS;
      return floorSecond(midnight - spanMs / 2);
    }
    long end = Math.floorDiv(nowMs, 3_600_000L) * 3_600_000L;
    return end - spanMs;
  }

  /** Floors to a whole second. */
  public static long floorSecond(long ms) {
    return Math.floorDiv(ms, 1000L) * 1000L;
  }
}
