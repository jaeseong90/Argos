/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core;

import io.argos.agent.testing.arch.fixture.compliant.core.log.AgentLogger;
import java.io.File;

/**
 * LOG-01 (10 §14, SEC-26): INFO/WARN/ERROR calls with constants, numbers, enums, files, class names
 * and throwables only; DEBUG may carry collected data (not checked).
 */
public final class LogsSafely {
  /** A fixed set of values. */
  public enum Mode {
    ON,
    OFF
  }

  private LogsSafely() {}

  public static void report(int count, long bytes, Mode mode, File dir, Throwable error) {
    AgentLogger.info("started: {} classes, {} bytes, mode {}, dir {}", count, bytes, mode, dir);
    AgentLogger.warn(
        "failed: {} after {}", error.getClass().getName(), String.valueOf(count), error);
    AgentLogger.error("constant only");
    AgentLogger.warnOnce("key-" + count, "once {} {}", true, Mode.OFF);
    try {
      dir.getCanonicalPath();
    } catch (Exception e) {
      AgentLogger.warn("cannot resolve {}: {}", dir, e.toString());
    }
  }

  public static void detail(String collected) {
    AgentLogger.debug("detail {}", collected);
  }
}
