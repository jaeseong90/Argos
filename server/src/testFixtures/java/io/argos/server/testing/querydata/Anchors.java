/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Times relative to the current time for hand-made rows (WO-03F anchor-date rule: rows in
 * retention-bound tables never use fixed dates, so a test that runs on any day finds them inside
 * the TTL and the retention rules of 14 §3).
 */
public final class Anchors {

  private Anchors() {}

  /** The current time in milliseconds. */
  public static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  /** The start of the current minute. */
  public static Instant thisMinute() {
    return Instant.now().truncatedTo(ChronoUnit.MINUTES);
  }

  /** The start of the current hour. */
  public static Instant thisHour() {
    return Instant.now().truncatedTo(ChronoUnit.HOURS);
  }

  /** The start of today (UTC). */
  public static Instant today() {
    return Instant.now().truncatedTo(ChronoUnit.DAYS);
  }

  /** {@code now() - duration}. */
  public static Instant ago(Duration duration) {
    return now().minus(duration);
  }

  /** {@code thisMinute() - minutes}. */
  public static Instant minutesAgo(long minutes) {
    return thisMinute().minus(Duration.ofMinutes(minutes));
  }

  /** {@code thisHour() - hours}. */
  public static Instant hoursAgo(long hours) {
    return thisHour().minus(Duration.ofHours(hours));
  }

  /** {@code today() - days}. */
  public static Instant daysAgo(long days) {
    return today().minus(Duration.ofDays(days));
  }
}
