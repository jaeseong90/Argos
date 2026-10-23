/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: Class.forName (reflective lookup, class loading) inside advice code. */
public final class ForNameAdvice {
  private static final int MODULE_ID = 9;

  private ForNameAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) String className) throws ClassNotFoundException {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(Class.forName(className));
  }
}
