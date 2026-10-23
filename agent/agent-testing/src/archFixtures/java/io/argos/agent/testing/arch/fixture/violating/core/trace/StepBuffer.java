/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core.trace;

import io.argos.agent.testing.arch.fixture.app.Order;

/** T-AG-004 violation: a step builder that keeps an application object. */
public final class StepBuffer {
  int[] type = new int[64];
  Order lastOrder;

  public void bind(Order order) {
    this.lastOrder = order;
  }
}
