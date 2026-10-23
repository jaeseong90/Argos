/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-003 violation: calls a method handle (MethodHandle.invoke) in advice code; field values are
 * read with @Advice.FieldValue instead.
 */
public final class MethodHandleInvokeAdvice {
  private static final int MODULE_ID = 9;

  private MethodHandleInvokeAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(
      @Advice.FieldGetterHandle("status") MethodHandle getter, @Advice.This Object self)
      throws Throwable {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(getter.invoke(self));
  }
}
