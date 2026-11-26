/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-007 (18 §11.1): a collector certificate that does not name the dialed host. */
@Order(70)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:SEC-08")
@Tag("req:N-05")
class TlsMismatchChaosTest {

  @Test
  @DisplayName(
      "T-CH-007 TLS host name mismatch: no connection and a WARN; insecure-skip-verify connects"
          + " with a WARN on every connection")
  void tlsMismatch(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run(TlsMismatchFault.ID, "TLS host name mismatch", new TlsMismatchFault()));
  }
}
