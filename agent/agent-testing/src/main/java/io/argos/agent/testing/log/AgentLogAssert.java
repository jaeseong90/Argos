/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on the agent log and the app's console of a normal scenario (18 §5.10, T-AG-060,
 * F-A35; 03 §3.1 "정상 시나리오 로그 ERROR 0·WARN 0·stdout/stderr 0"):
 *
 * <pre>
 * assertThatAgentLog(app.agentLog())
 *     .hasStartBanner()
 *     .hasNoErrors()
 *     .hasOnlyAllowedWarnings()          // fixtures/log-allowlist/agent-info.txt
 *     .doesNotContainSeedSentinel();     // T-SEC-26 PII seed (ARGOS_SEED)
 * AgentLogAssert.assertQuietConsole(app.stdoutLines(), app.stderrLines());
 * </pre>
 *
 * Agent log line format (agent-core {@code AgentLogger}): {@code <ISO time> <LEVEL> [<thread>]
 * <message>}; stack trace lines follow without a timestamp.
 */
public final class AgentLogAssert extends AbstractAssert<AgentLogAssert, String> {
  /** System property with the allow-list path (set by agent-testing's Gradle test tasks). */
  public static final String ALLOWLIST_PROPERTY = "argos.test.logAllowlist";

  /** The PII/XSS seed sentinel of the sample apps (18 §4.3, DSN-18-09). */
  public static final String SEED_SENTINEL = "ARGOS_SEED";

  static final Pattern LINE =
      Pattern.compile("^(\\S+)\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\[([^\\]]*)\\]\\s?(.*)$");

  /** One parsed log line. */
  public record LogLine(String level, String thread, String message, String raw) {}

  private AgentLogAssert(String actual) {
    super(actual, AgentLogAssert.class);
  }

  public static AgentLogAssert assertThatAgentLog(String log) {
    return new AgentLogAssert(log);
  }

  /** The log lines with a level (continuation lines are not returned). */
  public static List<LogLine> parse(String log) {
    List<LogLine> lines = new ArrayList<>();
    for (String raw : log.split("\\R", -1)) {
      Matcher matcher = LINE.matcher(raw);
      if (matcher.matches()) {
        lines.add(new LogLine(matcher.group(2), matcher.group(3), matcher.group(4), raw));
      }
    }
    return lines;
  }

  /** Lines of {@code level}. */
  public static List<LogLine> linesAt(String log, String level) {
    List<LogLine> matching = new ArrayList<>();
    for (LogLine line : parse(log)) {
      if (line.level().equals(level)) {
        matching.add(line);
      }
    }
    return matching;
  }

  /** The allow-list of the normal scenario ({@value #ALLOWLIST_PROPERTY}). */
  public static List<Pattern> defaultAllowlist() {
    String path = System.getProperty(ALLOWLIST_PROPERTY);
    return readAllowlist(
        path != null && !path.isBlank()
            ? Path.of(path)
            : Path.of("fixtures", "log-allowlist", "agent-info.txt"));
  }

