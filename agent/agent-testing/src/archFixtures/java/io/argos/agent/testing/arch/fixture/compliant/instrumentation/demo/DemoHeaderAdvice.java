/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Writes an argument (readOnly = false) to add a propagation header; allowed because the fixture
 * allow-list (archTest resources) has an entry for it (10 §5.3 A-5, C-9).
 */
public final class DemoHeaderAdvice {
  private DemoHeaderAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(value = 0, readOnly = false) String[] headers) {
    if (Argos.skip(DemoInstrumentation.MODULE_ID)) {
      return;
    }
    headers = DemoHelper.withHeader(headers);
  }
}
