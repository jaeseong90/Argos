/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: new Throwable() captures the stack on every call. */
public final class NewThrowableAdvice {
  private static final int MODULE_ID = 9;

  private NewThrowableAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(new IllegalStateException("caller of " + request).getStackTrace());
  }
}
