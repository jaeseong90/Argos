/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-004 (18 §11.1): server errors and quota refusals through the server's test hook. */
@Order(40)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
@Tag("req:D-06")
class ServerErrorChaosTest {

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-CH-004 server 5xx and RESOURCE_EXHAUSTED: retried, drops counted, agent and quota drops"
          + " shown separately")
  void serverErrors(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run(
            ServerErrorFault.ID, "server 5xx and RESOURCE_EXHAUSTED", new ServerErrorFault()));
  }
}
