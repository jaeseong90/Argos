/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** T-CH-008 (18 §11.1): the server's database reset; runs last. */
@Order(1000)
@ExtendWith(ChaosRunExtension.class)
@Tag("req:N-05")
class InstanceReassignChaosTest {

  @Test
  @DisplayName(
      "T-CH-008 instance_id reassignment: new session with a new id, earlier rows shown as an"
          + " unknown instance")
  void instanceReassignment(ChaosSession session) {
    ScenarioAssertions.assertScenario(
        session.run(
            InstanceReassignFault.ID, "instance_id reassignment", new InstanceReassignFault()));
  }
}
