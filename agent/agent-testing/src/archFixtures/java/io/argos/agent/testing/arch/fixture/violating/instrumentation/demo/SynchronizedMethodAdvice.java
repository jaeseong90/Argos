/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: a synchronized advice method (ACC_SYNCHRONIZED). */
public final class SynchronizedMethodAdvice {
  private static final int MODULE_ID = 9;

  private SynchronizedMethodAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static synchronized void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(request);
  }
}
