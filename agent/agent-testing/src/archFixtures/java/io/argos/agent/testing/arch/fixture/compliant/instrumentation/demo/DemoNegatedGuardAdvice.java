/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * The guard written as {@code if (!Argos.skip(MODULE_ID)) { ... }}: javac branches with IFNE to the
 * method's return, the same behaviour as {@code if (Argos.skip(MODULE_ID)) return;} (A-2).
 */
public final class DemoNegatedGuardAdvice {
  private DemoNegatedGuardAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter TraceContext context, @Advice.Thrown Throwable error) {
    if (!Argos.skip(DemoInstrumentation.MODULE_ID)) {
      DemoHelper.end(context, error);
    }
  }
}
