/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import io.argos.agent.core.log.AgentLogger;
import java.text.MessageFormat;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Replacement of {@code java.util.logging.Logger} for the shaded libraries (gRPC, Netty, protobuf):
 * the comparison jars relocate every reference to {@code java.util.logging.Logger} to this class,
 * so the shaded libraries never touch the JDK {@code LogManager} (no JUL initialisation in the
 * agent, CLAUDE.md, no console handler that could write to stderr: F-A35) and their messages go to
 * the agent log: SEVERE as ERROR, WARNING as WARN, INFO as INFO, CONFIG and FINE as DEBUG (only
 * with {@code log.level=debug}), FINER and FINEST are dropped. Only the methods the shaded
 * libraries call exist; a new call site fails the P-3 build check instead of failing at run time.
 *
 * <p>{@link Level} and {@link LogRecord} stay JDK classes: creating them does not initialise the
 * {@code LogManager}.
 */
public final class JulLogger {
  private final String name;

  private JulLogger(String name) {
    this.name = name;
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
    int value = level.intValue();
    if (value >= Level.INFO.intValue()) {
      return value != Level.OFF.intValue();
    }
    return value >= Level.FINE.intValue() && AgentLogger.isDebugEnabled();
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
          format(record.getMessage(), record.getParameters()),
          record.getThrown());
    }
  }

  public void log(Level level, String message) {
    if (isLoggable(level)) {
      emit(level, message, null);
    }
  }

  public void log(Level level, Supplier<String> message) {
    if (isLoggable(level)) {
      emit(level, message.get(), null);
    }
  }

  public void log(Level level, String message, Object parameter) {
    if (isLoggable(level)) {
      emit(level, format(message, new Object[] {parameter}), null);
    }
  }

  public void log(Level level, String message, Object[] parameters) {
    if (isLoggable(level)) {
      emit(level, format(message, parameters), null);
    }
  }

  public void log(Level level, String message, Throwable thrown) {
    if (isLoggable(level)) {
      emit(level, message, thrown);
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

  private static String format(String message, Object[] parameters) {
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

  private void emit(Level level, String message, Throwable thrown) {
    int value = level.intValue();
    String text = "[" + name + "] " + message;
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
}
