/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003a / C-24 violation: tests the enter value for null but still runs code when it is. */
public final class NullCheckWithoutReturnAdvice {
  private static final int MODULE_ID = 9;

  private NullCheckWithoutReturnAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return null;
    }
    return request;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object context) {
    if (context == null) {
      DemoHelper.record(MODULE_ID);
    }
    DemoHelper.record(context);
  }
}
