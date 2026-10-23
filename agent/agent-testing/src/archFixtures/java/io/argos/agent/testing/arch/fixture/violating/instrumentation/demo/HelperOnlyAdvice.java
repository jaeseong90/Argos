/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-003 violation: the exit advice only delegates to a helper, without the guard, so the kill
 * switch and a disabled module do not stop it (10 §5.3 A-2). The enter advice is guarded.
 */
public final class HelperOnlyAdvice {
  private static final int MODULE_ID = 9;

  private HelperOnlyAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(request);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Argument(0) Object request) {
    DemoHelper.record(request);
  }
}
