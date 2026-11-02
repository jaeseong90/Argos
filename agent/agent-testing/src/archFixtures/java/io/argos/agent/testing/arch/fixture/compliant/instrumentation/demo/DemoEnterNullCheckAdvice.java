/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ADR-0003 C-24: the exit advice decides by the enter result ({@code null} when the enter advice
 * skipped) and does not re-read the kill switch, so a context opened in enter is always closed. The
 * {@code @Advice.Enter} parameter is not the first one (slot computation).
 */
public final class DemoEnterNullCheckAdvice {
  private DemoEnterNullCheckAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object request) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return null;
    }
    return DemoHelper.begin(request);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Thrown Throwable error, @Advice.Enter TraceContext context) {
    if (context == null) {
      return;
    }
    DemoHelper.end(context, error);
  }
}
