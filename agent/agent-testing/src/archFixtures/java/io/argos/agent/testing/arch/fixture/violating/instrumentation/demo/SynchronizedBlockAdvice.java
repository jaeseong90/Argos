/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: a synchronized block (monitorenter) on the application thread. */
public final class SynchronizedBlockAdvice {
  private static final int MODULE_ID = 9;
  private static final Object LOCK = new Object();

  private SynchronizedBlockAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    synchronized (LOCK) {
      DemoHelper.record(request);
    }
  }
}
