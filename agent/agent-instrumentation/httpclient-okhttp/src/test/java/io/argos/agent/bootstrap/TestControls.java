/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Creates {@link AgentControl} capabilities for module unit tests (the constructor is
 * package-private): installs a real tracer for the helper tests.
 */
public final class TestControls {
  private TestControls() {}

  public static AgentControl newControl() {
    return new AgentControl();
  }
}
