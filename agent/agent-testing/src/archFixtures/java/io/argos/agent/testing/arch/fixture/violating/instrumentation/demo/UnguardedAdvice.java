/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: the first call is not the Argos.skip guard (10 §5.3 A-2, ADV-03). */
public final class UnguardedAdvice {
  private static final int MODULE_ID = 9;

  private UnguardedAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    Object tracer = Argos.tracer();
    if (Argos.skip(MODULE_ID) || tracer == null) {
      return;
    }
    DemoHelper.record(request);
  }
}
