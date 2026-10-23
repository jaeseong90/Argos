/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** T-AG-001 violation: an advice method that is not static (ADV-01, 10 §5.3 A-1). */
public final class InstanceMethodAdvice {
  private static final int MODULE_ID = 9;

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(request);
  }
}
