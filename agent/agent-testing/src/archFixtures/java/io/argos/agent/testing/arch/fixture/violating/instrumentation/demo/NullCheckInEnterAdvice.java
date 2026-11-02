/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.asm.Advice;

/**
 * T-AG-003a violation: a null check is no guard in enter advice (C-24 accepts it only in exit
 * advice, on the {@code @Advice.Enter} value); the kill switch would not stop this code.
 */
public final class NullCheckInEnterAdvice {
  private NullCheckInEnterAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (request == null) {
      return;
    }
    DemoHelper.record(request);
  }
}
