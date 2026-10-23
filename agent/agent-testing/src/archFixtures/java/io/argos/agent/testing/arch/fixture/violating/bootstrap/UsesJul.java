/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

import java.util.logging.Logger;

/** AGT-02 violation: java.util.logging in agent code (only the agent's own logger is allowed). */
public final class UsesJul {
  private static final Logger LOGGER = Logger.getLogger("argos");

  public void log(String message) {
    LOGGER.fine(message);
  }
}
