/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-006 (18 §11.1): the collector's host name does not resolve. */
@Order(60)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
class DnsFailureChaosTest {

  @Test
  @Tag("req:N-30")
  @DisplayName(
      "T-CH-006 DNS failure: premain does not block, the agent connects once the name resolves")
  void dnsFailure(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run(DnsFailureFault.ID, "DNS failure", new DnsFailureFault()));
  }
}
