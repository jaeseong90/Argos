/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent's own logger (10 §14, F-A35): format, queue bound, rolling file, stderr fallback. */
@Tag("req:F-A35")
class AgentLoggerTest {
  @TempDir File dir;

  /** Collects lines like a file sink. */
  static final class ListSink implements LogSink {
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
      return "list";
    }
  }

  @Test
  @DisplayName("{} placeholders, trailing Throwable kept for the stack trace, missing arguments")
  void format() {
    assertThat(LogFormat.format("a {} b {}", new Object[] {1, "x"})).isEqualTo("a 1 b x");
    assertThat(LogFormat.format("a {} b {}", new Object[] {1})).isEqualTo("a 1 b {}");
    assertThat(LogFormat.format("no placeholders", new Object[] {1})).isEqualTo("no placeholders");
    assertThat(LogFormat.format(null, null)).isEqualTo("null");
  }

  @Test
  @DisplayName("the pipeline writes level, thread and message; errors carry a bounded stack trace")
  void pipeline() {
    LogPipeline pipeline = new LogPipeline();
    ListSink sink = new ListSink();
    pipeline.setSink(sink);
    pipeline.enqueue(LogLevel.WARN, "careful", null);
    pipeline.enqueue(LogLevel.ERROR, "failed", new IllegalStateException("boom"));
    assertThat(pipeline.drain()).isEqualTo(2);
    assertThat(sink.lines.get(0))
        .matches(
            "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}(Z|[+-]\\d\\d:\\d\\d) WARN  \\[.+\\] careful");
    assertThat(sink.lines.get(1))
        .contains("ERROR [")
        .contains("failed\njava.lang.IllegalStateException: boom\n\tat ");
  }

  @Test
  @Tag("req:N-02")
  @DisplayName("the queue is bounded: overflow is dropped, counted and reported once drained")
  void boundedQueue() {
    LogPipeline pipeline = new LogPipeline();
    for (int i = 0; i < LogPipeline.CAPACITY + 7; i++) {
      pipeline.enqueue(LogLevel.INFO, "m" + i, null);
    }
    assertThat(pipeline.dropped()).isEqualTo(7);
    ListSink sink = new ListSink();
    pipeline.setSink(sink);
    pipeline.drain();
    assertThat(sink.lines).hasSize(LogPipeline.CAPACITY + 1);
    assertThat(sink.lines.get(LogPipeline.CAPACITY)).contains("7 log record(s) dropped");
    assertThat(pipeline.firstTime("k")).isTrue();
    assertThat(pipeline.firstTime("k")).isFalse();
  }

  @Test
  @DisplayName("rolling file: argos-agent.log rolls into .1 .. .(max-files - 1), oldest dropped")
  void rollingFile() throws IOException {
    File logs = new File(dir, "logs");
    RollingFileSink sink = RollingFileSink.open(logs, 100, 3);
    LogRecord record = new LogRecord(0, LogLevel.INFO, "t", "m", null);
    for (int i = 0; i < 10; i++) {
      sink.write(record, line(i));
    }
    sink.flush();
    sink.close();
    assertThat(logs.list())
        .containsExactlyInAnyOrder("argos-agent.log", "argos-agent.log.1", "argos-agent.log.2");
    for (String name : logs.list()) {
      assertThat(new File(logs, name).length()).isLessThanOrEqualTo(100);
    }
    List<String> newest =
        Files.readAllLines(new File(logs, "argos-agent.log").toPath(), StandardCharsets.UTF_8);
    assertThat(newest).last().isEqualTo(line(9));
  }

  private static String line(int i) {
    StringBuilder text = new StringBuilder("line-" + i + "-");
    while (text.length() < 39) {
      text.append('x');
    }
    return text.toString();
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-045 stderr fallback: at most 10 lines per minute, same message once, WARN+ only")
  void stderrFallback() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream stderr = new PrintStream(bytes, true);
    StderrFallbackSink sink = new StderrFallbackSink(stderr, "test");
    long now = 1_000_000;
    sink.write(new LogRecord(now, LogLevel.INFO, "t", "info", null), "info");
    for (int i = 0; i < 3; i++) {
      sink.write(new LogRecord(now, LogLevel.WARN, "t", "same", null), "same");
    }
    for (int i = 0; i < 20; i++) {
      sink.write(
          new LogRecord(now + i, LogLevel.ERROR, "t", "e" + i, null), "e" + i + "\n\tat stack");
    }
    String within = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    String[] lines = within.split("\\R");
    assertThat(lines).hasSize(StderrFallbackSink.LINES_PER_MINUTE);
    assertThat(lines[0]).isEqualTo("same");
    assertThat(within).doesNotContain("info").doesNotContain("at stack");
    sink.write(new LogRecord(now + 61_000, LogLevel.WARN, "t", "same", null), "same");
    assertThat(new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\\R"))
        .hasSize(StderrFallbackSink.LINES_PER_MINUTE + 1);
    assertThat(sink.describe()).contains("10 lines per minute");
  }

  @Test
  @DisplayName("timestamps match java.time ISO-8601 with offset (UTC, +09:00, -03:30, leap day)")
  void timestamps() {
    long[] instants = {0L, 951_782_400_123L, 1_792_823_369_865L, -86_400_001L, 4_102_444_800_999L};
    for (String zoneId : new String[] {"UTC", "Asia/Seoul", "America/St_Johns"}) {
      Timestamps timestamps = new Timestamps(TimeZone.getTimeZone(zoneId));
      DateTimeFormatter reference =
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
              .withZone(ZoneId.of(zoneId));
      for (long instant : instants) {
        assertThat(timestamps.format(instant))
            .as(zoneId + " " + instant)
            .isEqualTo(reference.format(Instant.ofEpochMilli(instant)));
      }
    }
  }

  @Test
  @DisplayName("log directory: log.dir, else <jar dir>/../logs when writable")
  void directory() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("log.dir", new File(dir, "explicit").getPath());
    assertThat(AgentLogger.directory(AgentConfig.of(values), null))
        .isEqualTo(new File(dir, "explicit"));
    File jar = new File(new File(dir, "agent"), "argos-agent.jar");
    assertThat(AgentLogger.directory(AgentConfig.of(Collections.<String, String>emptyMap()), jar))
        .isEqualTo(new File(dir, "logs"));
    assertThat(new File(dir, "logs")).isDirectory();
  }
}
