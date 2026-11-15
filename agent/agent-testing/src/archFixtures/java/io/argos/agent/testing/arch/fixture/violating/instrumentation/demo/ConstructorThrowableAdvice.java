/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice with onThrowable: ByteBuddy rejects it on a constructor ("Cannot catch exception
 * during constructor call"), so A-7 forbids applying it to constructors.
 */
public final class ConstructorThrowableAdvice {
  private ConstructorThrowableAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Thrown Throwable error) {
    if (Argos.skip(1)) {
      return;
    }
  }
}
