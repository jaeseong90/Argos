/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-002 violation: @Advice.SelfCallHandle is a handle that calls the instrumented application
 * method again (like repeatOn), never allowed.
 */
public final class SelfCallHandleAdvice {
  private static final int MODULE_ID = 9;

  private SelfCallHandleAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.SelfCallHandle MethodHandle self) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(self);
  }
}
