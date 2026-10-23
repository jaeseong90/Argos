/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-003 violation: writes application state before the guard, which is not the first statement.
 */
public final class CodeBeforeGuardAdvice {
  private static final int MODULE_ID = 9;

  private CodeBeforeGuardAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object[] values) {
    values[0] = null;
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(values);
  }
}
