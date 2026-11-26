/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-005 (18 §11.1): the agent's log directory on a full 1 MB tmpfs. */
@Order(50)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
class LogDiskFullChaosTest {

  @Test
  @DisplayName(
      "T-CH-005 agent log disk full: stderr fallback <= 10 lines per minute, application"
          + " unaffected")
  void logDiskFull(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run(LogDiskFullFault.ID, "agent log disk full", new LogDiskFullFault()));
  }
}
