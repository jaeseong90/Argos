/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant;

import java.lang.instrument.Instrumentation;

/**
 * Entry point at the root package (10 §4.1): JDK types only, loads the bootstrap by name. A start
 * that fails before the agent's logger exists prints the single C-78 line to stderr (ADR-0009).
 */
public final class AgentPremain {
  private AgentPremain() {}

  public static void premain(String args, Instrumentation instrumentation) {
    if (Boolean.getBoolean("argos.agent.disabled") || instrumentation == null) {
      return;
    }
    try {
      Class.forName(AgentPremain.class.getPackage().getName() + ".bootstrap.Argos", true, null);
    } catch (Throwable t) {
      System.err.println("argos: agent disabled: " + t);
    }
  }
}
