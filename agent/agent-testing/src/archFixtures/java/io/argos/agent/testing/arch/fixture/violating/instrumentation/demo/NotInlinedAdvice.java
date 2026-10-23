/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-002 violation: inline = false makes the target call the advice class, which the application
 * class loader cannot see (DSN-02-01: inlined advice only).
 */
public final class NotInlinedAdvice {
  private static final int MODULE_ID = 9;

  private NotInlinedAdvice() {}

  @Advice.OnMethodEnter(inline = false, suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(request);
  }
}
