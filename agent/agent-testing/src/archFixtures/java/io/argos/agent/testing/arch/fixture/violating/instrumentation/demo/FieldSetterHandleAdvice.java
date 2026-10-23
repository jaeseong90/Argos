/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;

/**
 * T-AG-002 violation: @Advice.FieldSetterHandle hands out a setter of an application field (here to
 * a helper that could call it), a write like @Advice.FieldValue(readOnly = false) that needs an
 * allow-list entry.
 */
public final class FieldSetterHandleAdvice {
  private static final int MODULE_ID = 9;

  private FieldSetterHandleAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.FieldSetterHandle("status") MethodHandle setter) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(setter);
  }
}
