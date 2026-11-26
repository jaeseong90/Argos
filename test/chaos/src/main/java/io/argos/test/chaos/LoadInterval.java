/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One progress line of the demo load generator ({@code sample-apps/load}, {@code
 * Summary.progressLine}): the requests and latency percentiles of one report interval, with the
 * wall-clock time the runner read the line (the end of the interval).
 *
 * @param end when the line was read
 * @param length the report interval
 * @param elapsedSeconds {@code t=} of the line (seconds since the measured window started)
 * @param sent requests sent in the interval
 * @param expected answers with the route's expected status
 * @param unexpected other answers and requests without an answer
 * @param status5xx 5xx answers and failures
 * @param dropped requests the generator could not start (in-flight limit)
 * @param inFlight requests in flight at the end of the interval
 * @param p99 interval p99 in ms (NaN without answers)
 */
public record LoadInterval(
    Instant end,
    Duration length,
    double elapsedSeconds,
    long sent,
    long expected,
    long unexpected,
    long status5xx,
    long dropped,
    int inFlight,
    double p99) {

  static final Pattern LINE =
      Pattern.compile(
          "\\[demo-load] t=(\\d+)s rate=\\S+ sent=(\\d+) expected=(\\d+) unexpected=(\\d+)"
              + " 5xx=(\\d+) dropped=(\\d+) inFlight=(\\d+) p50=\\S+ p95=\\S+ p99=(\\S+)ms");

  /**
   * Parses a progress line of the measured window; empty for any other line (warm-up lines,
   * banners, the final line).
   */
  public static Optional<LoadInterval> parse(String line, Instant readAt, Duration length) {
    Matcher m = LINE.matcher(line.strip());
    if (!m.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new LoadInterval(
            readAt,
            length,
            Double.parseDouble(m.group(1)),
            Long.parseLong(m.group(2)),
            Long.parseLong(m.group(3)),
            Long.parseLong(m.group(4)),
            Long.parseLong(m.group(5)),
            Long.parseLong(m.group(6)),
            Integer.parseInt(m.group(7)),
            parseMillis(m.group(8))));
  }

  /** The start of the interval ({@link #end} minus {@link #length}). */
  public Instant start() {
    return end.minus(length);
  }

  /** Whether the interval lies within [from, to]. */
  public boolean within(Instant from, Instant to) {
    return !start().isBefore(from) && !end.isAfter(to);
  }

  private static double parseMillis(String text) {
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }
}
