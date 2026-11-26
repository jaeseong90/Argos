/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-001 (18 §11.1): the server down (short 3 min, full 1 h), queue drops exact, back-off. */
@Order(10)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
@Tag("req:D-06")
class ServerOutageChaosTest {

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-CH-001 server down: app p99 within noise, drops beyond the 120 s queue counted exactly,"
          + " reconnect back-off <= 30 s, outage shown, recovery <= 60 s")
  void serverDown(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run("T-CH-001", "server down", new ServerOutageFault()));
  }
}
