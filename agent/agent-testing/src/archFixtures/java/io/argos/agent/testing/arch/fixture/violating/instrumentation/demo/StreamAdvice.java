/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.util.Arrays;
import net.bytebuddy.asm.Advice;

/** T-AG-001 violation: java.util.stream inside advice code (ADV-01). */
public final class StreamAdvice {
  private static final int MODULE_ID = 9;

  private StreamAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.AllArguments Object[] arguments) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(Arrays.stream(arguments).count());
  }
}
