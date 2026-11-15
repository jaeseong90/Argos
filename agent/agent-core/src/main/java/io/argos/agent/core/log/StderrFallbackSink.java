/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fallback when the log directory is not writable (10 §14, DSN-10-20, T-AG-045): at most {@value
 * #LINES_PER_MINUTE} lines per minute and the same message at most once per minute, so the
 * application's console is never flooded. Only WARN and ERROR reach this sink, one line per event
 * (stack traces are not copied to the application's console).
 *
 * <p>Start sequence (ADR-0009 C-78): a sink created while the agent starts holds its lines (at most
 * {@value #LINES_PER_MINUTE}) until the start ends. {@link #release()} (the start completed) writes
 * them with the rules above; {@link #discard()} (the start failed) drops them and every later line,
 * because the single {@code argos: agent disabled: <reason>} line is then the only output the agent
 * may produce on stderr.
 */
final class StderrFallbackSink implements LogSink {
  static final int LINES_PER_MINUTE = 10;
  private static final long MINUTE_MILLIS = 60_000L;
  private static final int MAX_TRACKED_MESSAGES = 256;

  private static final int HOLD = 0;
  private static final int RELEASED = 1;
  private static final int DISCARDED = 2;

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

  /** Set by the start sequence, read by the logger thread. */
  private volatile int mode;

  /** Lines held during the start sequence (logger thread only), at most LINES_PER_MINUTE. */
  private final List<LogRecord> heldRecords = new ArrayList<LogRecord>();

  private final List<String> heldLines = new ArrayList<String>();

  /** A sink that writes at once (outside the start sequence). */
  StderrFallbackSink(PrintStream stderr, String reason) {
    this(stderr, reason, false);
  }

  /**
   * @param holdUntilStarted true while the agent starts: lines are held until {@link #release()}
   */
  StderrFallbackSink(PrintStream stderr, String reason, boolean holdUntilStarted) {
    this.stderr = stderr;
    this.reason = reason;
    this.mode = holdUntilStarted ? HOLD : RELEASED;
  }

  /** The start completed: held lines are written at the next {@link #flush()} or write. */
  void release() {
    if (mode == HOLD) {
      mode = RELEASED;
    }
  }

  /** The start failed: nothing is written any more (C-78). */
  void discard() {
    mode = DISCARDED;
  }

  /** True while lines are held. */
  boolean holding() {
    return mode == HOLD;
  }

  @Override
  public void write(LogRecord record, String line) {
    if (!LogLevel.WARN.allows(record.level)) {
      return;
    }
    int current = mode;
    if (current == DISCARDED) {
      heldRecords.clear();
      heldLines.clear();
      return;
    }
    if (current == HOLD) {
      if (heldRecords.size() < LINES_PER_MINUTE) {
        heldRecords.add(record);
        heldLines.add(line);
      }
      return;
    }
    writeHeld();
    emit(record, line);
  }

  private void writeHeld() {
    for (int i = 0; i < heldRecords.size(); i++) {
      emit(heldRecords.get(i), heldLines.get(i));
    }
    heldRecords.clear();
    heldLines.clear();
  }

  private void emit(LogRecord record, String line) {
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
    int newline = line.indexOf('\n');
    stderr.println(newline < 0 ? line : line.substring(0, newline));
  }

  @Override
  public void flush() {
    int current = mode;
    if (current == RELEASED) {
      writeHeld();
    } else if (current == DISCARDED) {
      heldRecords.clear();
      heldLines.clear();
      return;
    }
    stderr.flush();
  }

  @Override
  public void close() {
    flush();
  }

  @Override
  public String describe() {
    return "stderr (limited to " + LINES_PER_MINUTE + " lines per minute: " + reason + ")";
  }
}
