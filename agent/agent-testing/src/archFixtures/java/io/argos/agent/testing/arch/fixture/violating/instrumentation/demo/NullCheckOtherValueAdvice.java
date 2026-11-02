/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-003a / C-24 violation: the exit advice tests a value other than its {@code @Advice.Enter}
 * parameter for null, so it runs although the enter advice skipped.
 */
public final class NullCheckOtherValueAdvice {
  private static final int MODULE_ID = 9;

  private NullCheckOtherValueAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return null;
    }
    return request;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object context, @Advice.Thrown Throwable error) {
    if (error == null) {
      return;
    }
    DemoHelper.record(context);
  }
}
