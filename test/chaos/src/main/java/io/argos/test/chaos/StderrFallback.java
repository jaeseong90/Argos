/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent's stderr fallback as the application's console shows it (10 §14, DSN-10-20): when the
 * log directory cannot be written, WARN and ERROR records go to stderr, at most {@value
 * #LINES_PER_MINUTE} lines per minute (windows of one minute from the first line, as agent-core's
 * {@code StderrFallbackSink} counts them). The records have the log file's format ({@link
 * AgentLog#RECORD}); other stderr lines (JVM messages) are not the agent's.
 *
 * @param lines the agent records on stderr, oldest first
 */
public record StderrFallback(List<AgentLog.Line> lines) {

  /** The design's cap (T-CH-005 "stderr 폴백 분당 ≤ 10줄"). */
  public static final int LINES_PER_MINUTE = 10;

  /** Copies. */
  public StderrFallback {
    lines = List.copyOf(lines);
  }

  /** The agent records of the stderr text {@code text}. */
  public static StderrFallback parse(String text) {
    return new StderrFallback(AgentLog.parse(text).lines());
  }

  /** The records at or after {@code from}. */
  public List<AgentLog.Line> since(Instant from) {
    return lines.stream().filter(l -> !l.time().isBefore(from)).toList();
  }

  /**
   * The line counts of consecutive one-minute windows, the first starting at the first record (the
   * sink's windows).
   */
  public List<Integer> perMinute() {
    List<Integer> counts = new ArrayList<>();
    Instant windowStart = null;
    int count = 0;
    for (AgentLog.Line l : lines) {
      if (windowStart == null) {
        windowStart = l.time();
      } else if (!l.time().isBefore(windowStart.plus(Duration.ofMinutes(1)))) {
        counts.add(count);
        count = 0;
        windowStart = l.time();
      }
      count++;
    }
    if (windowStart != null) {
      counts.add(count);
    }
    return counts;
  }

  /** The largest count of one window ({@link #perMinute()}), 0 without lines. */
  public int maxPerMinute() {
    return perMinute().stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  /** Whether every window stays within {@link #LINES_PER_MINUTE}. */
  public boolean withinCap() {
    return maxPerMinute() <= LINES_PER_MINUTE;
  }
}
