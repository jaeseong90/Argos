/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Advice that follows every rule of 10 §5.3: static, guarded, delegating, suppressing. */
public final class DemoAdvice {
  private DemoAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object request) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return null;
    }
    return DemoHelper.begin(request);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter TraceContext context, @Advice.Thrown Throwable error) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return;
    }
    DemoHelper.end(context, error);
  }
}
