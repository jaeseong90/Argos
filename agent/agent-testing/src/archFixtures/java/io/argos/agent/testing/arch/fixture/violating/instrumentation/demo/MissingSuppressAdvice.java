/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-002 violation: no suppress = Throwable.class, so advice exceptions reach the app. */
public final class MissingSuppressAdvice {
  private static final int MODULE_ID = 9;

  private MissingSuppressAdvice() {}

  @Advice.OnMethodEnter
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(request);
  }
}
