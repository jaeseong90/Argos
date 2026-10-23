/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-002 violation: replaces the return value (readOnly = false) without an allow-list entry. */
public final class ReturnReadOnlyFalseAdvice {
  private static final int MODULE_ID = 9;

  private ReturnReadOnlyFalseAdvice() {}

  // ByteBuddy writes the value assigned to `result` back as the return value; javac sees a
  // parameter that is never read.
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  @SuppressWarnings("UnusedVariable")
  public static void exit(@Advice.Return(readOnly = false) Object result) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    result = DemoHelper.value();
  }
}
