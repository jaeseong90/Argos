/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-002 (18 §11.1): 500 ms latency each way between the agent and the collector. */
@Order(20)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
class LatencyChaosTest {

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-CH-002 latency 500 ms: app p99 within noise, in-flight bound kept, agent heap growth"
          + " <= queue bound")
  void latency(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run("T-CH-002", "latency 500 ms", new LatencyFault()));
  }
}
