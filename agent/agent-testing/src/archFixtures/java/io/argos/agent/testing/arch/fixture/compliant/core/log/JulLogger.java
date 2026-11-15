/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.log;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/** The JUL bridge (ADR-0005 decision 4): the one class that may take JUL's plain values. */
public final class JulLogger {
  public boolean isLoggable(Level level) {
    return level != null && level.intValue() >= Level.INFO.intValue();
  }

  public void log(LogRecord record) {
    if (isLoggable(record.getLevel())) {
      AgentLogger.info("library message");
    }
  }
}
