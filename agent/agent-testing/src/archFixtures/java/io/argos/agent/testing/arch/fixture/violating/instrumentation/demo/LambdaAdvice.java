/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-001 violation: a lambda (invokedynamic) inside advice code. */
public final class LambdaAdvice {
  private static final int MODULE_ID = 9;

  private LambdaAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    Runnable task = () -> DemoHelper.record(request);
    task.run();
  }
}
