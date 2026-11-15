/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core.log;

import java.util.logging.Logger;

/** AGT-02 violation: the JUL bridge must not use the JDK logger itself (ADR-0005 decision 4). */
public final class JulLogger {
  private static final Logger LOGGER = Logger.getLogger("bridge");

  public void info(String message) {
    LOGGER.info(message);
  }
}
