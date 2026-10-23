/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-002 violation: @Advice.AssignReturned replaces the return value like readOnly = false and
 * needs the same allow-list entry.
 */
public final class AssignReturnedAdvice {
  private static final int MODULE_ID = 9;

  private AssignReturnedAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  @Advice.AssignReturned.ToReturned
  public static Object exit(@Advice.Return Object result) {
    if (Argos.skip(MODULE_ID)) {
      return result;
    }
    return DemoHelper.value();
  }
}
