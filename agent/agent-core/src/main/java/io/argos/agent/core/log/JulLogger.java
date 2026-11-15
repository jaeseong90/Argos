/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.text.MessageFormat;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Replacement of {@code java.util.logging.Logger} for the shaded libraries (gRPC, Netty, Guava,
 * protobuf; ADR-0005 decision 4, ported from the WO-02 P-3 bridge). {@code :agent:agent-dist}
 * relocates every reference to {@code java.util.logging.Logger} to this class, so the shaded
 * libraries never touch the JDK {@code LogManager} (no JUL initialisation by the agent, AGT-02; no
 * console handler that could write to stderr, F-A35) and their messages go to the agent log: SEVERE
 * as ERROR, WARNING as WARN, INFO as INFO, CONFIG and FINE as DEBUG (only with {@code
 * log.level=debug}); FINER and FINEST are dropped. The text is prefixed with the library logger's
 * name. Only the methods the shaded libraries call exist; {@code checkJulBridge} fails the build
 * when a library update calls another one, instead of a {@code NoSuchMethodError} at run time.
 *
 * <p>{@link Level} and {@link LogRecord} stay JDK classes: they are plain values, and creating them
 * does not initialise the {@code LogManager} (the only JUL types an agent class may use, and only
 * here; AGT-02 exception for this bridge).
 *
 * <p>Library messages are not collected data: gRPC, Netty and protobuf log connection and protocol
 * events. One logger is different: gRPC's HTTP/2 frame logger ({@code NettyClientHandler}) dumps
 * every frame at FINE, request headers with the token and message bytes as hex, so FINE of that
 * logger is never loggable, not even with {@code log.level=debug} (11 §11, SEC-06, SEC-26). In
 * every other message, and in the text of a logged throwable (its message and its causes', masked
 * when the logger thread formats the stack trace, {@link LogPipeline}), a {@code Bearer} value and
 * any {@code argos_} run are masked ({@code argos_****}, C-94).
 *
 * <p>Volume (10 §14): WARN and above of a library are limited to one line per minute for each
 * (logger, message template) ({@link LibraryLogLimiter}; suppressed lines are counted in {@code
 * argos_agent_library_log_suppressed_total}, {@link #suppressedTotal()}). gRPC's {@code
 * ManagedChannelImpl} warning {@code Failed to resolve name} is logged at DEBUG: it is the same
 * event as the agent's own {@code collector connection failed} WARN (once per minute), and its
 * status text carries the cause's whole stack trace.
 */
public final class JulLogger {
  /** The class name gRPC's HTTP/2 frame logger logs under (relocated, so only its end is fixed). */
  private static final String FRAME_LOGGER_SUFFIX = ".NettyClientHandler";

  /** gRPC's channel logger (relocated, so only its end is fixed). */
  private static final String CHANNEL_LOGGER_SUFFIX = ".ManagedChannelImpl";

  /**
   * The template of the channel's name resolution warning ({@code [{0}] Failed to resolve ...}).
   */
  private static final String RESOLVE_FAILURE = "Failed to resolve name";

  /** The one-per-minute limit of library warnings (shared by every library logger). */
  static final LibraryLogLimiter LIMITER =
      new LibraryLogLimiter(
          new LongSupplier() {
            @Override
            public long getAsLong() {
              return System.nanoTime();
            }
          });

  private final String name;
  private final boolean frameLogger;
  private final boolean channelLogger;

  private JulLogger(String name) {
    this.name = name;
    this.frameLogger = name != null && name.endsWith(FRAME_LOGGER_SUFFIX);
    this.channelLogger = name != null && name.endsWith(CHANNEL_LOGGER_SUFFIX);
  }

  /** Library lines at WARN and above suppressed by the one-per-minute limit since the start. */
  public static long suppressedTotal() {
    return LIMITER.suppressed();
  }

  public static JulLogger getLogger(String name) {
    return new JulLogger(name);
  }

  public static JulLogger getLogger(String name, String resourceBundleName) {
    return new JulLogger(name);
  }

  public String getName() {
    return name;
  }

  public boolean isLoggable(Level level) {
    if (level == null) {
      return false;
    }
    int value = level.intValue();
    if (value >= Level.INFO.intValue()) {
      return value != Level.OFF.intValue();
    }
    return value >= Level.FINE.intValue() && !frameLogger && AgentLogger.isDebugEnabled();
  }

  public Level getLevel() {
    return null;
  }

  /** Levels are decided by the agent log level; requests from the libraries are ignored. */
  public void setLevel(Level level) {}

  public void log(LogRecord record) {
    if (record != null && isLoggable(record.getLevel())) {
      emit(
          record.getLevel(),
          record.getMessage(),
          format(record.getMessage(), record.getParameters()),
          record.getThrown());
    }
  }

  public void log(Level level, String message) {
    if (isLoggable(level)) {
      emit(level, message, message, null);
    }
  }

  public void log(Level level, Supplier<String> message) {
    if (isLoggable(level)) {
      String text = message.get();
      emit(level, text, text, null);
    }
  }

  public void log(Level level, String message, Object parameter) {
    if (isLoggable(level)) {
      emit(level, message, format(message, new Object[] {parameter}), null);
    }
  }

  public void log(Level level, String message, Object[] parameters) {
    if (isLoggable(level)) {
      emit(level, message, format(message, parameters), null);
    }
  }

  public void log(Level level, String message, Throwable thrown) {
    if (isLoggable(level)) {
      emit(level, message, message, thrown);
    }
  }

  public void log(Level level, Throwable thrown, Supplier<String> message) {
    if (isLoggable(level)) {
      String text = message.get();
      emit(level, text, text, thrown);
    }
  }

  public void logp(Level level, String sourceClass, String sourceMethod, String message) {
    log(level, message);
  }

  public void logp(
      Level level, String sourceClass, String sourceMethod, String message, Object parameter) {
    log(level, message, parameter);
  }

  public void logp(
      Level level, String sourceClass, String sourceMethod, String message, Object[] parameters) {
    log(level, message, parameters);
  }

  public void logp(
      Level level, String sourceClass, String sourceMethod, String message, Throwable thrown) {
    log(level, message, thrown);
  }

  public void severe(String message) {
    log(Level.SEVERE, message);
  }

  public void warning(String message) {
    log(Level.WARNING, message);
  }

  public void info(String message) {
    log(Level.INFO, message);
  }

  public void config(String message) {
    log(Level.CONFIG, message);
  }

  public void fine(String message) {
    log(Level.FINE, message);
  }

  public void finer(String message) {
    log(Level.FINER, message);
  }

  public void finest(String message) {
    log(Level.FINEST, message);
  }

  static String format(String message, Object[] parameters) {
    if (message == null
        || parameters == null
        || parameters.length == 0
        || message.indexOf('{') < 0) {
      return message;
    }
    try {
      return MessageFormat.format(message, parameters);
    } catch (IllegalArgumentException e) {
      return message;
    }
  }

  /** {@code text} without a token: {@code Bearer} values and every {@code argos_} run. */
  static String mask(String text) {
    return LogMask.mask(text);
  }

  /**
   * Writes one library line: {@code template} is the message before formatting (the key of the
   * one-per-minute limit), {@code message} the formatted text.
   */
  private void emit(Level level, String template, String message, Throwable thrown) {
    int value = level.intValue();
    if (value >= Level.WARNING.intValue() && isResolveFailure(template)) {
      // The same event as SessionClient's "collector connection failed" WARN (once per minute);
      // its status text carries the cause's whole stack trace. DEBUG only.
      if (!AgentLogger.isDebugEnabled()) {
        return;
      }
      value = Level.FINE.intValue();
    }
    long repeats = 0;
    if (value >= Level.WARNING.intValue()) {
      repeats = LIMITER.admit(name + '\u0000' + (template != null ? template : message));
      if (repeats < 0) {
        // Suppressed and counted; no line and no stack trace.
        return;
      }
    }
    // Defence in depth (11 §11, SEC-06): a library message never carries the token in clear; the
    // throwable's text is masked when the logger thread formats it (LogPipeline).
    String text =
        mask(
            "["
                + name
                + "] "
                + message
                + (repeats > 0
                    ? " (" + repeats + " similar line(s) suppressed in the last minute)"
                    : ""));
    if (value >= Level.SEVERE.intValue()) {
      AgentLogger.error("{}", text, thrown);
    } else if (value >= Level.WARNING.intValue()) {
      AgentLogger.warn("{}", text, thrown);
    } else if (value >= Level.INFO.intValue()) {
      AgentLogger.info("{}", text, thrown);
    } else {
      AgentLogger.debug("{}", text, thrown);
    }
  }

  private boolean isResolveFailure(String template) {
    return channelLogger && template != null && template.contains(RESOLVE_FAILURE);
  }
}
