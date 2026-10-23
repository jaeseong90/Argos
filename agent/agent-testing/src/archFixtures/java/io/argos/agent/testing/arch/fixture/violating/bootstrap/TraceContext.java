/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

import io.argos.agent.testing.arch.fixture.app.Order;
import java.util.Map;

/**
 * T-AG-004 violation: keeps an application object and a JDK map (which can hold application
 * objects) and so pins the web application class loader (F-A36).
 */
public final class TraceContext {
  public final long txId;
  public volatile String serviceName;
  public volatile Order order;
  public volatile Map<String, Object> attributes;

  public TraceContext(long txId) {
    this.txId = txId;
  }
}
