/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Creates {@link AgentControl} capabilities for core unit tests (the constructor is
 * package-private).
 */
public final class TestControls {
  private TestControls() {}

  public static AgentControl newControl() {
    return new AgentControl();
  }
}
