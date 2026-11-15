/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Exit-only advice of constructors (A-7): the guard first, then helper delegation (10 §5.3 A-2).
 * Without onThrowable: ByteBuddy cannot catch inside a constructor (T-AG-002b exempts constructor
 * advice, A-7 rejects onThrowable there). Reads the instance with dynamic typing, the only
 * ByteBuddy implementation type advice may use (Assigner.Typing).
 */
public final class DemoExitOnlyAdvice {
  private DemoExitOnlyAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return;
    }
    if (self != null) {
      DemoHelper.end(context, null);
    }
  }
}
