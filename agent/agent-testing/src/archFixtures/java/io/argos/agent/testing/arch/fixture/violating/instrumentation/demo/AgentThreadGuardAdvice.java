/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.ArgosThread;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-003 violation: guards with ArgosThread.isAgentThread() only; the kill switch and the module
 * state (Argos.skip, 10 §3) are not checked.
 */
public final class AgentThreadGuardAdvice {
  private AgentThreadGuardAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (ArgosThread.isAgentThread()) {
      return;
    }
    DemoHelper.record(request);
  }
}
