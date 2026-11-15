/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: captures the agent log's formatted lines (stack traces included) for the tests of
 * other packages, at the given level, and restores the previous sink and level on {@link #close}.
 *
 * <pre>
 * try (LogCapture log = LogCapture.start(LogLevel.INFO)) {
 *   ...
 *   assertThat(log.linesAt("WARN")).hasSize(1);
 * }
 * </pre>
 */
public final class LogCapture implements AutoCloseable {
  private final LogPipeline pipeline = AgentLogger.pipeline();
  private final LogSink previousSink;
  private final LogLevel previousLevel;
  private final AgentLoggerTest.ListSink sink = new AgentLoggerTest.ListSink();

  private LogCapture(LogLevel level) {
    synchronized (pipeline) {
      previousSink = pipeline.sink();
      previousLevel = pipeline.level();
      // What earlier code queued (and the count of what it dropped) is not this test's: it goes to
      // the previous sink, or is thrown away when there is none.
      if (previousSink == null) {
        pipeline.setSink(new AgentLoggerTest.ListSink());
      }
      pipeline.drain();
      pipeline.setSink(sink);
      pipeline.setLevel(level);
    }
  }

  /** Captures from now on at {@code level} ({@code "INFO"}, {@code "DEBUG"}, ...). */
  public static LogCapture start(String level) {
    return new LogCapture(LogLevel.parse(level, LogLevel.INFO));
  }

  /** Every line written so far (the queue is drained first). */
  public List<String> lines() {
    // drain() holds the pipeline's monitor; so does the copy (the logger thread may drain too).
    synchronized (pipeline) {
      pipeline.drain();
      return new ArrayList<String>(sink.lines);
    }
  }

  /** The lines of {@code level} ({@code "WARN"}, ...), first line of each record only. */
  public List<String> linesAt(String level) {
    List<String> matching = new ArrayList<String>();
    for (String line : lines()) {
      String first = line.indexOf('\n') < 0 ? line : line.substring(0, line.indexOf('\n'));
      String[] parts = first.split(" +", 3);
      if (parts.length >= 2 && parts[1].equals(level)) {
        matching.add(first);
      }
    }
    return matching;
  }

  /** Everything written so far as one text (records joined by newlines). */
  public String text() {
    return String.join("\n", lines());
  }

  @Override
  public void close() {
    pipeline.drain();
    pipeline.setSink(previousSink);
    pipeline.setLevel(previousLevel);
  }
}
