/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * C-24 written as {@code if (context != null) { ... }}: javac branches with IFNULL to the method's
 * return, the same behaviour as {@code if (context == null) return;}.
 */
public final class DemoEnterNonNullBlockAdvice {
  private DemoEnterNonNullBlockAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return null;
    }
    return DemoHelper.begin(request);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter TraceContext context, @Advice.Thrown Throwable error) {
    if (context != null) {
      DemoHelper.end(context, error);
    }
  }
}
