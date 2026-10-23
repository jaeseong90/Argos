/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.util.function.Consumer;
import net.bytebuddy.asm.Advice;

/** T-AG-001 violation: a method reference (invokedynamic) inside advice code. */
public final class MethodReferenceAdvice {
  private static final int MODULE_ID = 9;

  private MethodReferenceAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    Consumer<Object> sink = DemoHelper::record;
    sink.accept(request);
  }
}
