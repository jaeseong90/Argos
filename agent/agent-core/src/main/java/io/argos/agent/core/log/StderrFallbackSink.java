/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback when the log directory is not writable (10 §14, DSN-10-20, T-AG-045): at most {@value
 * #LINES_PER_MINUTE} lines per minute and the same message at most once per minute, so the
 * application's console is never flooded. Only WARN and ERROR reach this sink.
 */
final class StderrFallbackSink implements LogSink {
  static final int LINES_PER_MINUTE = 10;
  private static final long MINUTE_MILLIS = 60_000L;
  private static final int MAX_TRACKED_MESSAGES = 256;

  private final PrintStream stderr;
  private final String reason;
  private long windowStart = Long.MIN_VALUE;
  private int linesInWindow;
  private final Map<String, Long> lastSeen =
      new LinkedHashMap<String, Long>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
          return size() > MAX_TRACKED_MESSAGES;
        }
      };

  StderrFallbackSink(PrintStream stderr, String reason) {
    this.stderr = stderr;
    this.reason = reason;
  }

  @Override
  public void write(LogRecord record, String line) {
    if (!LogLevel.WARN.allows(record.level)) {
      return;
    }
    long now = record.epochMillis;
    if (windowStart == Long.MIN_VALUE || now - windowStart >= MINUTE_MILLIS) {
      windowStart = now;
      linesInWindow = 0;
    }
    if (linesInWindow >= LINES_PER_MINUTE) {
      return;
    }
    Long previous = lastSeen.get(record.message);
    if (previous != null && now - previous < MINUTE_MILLIS) {
      return;
    }
    lastSeen.put(record.message, now);
    linesInWindow++;
    // One line per event: stack traces are not copied to the application's console.
    int newline = line.indexOf('\n');
    stderr.println(newline < 0 ? line : line.substring(0, newline));
  }

  @Override
  public void flush() {
    stderr.flush();
  }

  @Override
  public void close() {
    stderr.flush();
  }

  @Override
  public String describe() {
    return "stderr (limited to " + LINES_PER_MINUTE + " lines per minute: " + reason + ")";
  }
}
