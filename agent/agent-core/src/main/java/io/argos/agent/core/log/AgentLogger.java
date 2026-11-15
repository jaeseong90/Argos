/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;

/**
 * The agent's own logger (10 §14, F-A35): no logging framework, nothing on stdout, a rolling file
 * in {@code log.dir}, stderr only as a rate-limited fallback when the directory is not writable.
 *
 * <p>Messages use {@code {}} placeholders; a {@link Throwable} as the last argument is logged with
 * its stack trace. Calls never block and never throw. Do not log collected data (SQL, parameters,
 * headers, URLs with query strings, user ids) at INFO, WARN or ERROR (SEC-26, 10 §14): the archTest
 * rule {@code LOG-01} accepts only constants, numbers, enums and allow-listed identifier sources as
 * arguments of those calls. DEBUG may contain such data (documented in docs/agent-config.md).
 *
 * <p>Start sequence (10 §4.3, ADR-0009 C-76, C-78): {@link #start()} at P3 starts the {@code
 * argos-logger} thread and queues records; {@link #configure(AgentConfig, File)} opens the
 * destination once the configuration is read: {@code log.dir}; when it is not set, {@code <agent
 * jar dir>/../logs} if writable, else {@code java.io.tmpdir/argos-agent-<pid>} (0700); when the
 * chosen directory is not writable, stderr limited to 10 lines per minute. Stderr lines are held
 * until {@link #startCompleted()}. {@link #startFailed(String, Throwable)} writes the failure to
 * the log file and flushes it; without a log file it prints the single line {@code argos: agent
 * disabled: <reason>} on stderr and nothing else.
 */
public final class AgentLogger {
  /** Name of the log directory next to the agent jar's directory ({@code <jar dir>/../logs}). */
  static final String DEFAULT_DIRECTORY_NAME = "logs";

  /** Prefix of the single stderr line of a failed start without a log file (C-78). */
  public static final String DISABLED_LINE_PREFIX = "argos: agent disabled: ";

  /** Upper bound of the reason text of that line. */
  static final int MAX_REASON_CHARS = 200;

  private static final LogPipeline PIPELINE = new LogPipeline();

  private static volatile File resolvedDirectory;

  private AgentLogger() {}

  /** P3 (10 §4.3): starts the {@code argos-logger} thread; records queue until a destination. */
  public static void start() {
    PIPELINE.start(ArgosThread.factory("argos-logger"));
  }

  /**
   * After P4: applies {@code log.level} and opens the destination ({@link #directory}); a stderr
   * fallback holds its lines until {@link #startCompleted()}. Returns a description of the
   * destination.
   */
  public static String configure(AgentConfig config, File agentJar) {
    return configure(config, agentJar, System.err, true);
  }

  static String configure(
      AgentConfig config, File agentJar, PrintStream stderr, boolean holdUntilStarted) {
    return configure(PIPELINE, config, agentJar, stderr, holdUntilStarted);
  }

  static String configure(
      LogPipeline pipeline,
      AgentConfig config,
      File agentJar,
      PrintStream stderr,
      boolean holdUntilStarted) {
    pipeline.setLevel(LogLevel.parse(config.logLevel(), LogLevel.INFO));
    long maxBytes = config.logMaxFileMb() * 1024L * 1024L;
    int maxFiles = config.logMaxFiles();
    File directory = directory(config, agentJar);
    resolvedDirectory = directory;
    LogSink sink;
    try {
      sink = RollingFileSink.open(directory, maxBytes, maxFiles);
    } catch (IOException | RuntimeException e) {
      sink =
          new StderrFallbackSink(
              stderr,
              "log directory " + directory + " is not writable (" + e + ")",
              holdUntilStarted);
    }
    pipeline.setSink(sink);
    return sink.describe();
  }

  /**
   * P3 and the destination at once (outside the start sequence: tests, {@code --check}); stderr
   * lines are not held.
   */
  public static String init(AgentConfig config, File agentJar) {
    start();
    return configure(config, agentJar, System.err, false);
  }

  /**
   * {@code log.dir}, else {@code <agent jar dir>/../logs} when it is (or can be made) writable,
   * else {@code java.io.tmpdir/argos-agent-<pid>} (10 §14 "로그 디렉터리 기본", ADR-0009 C-76). A
   * configured {@code log.dir} is used as given: when it is not writable the agent logs to the
   * stderr fallback, not to another directory.
   */
  public static File directory(AgentConfig config, File agentJar) {
    String configured = config.logDir();
    if (configured != null) {
      return new File(configured);
    }
    File jarDirectory = agentJar == null ? null : agentJar.getAbsoluteFile().getParentFile();
    File besideJar =
        jarDirectory == null || jarDirectory.getParentFile() == null
            ? null
            : new File(jarDirectory.getParentFile(), DEFAULT_DIRECTORY_NAME);
    if (besideJar != null && writableDirectory(besideJar)) {
      return besideJar;
    }
    return new File(System.getProperty("java.io.tmpdir"), "argos-agent-" + pid());
  }

