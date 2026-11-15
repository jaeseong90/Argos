/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core.trace;

import io.argos.agent.testing.arch.fixture.app.Order;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * T-AG-004 violations: a step builder that keeps an application object, directly, in an atomic
 * reference and in a raw atomic array.
 */
public final class StepBuffer {
  int[] type = new int[64];
  Order lastOrder;
  final AtomicReference<Order> pendingOrder = new AtomicReference<Order>();

  @SuppressWarnings("rawtypes")
  final AtomicReferenceArray anything = new AtomicReferenceArray(4);

  public void bind(Order order) {
    this.lastOrder = order;
    this.pendingOrder.set(order);
  }

  public int size() {
    return anything.length();
  }
}
