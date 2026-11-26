/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-009 (18 §11.1): ClickHouse paused (short 3 min, full 30 min) under the real agent. */
@Order(90)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:D-13")
@Tag("req:N-05")
class ClickHouseOutageChaosTest {

  @Test
  @DisplayName(
      "T-CH-009 ClickHouse down: WAL < 10 GiB, rows loaded after the pause, every transaction"
          + " stored once (D-13), app p99 within noise")
  void clickHouseDown(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run("T-CH-009", "ClickHouse down", new ClickHouseOutageFault()));
  }
}
