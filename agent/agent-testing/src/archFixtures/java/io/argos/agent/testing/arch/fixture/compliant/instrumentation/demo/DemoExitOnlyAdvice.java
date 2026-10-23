/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Exit-only advice: the guard first, then helper delegation (10 §5.3 A-2). Reads the return value
 * with dynamic typing, the only ByteBuddy implementation type advice may use (Assigner.Typing).
 */
public final class DemoExitOnlyAdvice {
  private DemoExitOnlyAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
      @Advice.Thrown Throwable error) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return;
    }
    DemoHelper.end(context, result == null ? error : null);
  }
}
