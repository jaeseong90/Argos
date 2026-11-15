/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating;

/**
 * AGT-02 violation of the entry point: the C-78 exception covers one stderr line only, never stdout
 * or a stack trace (ADR-0009 C-78, F-A35).
 */
public final class AgentPremain {
  private AgentPremain() {}

  public static void premain(String args) {
    try {
      Class.forName("io.argos.agent.bootstrap.Argos", true, null);
    } catch (Exception e) {
      System.out.println("argos: agent disabled: " + e);
      e.printStackTrace();
    }
  }
}
