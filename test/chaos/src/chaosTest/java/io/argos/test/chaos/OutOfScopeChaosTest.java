/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-CH-010 and T-CH-011 of 18 §11.1 are not WO-52's acceptance (T-CH-001~009): the report lists
 * them SKIPPED with the reason, nothing runs.
 */
@Order(1010)
@ExtendWith(ChaosRunExtension.class)
class OutOfScopeChaosTest {

  @Test
  @DisplayName("T-CH-010 PostgreSQL down 5 min: not in WO-52 (skipped)")
  void postgresDown(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.record(
            ScenarioResult.skipped(
                "T-CH-010",
                "PostgreSQL down 5 min",
                "not in WO-52 (acceptance T-CH-001~009); a later work order")));
  }

  @Test
  @DisplayName("T-CH-011 server disk 90 %: not in WO-52 (skipped)")
  void diskNinetyPercent(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.record(
            ScenarioResult.skipped(
                "T-CH-011",
                "server disk 90 %",
                "not in WO-52 (acceptance T-CH-001~009); the emergency retention part is proven"
                    + " by WO-28 EmergencyRetentionIT (disk usage injected)")));
  }
}
