/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks of a sample app's console output for agent output (T-AG-060 PoC, F-A35: the agent writes
 * nothing to stdout or stderr). The agent never prints (ArchUnit AGT-02); these checks show it on a
 * running application:
 *
 * <ul>
 *   <li>{@link #foreignStdoutLines}: stdout lines that are neither a log line of the application
 *       (Spring Boot console format, banner off) nor a continuation of one (stack trace lines);
 *   <li>{@link #agentMentions}: lines that name the agent ({@code io.argos.agent}, {@code Argos
 *       agent}, {@code [argos});
 *   <li>{@link #unexpectedStderrLines}: stderr lines other than the JVM's CDS notice (10 §14
 *       "CDS").
 * </ul>
 */
public final class AppOutput {
  /**
   * Spring Boot 2.x / 3.x / 4.x console log line: ISO-like timestamp, then the level ({@code
   * 2026-10-24T16:47:30.409+09:00 INFO 123 --- [app] [main] ...}, Boot 2: {@code 2026-10-24
   * 16:47:30.409 INFO 123 --- [main] ...}).
   */
  static final Pattern BOOT_LOG_LINE =
      Pattern.compile(
          "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\S*\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\b.*");

  /**
   * Continuation lines of a multi-line log message: indented lines (stack frames, Hibernate's
   * tab-indented "Database info" block), exception headers and blank lines.
   */
  static final Pattern CONTINUATION =
      Pattern.compile(
          "^(\\s+\\S.*"
              + "|\\s*Caused by: .*"
              + "|\\s*Suppressed: .*"
              + "|\\s*Wrapped by: .*"
              + "|[\\w$.]+(Exception|Error|Throwable|Fault)(: .*)?"
              + "|\\s*)$");

  /** The JVM's notice when a jar is appended to the bootstrap class path with CDS on. */
  public static final Pattern CDS_NOTICE =
      Pattern.compile(
          "(OpenJDK|Java HotSpot\\(TM\\)) 64-Bit Server VM warning: Sharing is only supported for"
              + " boot loader classes because bootstrap classpath has been appended");

  private static final Pattern AGENT_MENTION =
      Pattern.compile("(io\\.argos\\.agent|Argos agent|\\[argos)", Pattern.CASE_INSENSITIVE);

  private AppOutput() {}

  /**
   * Stdout lines that are not application log lines or their continuations. Lines before the first
   * log line count as foreign too (the agent starts before the application logs anything).
   */
  public static List<String> foreignStdoutLines(List<String> stdout) {
    List<String> foreign = new ArrayList<>();
    boolean inLog = false;
    for (String line : stdout) {
      if (BOOT_LOG_LINE.matcher(line).matches()) {
        inLog = true;
        continue;
      }
      if (inLog && CONTINUATION.matcher(line).matches()) {
        continue;
      }
      foreign.add(line);
    }
    return foreign;
  }

  /** Lines that name the agent. */
  public static List<String> agentMentions(List<String> lines) {
    List<String> mentions = new ArrayList<>();
    for (String line : lines) {
      if (AGENT_MENTION.matcher(line).find()) {
        mentions.add(line);
      }
    }
    return mentions;
  }

  /** Non-blank stderr lines other than the JVM's CDS notice. */
  public static List<String> unexpectedStderrLines(List<String> stderr) {
    List<String> unexpected = new ArrayList<>();
    for (String line : stderr) {
      if (!line.isBlank() && !CDS_NOTICE.matcher(line).matches()) {
        unexpected.add(line);
      }
    }
    return unexpected;
  }
}
