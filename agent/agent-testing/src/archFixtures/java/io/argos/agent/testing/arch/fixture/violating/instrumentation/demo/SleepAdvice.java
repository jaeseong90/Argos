/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: Thread.sleep blocks the application thread. */
public final class SleepAdvice {
  private static final int MODULE_ID = 9;

  private SleepAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) throws InterruptedException {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    Thread.sleep(1L);
    DemoHelper.record(request);
  }
}
