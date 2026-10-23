/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.other.OtherHelper;
import net.bytebuddy.asm.Advice;

/** ADV-04 violation: uses the helper of another instrumentation module. */
public final class OtherModuleAdvice {
  private static final int MODULE_ID = 9;

  private OtherModuleAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    OtherHelper.record(request);
  }
}
