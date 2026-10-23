/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-002 violation: repeatOn would run the application method body again (DSN-02-01). */
public final class RepeatOnAdvice {
  private static final int MODULE_ID = 9;

  private RepeatOnAdvice() {}

  @Advice.OnMethodExit(
      repeatOn = Advice.OnNonDefaultValue.class,
      suppress = Throwable.class,
      onThrowable = Throwable.class)
  public static boolean exit(@Advice.Thrown Throwable error) {
    if (Argos.skip(MODULE_ID)) {
      return false;
    }
    DemoHelper.record(error);
    return false;
  }
}
