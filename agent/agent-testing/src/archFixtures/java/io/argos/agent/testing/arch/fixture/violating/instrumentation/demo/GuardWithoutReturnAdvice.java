/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: tests Argos.skip but still runs code when it returns true. */
public final class GuardWithoutReturnAdvice {
  private static final int MODULE_ID = 9;

  private GuardWithoutReturnAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      DemoHelper.record(MODULE_ID);
    }
    DemoHelper.record(request);
  }
}
