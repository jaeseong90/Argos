/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-002 violation: skipOn would skip the application method body (10 §5.3 A-4). */
public final class SkipOnAdvice {
  private static final int MODULE_ID = 9;

  private SkipOnAdvice() {}

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
  public static boolean enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return false;
    }
    DemoHelper.record(request);
    return false;
  }
}
