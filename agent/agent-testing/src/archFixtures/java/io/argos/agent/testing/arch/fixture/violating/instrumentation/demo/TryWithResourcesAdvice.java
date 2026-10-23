/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import java.io.IOException;
import java.io.StringReader;
import net.bytebuddy.asm.Advice;

/** T-AG-001 violation: try-with-resources inside advice code (ADV-01). */
public final class TryWithResourcesAdvice {
  private static final int MODULE_ID = 9;

  private TryWithResourcesAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) String body) throws IOException {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    try (StringReader reader = new StringReader(body)) {
      DemoHelper.record(reader.read());
    }
  }
}
