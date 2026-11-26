/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The agent's own log ({@code log.dir}, {@code argos-agent.log} and its rolled files): {@code
 * <ISO-8601 time with offset> <LEVEL> [<thread>] <message>} per record (agent-core {@code
 * LogFormat}); continuation lines without a time belong to the record before and are not counted.
 *
 * @param lines the records, oldest first
 */
public record AgentLog(List<Line> lines) {

  /** One record. */
  public record Line(Instant time, String level, String thread, String message) {}

  static final Pattern RECORD =
      Pattern.compile("^(\\S+)\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\[([^\\]]*)\\]\\s?(.*)$");

  /**
   * The agent's WARN of a rejected retransformation (agent-core {@code RetransformFailureListener},
   * ADR-0010 C-257 (2)): "retransformation of N already loaded class(es) failed ...".
   */
  public static final Pattern RETRANSFORM_FAILURE =
      Pattern.compile("retransformation of \\d+ already loaded class\\S* failed");

  /** Copies. */
  public AgentLog {
    lines = List.copyOf(lines);
  }

  /** Parses log text (records only). */
  public static AgentLog parse(String text) {
    List<Line> lines = new ArrayList<>();
    for (String raw : text.lines().toList()) {
      Matcher m = RECORD.matcher(raw);
      if (!m.matches()) {
        continue;
      }
      Instant time;
      try {
        time = OffsetDateTime.parse(m.group(1)).toInstant();
      } catch (DateTimeParseException e) {
        continue;
      }
      lines.add(new Line(time, m.group(2), m.group(3), m.group(4)));
    }
    lines.sort(Comparator.comparing(Line::time));
    return new AgentLog(lines);
  }

  /**
   * Reads every {@code argos-agent*.log*} file of the agent's log directory (rolled files first);
   * an absent directory is an empty log.
   */
  public static AgentLog read(Path logDir) {
    if (!Files.isDirectory(logDir)) {
      return new AgentLog(List.of());
    }
    StringBuilder text = new StringBuilder();
    try (Stream<Path> files = Files.list(logDir)) {
      for (Path file :
          files
              .filter(p -> p.getFileName().toString().startsWith("argos-agent"))
              .sorted(Comparator.reverseOrder())
              .toList()) {
        text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parse(text.toString());
  }

  /** The records of [from, to] that match {@code filter}. */
  public List<Line> between(Instant from, Instant to, Predicate<Line> filter) {
    return lines.stream()
        .filter(l -> !l.time().isBefore(from) && !l.time().isAfter(to))
        .filter(filter)
        .toList();
  }

  /** ERROR records of [from, to]. */
  public List<Line> errors(Instant from, Instant to) {
    return between(from, to, l -> l.level().equals("ERROR"));
  }

  /** Records of [from, to] that report a rejected retransformation (C-257 (2)). */
  public List<Line> retransformFailures(Instant from, Instant to) {
    return between(from, to, l -> RETRANSFORM_FAILURE.matcher(l.message()).find());
  }
}
