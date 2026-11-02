/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.util.Locale;

/** Agent log levels ({@code log.level}, 10 §15), most severe first. */
public enum LogLevel {
  ERROR(0),
  WARN(1),
  INFO(2),
  DEBUG(3),
  TRACE(4);

  private final int severity;

  LogLevel(int severity) {
    this.severity = severity;
  }

  /** True when a message of level {@code message} passes this threshold. */
  public boolean allows(LogLevel message) {
    return message.severity <= severity;
  }

  /** Parses a level name (case-insensitive); {@code fallback} for unknown names. */
  public static LogLevel parse(String name, LogLevel fallback) {
    if (name == null) {
      return fallback;
    }
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }
}
