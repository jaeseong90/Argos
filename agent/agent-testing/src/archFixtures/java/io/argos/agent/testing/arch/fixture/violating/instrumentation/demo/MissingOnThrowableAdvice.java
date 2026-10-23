/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-002 violation: exit advice without onThrowable = Throwable.class (10 §5.3 A-1). */
public final class MissingOnThrowableAdvice {
  private static final int MODULE_ID = 9;

  private MissingOnThrowableAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.Return Object result) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(result);
  }
}
