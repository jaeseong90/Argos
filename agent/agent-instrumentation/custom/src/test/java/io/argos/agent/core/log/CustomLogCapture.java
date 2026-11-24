/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helper of the custom module (the core's {@code LogCapture} is a core test class): captures
 * the agent log's formatted lines at the given level and restores the previous sink and level on
 * {@link #close}. In the logger's package because the pipeline is package-private.
 */
public final class CustomLogCapture implements AutoCloseable {
  private final LogPipeline pipeline = AgentLogger.pipeline();
  private final LogSink previousSink;
  private final LogLevel previousLevel;
  private final ListSink sink = new ListSink();

  private CustomLogCapture(LogLevel level) {
    synchronized (pipeline) {
      previousSink = pipeline.sink();
      previousLevel = pipeline.level();
      if (previousSink == null) {
        pipeline.setSink(new ListSink());
      }
      pipeline.drain();
      pipeline.setSink(sink);
      pipeline.setLevel(level);
    }
  }

  /** Captures from now on at {@code level} ({@code "INFO"}, {@code "DEBUG"}, ...). */
  public static CustomLogCapture start(String level) {
    return new CustomLogCapture(LogLevel.parse(level, LogLevel.INFO));
  }

  /** The lines of {@code level} ({@code "WARN"}, ...), first line of each record only. */
  public List<String> linesAt(String level) {
    List<String> lines;
    synchronized (pipeline) {
      pipeline.drain();
      lines = new ArrayList<String>(sink.lines);
    }
    List<String> matching = new ArrayList<String>();
    for (String line : lines) {
      String first = line.indexOf('\n') < 0 ? line : line.substring(0, line.indexOf('\n'));
      String[] parts = first.split(" +", 3);
      if (parts.length >= 2 && parts[1].equals(level)) {
        matching.add(first);
      }
    }
    return matching;
  }

  @Override
  public void close() {
    pipeline.drain();
    pipeline.setSink(previousSink);
    pipeline.setLevel(previousLevel);
  }

  private static final class ListSink implements LogSink {
    final List<String> lines = new ArrayList<String>();

    @Override
    public void write(LogRecord record, String line) {
      lines.add(line);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public String describe() {
      return "custom module test";
    }
  }
}