  /** Reads an allow-list file: one regular expression per line, {@code #} comments. */
  public static List<Pattern> readAllowlist(Path file) {
    try {
      List<Pattern> patterns = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String entry = line.strip();
        if (!entry.isEmpty() && !entry.startsWith("#")) {
          patterns.add(Pattern.compile(entry));
        }
      }
      return patterns;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The agent wrote its start banner ({@code Argos agent <version> ... started in}). */
  public AgentLogAssert hasStartBanner() {
    isNotNull();
    if (!actual.contains("Argos agent ") || !actual.contains(" started in ")) {
      failWithMessage("expected the agent's start banner in the agent log:%n%s", actual);
    }
    return this;
  }

  /** No ERROR line. */
  public AgentLogAssert hasNoErrors() {
    isNotNull();
    List<LogLine> errors = linesAt(actual, "ERROR");
    if (!errors.isEmpty()) {
      failWithMessage("expected no ERROR in the agent log but found:%n%s", raw(errors));
    }
    return this;
  }

  /** No WARN line at all (the 03 §3.1 goal). */
  public AgentLogAssert hasNoWarnings() {
    isNotNull();
    List<LogLine> warnings = linesAt(actual, "WARN");
    if (!warnings.isEmpty()) {
      failWithMessage("expected no WARN in the agent log but found:%n%s", raw(warnings));
    }
    return this;
  }

  /** Every WARN line matches the normal-scenario allow-list (T-AG-060). */
  public AgentLogAssert hasOnlyAllowedWarnings() {
    return hasOnlyAllowedWarnings(defaultAllowlist());
  }

  /** Every WARN line matches one of {@code allowlist} (whole message). */
  public AgentLogAssert hasOnlyAllowedWarnings(List<Pattern> allowlist) {
    isNotNull();
    List<LogLine> unexpected = new ArrayList<>();
    for (LogLine line : linesAt(actual, "WARN")) {
      boolean allowed = false;
      for (Pattern pattern : allowlist) {
        if (pattern.matcher(line.message()).matches()) {
          allowed = true;
          break;
        }
      }
      if (!allowed) {
        unexpected.add(line);
      }
    }
    if (!unexpected.isEmpty()) {
      failWithMessage(
          "WARN lines outside the log allow-list (fixtures/log-allowlist/agent-info.txt):%n%s",
          raw(unexpected));
    }
    return this;
  }

  /** {@link #hasNoErrors()} and {@link #hasOnlyAllowedWarnings()}: the T-AG-060 log rule. */
  public AgentLogAssert isNormalScenarioLog() {
    return hasNoErrors().hasOnlyAllowedWarnings();
  }

  /** No PII seed sentinel ({@value #SEED_SENTINEL}, T-SEC-26). */
  public AgentLogAssert doesNotContainSeedSentinel() {
    return doesNotContainText(SEED_SENTINEL);
  }

  /**
   * The text does not appear anywhere in the log (a token, T-SEC-06; a secret). The failure message
   * names the line number only: printing the text (or the line) would put the secret into the test
   * output (11 §11, SEC-06).
   */
  public AgentLogAssert doesNotContainText(String text) {
    isNotNull();
    int index = actual.indexOf(text);
    if (index >= 0) {
      long line = actual.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
      failWithMessage(
          "expected the agent log not to contain the given text (%s characters, not printed) but"
              + " line %s does",
          text.length(), line);
    }
    return this;
  }

  public AgentLogAssert contains(String text) {
    isNotNull();
    if (!actual.contains(text)) {
      failWithMessage("expected the agent log to contain <%s>:%n%s", text, actual);
    }
    return this;
  }

  /** Exactly {@code count} WARN lines whose message contains {@code text} (e.g. "WARN 1회"). */
  public AgentLogAssert hasWarningCount(String text, int count) {
    isNotNull();
    long found = linesAt(actual, "WARN").stream().filter(l -> l.message().contains(text)).count();
    if (found != count) {
      failWithMessage(
          "expected %s WARN line(s) containing <%s> but found %s:%n%s", count, text, found, actual);
    }
    return this;
  }

  /**
   * The agent announced the injected fault {@code <kind>:<module>} of {@code argos.test.faults}
   * with exactly one WARN (18 §5.7): {@code kind} is {@code advice-throw}, {@code transform-error}
   * or {@code helper-nocdef}. A fault test asserts it, so that it cannot pass without its fault (a
   * spec that names no installed module, or a point-level spec, injects nothing and gets a
   * different WARN).
   */
  public AgentLogAssert hasInjectedFault(String kind, String module) {
    isNotNull();
    String message = injectedFaultMessage(kind, module);
    long found = linesAt(actual, "WARN").stream().filter(l -> l.message().equals(message)).count();
    if (found != 1) {
      failWithMessage(
          "expected one WARN <%s> (fault %s:%s injected) but found %s:%n%s",
          message, kind, module, found, actual);
    }
    return this;
  }

  /** The agent's WARN message for an injected fault (agent-core {@code TestFaults.announce}). */
  static String injectedFaultMessage(String kind, String module) {
    return switch (kind) {
      case "advice-throw" ->
          "argos.test.faults: advice of module " + module + " throws (test fault)";
      case "transform-error" ->
          "argos.test.faults: transformations of module " + module + " fail (test fault)";
      case "helper-nocdef" ->
          "argos.test.faults: helper classes of module "
              + module
              + " are not injected (test fault)";
      default ->
          throw new IllegalArgumentException(
              "unknown fault kind " + kind + " (advice-throw, transform-error, helper-nocdef)");
    };
  }

  /**
   * T-AG-060 console rule: stdout holds only the application's own log lines (no foreign line, no
   * agent mention), stderr holds nothing but the JVM's CDS notice ({@link AppOutput}).
   */
  public static void assertQuietConsole(List<String> stdout, List<String> stderr) {
    List<String> foreign = AppOutput.foreignStdoutLines(stdout);
    List<String> mentions = AppOutput.agentMentions(stdout);
    List<String> unexpected = AppOutput.unexpectedStderrLines(stderr);
    if (!foreign.isEmpty() || !mentions.isEmpty() || !unexpected.isEmpty()) {
      throw new AssertionError(
          "T-AG-060: console output beyond the application's own:\nforeign stdout lines: "
              + foreign
              + "\nagent mentions on stdout: "
              + mentions
              + "\nstderr lines other than the JVM's CDS notice: "
              + unexpected);
    }
  }

  private static String raw(List<LogLine> lines) {
    StringBuilder out = new StringBuilder();
    for (LogLine line : lines) {
      out.append(line.raw()).append('\n');
    }
    return out.toString();
  }
}
