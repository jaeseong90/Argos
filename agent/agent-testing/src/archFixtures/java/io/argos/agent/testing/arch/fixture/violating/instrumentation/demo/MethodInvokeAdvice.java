/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/** T-AG-003 violation: Method.invoke (reflection) inside advice code. */
public final class MethodInvokeAdvice {
  private static final int MODULE_ID = 9;

  private MethodInvokeAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request, @Advice.Origin Method method)
      throws ReflectiveOperationException {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(method.invoke(request));
  }
}
