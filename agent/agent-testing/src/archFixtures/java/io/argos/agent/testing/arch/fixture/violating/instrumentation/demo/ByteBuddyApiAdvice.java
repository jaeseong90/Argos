/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.Advice;

/**
 * B-4 violation: advice code calling the (shaded) ByteBuddy API. Only the Advice annotations may be
 * referenced; they are not part of the inlined code.
 */
public final class ByteBuddyApiAdvice {
  private static final int MODULE_ID = 9;

  private ByteBuddyApiAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(ClassFileVersion.ofThisVm().getMajorVersion());
    DemoHelper.record(request);
  }
}
