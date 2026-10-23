/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.app;

/** An application class, loaded by a web application class loader in production. */
public final class Order {
  private final long id;

  public Order(long id) {
    this.id = id;
  }

  public long id() {
    return id;
  }
}
