/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-CH-003 (18 §11.1): 10 % loss. The toxiproxy variant runs everywhere; the {@code tc netem}
 * variant only on a Linux Docker Engine host with {@code -Pchaos.netem.image} (SKIPPED with the
 * reason otherwise).
 */
@Order(30)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
@Tag("req:D-13")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PacketLossChaosTest {

  @Test
  @Order(1)
  @DisplayName(
      "T-CH-003 loss 10 % (toxiproxy limit_data, reset_peer, timeout): no duplicate rows after"
          + " resends, every transaction stored or counted, app p99 within noise")
  void toxiproxyLoss(ChaosSession session) {
    ScenarioAssertions.assertScenario(session.run("T-CH-003", "loss 10 %", new LossFault()));
  }

  @Test
  @Order(2)
  @DisplayName("T-CH-003 loss 10 % (tc netem, Linux): no duplicate rows after resends")
  void netemLoss(ChaosSession session) throws Exception {
    String title = "loss 10 % (tc netem)";
    if (!session.options().selected("T-CH-003")) {
      ScenarioAssertions.assertScenario(
          session.record(
              ScenarioResult.skipped("T-CH-003", title, "not selected (-Pchaos.scenarios)")));
      return;
    }
    boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    String reason;
    if (!linux) {
      reason = NetemFault.unavailableReason(false, false, null, session.options().netemImage());
    } else {
      ChaosStack stack = session.stack();
      reason =
          NetemFault.unavailableReason(
              true,
              Toxiproxy.dockerDesktop(),
              stack.toxiproxy().network(),
              session.options().netemImage());
    }
    if (reason != null) {
      ScenarioAssertions.assertScenario(
          session.record(ScenarioResult.skipped("T-CH-003", title, reason)));
      return;
    }
    ScenarioAssertions.assertScenario(
        session.run("T-CH-003", title, new NetemFault(session.options().netemImage())));
  }
}
