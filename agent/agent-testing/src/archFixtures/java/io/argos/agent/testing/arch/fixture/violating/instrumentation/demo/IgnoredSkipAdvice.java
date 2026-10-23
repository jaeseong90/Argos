/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: calls Argos.skip first but ignores its result and keeps going. */
public final class IgnoredSkipAdvice {
  private static final int MODULE_ID = 9;

  private IgnoredSkipAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings("unused")
  public static void enter(@Advice.Argument(0) Object request) {
    boolean ignored = Argos.skip(MODULE_ID);
    DemoHelper.record(request);
  }
}
