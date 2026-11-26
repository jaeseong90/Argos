/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.log;

import io.argos.agent.testing.leak.TomcatRedeployDriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judgement of a standalone Tomcat's own log after a WAR run with the agent (WO-05 r1-new-1, 18
 * §5.9 "Tomcat 로그에 ThreadLocal 누수 경고 0", F-A36): a pure function of the log lines ({@code
 * RunningApp.tomcatLogLines()}), unit-tested on fixed lines.
 *
 * <ul>
 *   <li><b>SEVERE</b>: every record of level SEVERE.
 *   <li><b>Agent warnings</b>: every WARNING or SEVERE record whose line names the agent: its
 *       packages ({@code io.argos.agent}, shaded ones included), its jar ({@code argos-agent}), its
 *       threads ({@code argos-<name>}), its settings ({@code argos.<key>}) or its log prefix
 *       ({@code argos:}), or whose stack trace has a frame of the agent (below). The sample apps'
 *       own package {@code io.argos.sample} and file paths that merely contain the repository name
 *       ({@code .../Argos/agent/...}) are not agent mentions.
 *   <li><b>ThreadLocal leaks</b>: Tomcat's {@code WebappClassLoaderBase} warnings about thread
 *       locals a web application left behind ({@link
 *       TomcatRedeployDriver#threadLocalLeakWarnings}), logged when Tomcat stops the application.
 * </ul>
 *
 * <p>A sample application can produce SEVERE records and leak warnings of its own (the WAR of
 * shop-boot3 leaves Netty's {@code InternalThreadLocalMap} of its WebClient in Tomcat's threads):
 * {@link Findings#beyond(Findings)} keeps only the records that a run without the agent did not log
 * as well, so the agent is judged on what it adds. Agent warnings are never excused.
 *
 * <p>Records are JULI's {@code OneLineFormatter} lines ({@code dd-MMM-yyyy HH:mm:ss.SSS LEVEL
 * [thread] source message}; the harness starts Tomcat with an English locale, and the level names
 * of the current locale are accepted too). Continuation lines (stack traces) belong to the record
 * above them: they are not records of their own, but an agent frame ({@code io.argos.agent.},
 * shaded packages included) in the stack of a WARNING or SEVERE record makes that record an agent
 * warning. The exception is the stack that Tomcat's {@code clearReferencesThreads} warning prints:
 * it is the current stack of an application thread that was not stopped, and an application thread
 * can be inside instrumented code at that moment (its record line is still judged).
 */
public final class TomcatLogCheck {
  /** A record line: date, time, level, then the thread in brackets. */
  private static final Pattern RECORD =
      Pattern.compile("^\\S+ \\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})? (\\S+) \\[");

  /** The agent's own identifiers (class comment). */
  static final Pattern AGENT_MENTION =
      Pattern.compile(
          "io[./]argos[./]agent|argos-agent|\\bargos-[a-z]|(?:\\b|-D)argos\\.(?!sample\\b)[a-z]"
              + "|\\bargos:",
          Pattern.CASE_INSENSITIVE);

  /**
   * What the log shows.
   *
   * @param severe SEVERE record lines
   * @param agentWarnings WARNING or SEVERE record lines that name the agent
   * @param threadLocalLeaks Tomcat's ThreadLocal leak warnings
   */
  public record Findings(
      List<String> severe, List<String> agentWarnings, List<String> threadLocalLeaks) {
    public Findings {
      severe = List.copyOf(severe);
      agentWarnings = List.copyOf(agentWarnings);
      threadLocalLeaks = List.copyOf(threadLocalLeaks);
    }

    /**
     * These findings without the SEVERE records and leak warnings that {@code baseline} (the same
     * application in the same Tomcat without the agent) has too, compared without their timestamp
     * and object hash codes ({@link #normalized}); agent warnings are kept whatever the baseline.
     */
    public Findings beyond(Findings baseline) {
      return new Findings(
          without(severe, baseline.severe()),
          agentWarnings,
          without(threadLocalLeaks, baseline.threadLocalLeaks()));
    }

    private static List<String> without(List<String> lines, List<String> baseline) {
      java.util.Set<String> known = new java.util.HashSet<>();
      for (String line : baseline) {
        known.add(normalized(line));
      }
      List<String> kept = new ArrayList<>();
      for (String line : lines) {
        if (!known.contains(normalized(line))) {
          kept.add(line);
        }
      }
      return kept;
    }

    /** True when there is none of the three. */
    public boolean clean() {
      return severe.isEmpty() && agentWarnings.isEmpty() && threadLocalLeaks.isEmpty();
    }

    @Override
    public String toString() {
      return "Tomcat log: "
          + severe.size()
          + " SEVERE "
          + severe
          + ", "
          + agentWarnings.size()
          + " WARNING naming the agent "
          + agentWarnings
          + ", "
          + threadLocalLeaks.size()
          + " ThreadLocal leak warnings "
          + threadLocalLeaks;
    }
  }

  private TomcatLogCheck() {}

  /** Judges the lines of a Tomcat log (see the class comment). */
  public static Findings judge(List<String> lines) {
    List<String> severe = new ArrayList<>();
    List<String> agentWarnings = new ArrayList<>();
    // The WARNING or SEVERE record whose stack the continuation lines belong to (null: none, or
    // one that is already an agent warning, or the stack of an application thread).
    String stackOwner = null;
    for (String line : lines) {
      String level = level(line);
      if (level == null) {
        if (stackOwner != null && AGENT_FRAME.matcher(line).find()) {
          agentWarnings.add(stackOwner + " [stack: " + line.strip() + "]");
          stackOwner = null;
        }
        continue;
      }
      stackOwner = null;
      boolean severeRecord = isLevel(level, Level.SEVERE);
      if (severeRecord) {
        severe.add(line);
      }
      if (severeRecord || isLevel(level, Level.WARNING)) {
        if (mentionsAgent(line)) {
          agentWarnings.add(line);
        } else if (!line.contains(THREAD_STACK_SOURCE)) {
          stackOwner = line;
        }
      }
    }
    return new Findings(severe, agentWarnings, TomcatRedeployDriver.threadLocalLeakWarnings(lines));
  }

  /** A stack frame of the agent's code in a continuation line (shaded packages included). */
  static final Pattern AGENT_FRAME = Pattern.compile("\\bio\\.argos\\.agent\\.");

  /**
   * The source of Tomcat's warning about an application thread that was not stopped, followed by
   * that thread's current stack (class comment: not judged).
   */
  static final String THREAD_STACK_SOURCE = "WebappClassLoaderBase.clearReferencesThreads";

  /**
   * A record line without its date and time, with its thread as {@code [*]} and every
   * {@code @<hex>} object hash code as {@code @#}: the same message of two runs compares equal. The
   * thread name carries the run's port and pool number ({@code http-nio-127.0.0.1-61476-exec-8}),
   * so the same leak report written while the manager undeploys an application differs only there
   * (WO-50 redeploy runs).
   */
  static String normalized(String line) {
    Matcher matcher = RECORD.matcher(line);
    String text = line;
    if (matcher.find()) {
      text = line.substring(matcher.start(1));
      text = THREAD.matcher(text).replaceFirst("$1 [*]");
    }
    return HASH_CODE.matcher(text).replaceAll("@#");
  }

  /** The level and the bracketed thread name at the start of a normalized record. */
  private static final Pattern THREAD = Pattern.compile("^(\\S+) \\[[^\\]]*\\]");

  private static final Pattern HASH_CODE = Pattern.compile("@[0-9a-fA-F]+");

  /** The level token of a record line, or null for a continuation line. */
  static String level(String line) {
    Matcher matcher = RECORD.matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** True when {@code line} names the agent (class comment). */
  static boolean mentionsAgent(String line) {
    return AGENT_MENTION.matcher(line).find();
  }

  private static boolean isLevel(String token, Level level) {
    return token.equals(level.getName())
        || token.equals(level.getLocalizedName())
        || token.toUpperCase(Locale.ROOT).equals(level.getName());
  }
}
