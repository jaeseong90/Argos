/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/** Enter advice: A-7 forbids applying it to constructors. */
public final class ConstructorEnterAdvice {
  private ConstructorEnterAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter() {
    if (Argos.skip(1)) {
      return;
    }
  }
}
