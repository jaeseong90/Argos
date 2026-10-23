/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** B-4 violation: advice code that uses a shaded (relocated) library. */
public final class ThirdPartyAdvice {
  private static final int MODULE_ID = 9;

  private ThirdPartyAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(new ThirdPartyQueue().offer(request));
  }
}
