/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;

/**
 * The agent's own logger (10 §14, F-A35): no logging framework, nothing on stdout, a rolling file
 * in {@code log.dir}, stderr only as a rate-limited fallback when the directory is not writable.
 *
 * <p>Messages use {@code {}} placeholders; a {@link Throwable} as the last argument is logged with
 * its stack trace. Calls never block and never throw. Do not log collected data (SQL, parameters,
 * headers, URLs with query strings, user ids) at INFO or below (10 §14).
 */
public final class AgentLogger {
  /** Name of the log directory next to the agent jar's directory ({@code <jar dir>/../logs}). */
  static final String DEFAULT_DIRECTORY_NAME = "logs";

  private static final LogPipeline PIPELINE = new LogPipeline();

  private AgentLogger() {}

  /**
   * P3 (10 §4.3): resolves {@code log.dir}, opens the log file (or the stderr fallback) and starts
   * the {@code argos-logger} thread. Returns a description of the destination.
   */
  public static String init(AgentConfig config, File agentJar) {
    PIPELINE.setLevel(LogLevel.parse(config.get(ConfigKeys.LOG_LEVEL, "INFO"), LogLevel.INFO));
    long maxBytes = config.getInt(ConfigKeys.LOG_MAX_FILE_MB, 10, 1, 1024) * 1024L * 1024L;
    int maxFiles = config.getInt(ConfigKeys.LOG_MAX_FILES, 5, 1, 100);
    File directory = directory(config, agentJar);
    LogSink sink;
    try {
      sink = RollingFileSink.open(directory, maxBytes, maxFiles);
    } catch (IOException | RuntimeException e) {
      sink =
          new StderrFallbackSink(
              System.err, "log directory " + directory + " is not writable (" + e + ")");
    }
    PIPELINE.setSink(sink);
    PIPELINE.start(ArgosThread.factory("argos-logger"));
    return sink.describe();
  }

  /**
   * {@code log.dir}, else {@code <agent jar dir>/../logs} when it is (or can be made) writable,
   * else {@code java.io.tmpdir/argos-agent-<pid>} (10 §14 "로그 디렉터리 기본").
   */
  public static File directory(AgentConfig config, File agentJar) {
    String configured = config.get(ConfigKeys.LOG_DIR, null);
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

  private static boolean writableDirectory(File directory) {
    try {
      RollingFileSink.createDirectory(directory);
      return directory.isDirectory() && directory.canWrite();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static String pid() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int at = name.indexOf('@');
    return at > 0 ? name.substring(0, at) : name;
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
