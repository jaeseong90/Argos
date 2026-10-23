/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant;

import java.lang.instrument.Instrumentation;

/** Entry point at the root package (10 §4.1): JDK types only, loads the bootstrap by name. */
public final class AgentPremain {
  private AgentPremain() {}

  public static void premain(String args, Instrumentation instrumentation) throws Exception {
    if (Boolean.getBoolean("argos.agent.disabled") || instrumentation == null) {
      return;
    }
    Class.forName(AgentPremain.class.getPackage().getName() + ".bootstrap.Argos", true, null);
  }
}