  /** The directory chosen by {@link #configure} (null before). */
  public static File logDirectory() {
    return resolvedDirectory;
  }

  private static boolean writableDirectory(File directory) {
    try {
      RollingFileSink.createDirectory(directory);
      return directory.isDirectory() && directory.canWrite();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** The process id (only on the temp-directory fallback path: it loads the management API). */
  private static String pid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int at = name.indexOf('@');
    return at > 0 ? name.substring(0, at) : name;
  }

  /** True when log lines go to a file (not to the stderr fallback, not nowhere). */
  public static boolean hasLogFile() {
    return PIPELINE.sink() instanceof RollingFileSink;
  }

  /** P8: the start completed; stderr fallback lines held during the start are written. */
  public static void startCompleted() {
    startCompleted(PIPELINE);
  }

  static void startCompleted(LogPipeline pipeline) {
    LogSink sink = pipeline.sink();
    if (sink instanceof StderrFallbackSink) {
      ((StderrFallbackSink) sink).release();
      pipeline.requestFlush();
    }
  }

  /**
   * The start failed and the agent is off (T-AG-044, ADR-0009 C-78). With a log file: {@code
   * summary} and {@code error} are logged at ERROR and written before this method returns (the JVM
   * may exit right after premain). Without a log file (stderr fallback, or no destination): queued
   * records are dropped and exactly one line {@value #DISABLED_LINE_PREFIX}{@code <reason>} goes to
   * stderr; nothing else is written afterwards. The reason is one line of at most {@value
   * #MAX_REASON_CHARS} characters with tokens masked (SEC-06).
   *
   * @return true when the failure was written to a log file
   */
  public static boolean startFailed(String summary, Throwable error) {
    return startFailed(PIPELINE, summary, error, System.err);
  }

  static boolean startFailed(
      LogPipeline pipeline, String summary, Throwable error, PrintStream stderr) {
    try {
      LogSink sink = pipeline.sink();
      if (sink instanceof RollingFileSink) {
        pipeline.enqueue(LogLevel.ERROR, summary, error);
        pipeline.drain();
        return pipeline.sink() instanceof RollingFileSink;
      }
      if (sink instanceof StderrFallbackSink) {
        ((StderrFallbackSink) sink).discard();
      }
      pipeline.silence();
      pipeline.discardQueued();
      stderr.println(DISABLED_LINE_PREFIX + reason(error));
      stderr.flush();
    } catch (Throwable ignored) {
      // Never fail the application's premain.
    }
    return false;
  }

  /** One line, at most {@value #MAX_REASON_CHARS} characters, tokens masked. */
  static String reason(Throwable error) {
    String text =
        error == null
            ? "unknown error"
            : error.getClass().getName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
    text = TokenHolder.maskTokens(text).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    return text.length() > MAX_REASON_CHARS ? text.substring(0, MAX_REASON_CHARS) + "..." : text;
  }

  /** Stops the logger thread after writing what is queued (shutdown hook). */
  public static void shutdown(long timeoutMillis) {
    PIPELINE.stop(timeoutMillis);
  }

  /** Writes queued messages now (tests, shutdown). */
  public static void flush() {
    PIPELINE.drain();
  }

  public static boolean isDebugEnabled() {
    return PIPELINE.level().allows(LogLevel.DEBUG);
  }

  /** Records dropped because the logger queue was full. */
  public static long droppedRecords() {
    return PIPELINE.dropped();
  }

  /** Where log lines go (banner, self diagnostics). */
  public static String destination() {
    LogSink sink = PIPELINE.sink();
    return sink == null ? "not initialised" : sink.describe();
  }

  public static void error(String format, Object... args) {
    log(LogLevel.ERROR, format, args);
  }

  public static void warn(String format, Object... args) {
    log(LogLevel.WARN, format, args);
  }

  public static void info(String format, Object... args) {
    log(LogLevel.INFO, format, args);
  }

  public static void debug(String format, Object... args) {
    log(LogLevel.DEBUG, format, args);
  }

  /** WARN the first time {@code key} is seen, nothing afterwards ("WARN 1회", 10 §5.2). */
  public static void warnOnce(String key, String format, Object... args) {
    if (PIPELINE.level().allows(LogLevel.WARN) && PIPELINE.firstTime(key)) {
      log(LogLevel.WARN, format, args);
    }
  }

  static void log(LogLevel level, String format, Object[] args) {
    try {
      if (!PIPELINE.level().allows(level)) {
        return;
      }
      Throwable error = null;
      if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
        error = (Throwable) args[args.length - 1];
      }
      PIPELINE.enqueue(level, LogFormat.format(format, args), error);
    } catch (Throwable ignored) {
      // Logging never fails the caller.
    }
  }

  /** The pipeline (package tests). */
  static LogPipeline pipeline() {
    return PIPELINE;
  }
}
