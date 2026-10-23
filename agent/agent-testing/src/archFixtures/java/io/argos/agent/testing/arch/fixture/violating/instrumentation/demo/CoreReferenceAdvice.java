/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.violating.core.CoreService;
import net.bytebuddy.asm.Advice;

/**
 * ADV-04/B-2 violation: inlined advice code runs in the application class loader, which cannot see
 * core classes.
 */
public final class CoreReferenceAdvice {
  private static final int MODULE_ID = 9;

  private CoreReferenceAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    CoreService.submit(request);
  }
}
