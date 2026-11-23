/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.app.Order;
import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * ADV-04b violation (C-223 (8)): an application/library type as an advice parameter and a (shaded)
 * library type as an advice return type.
 */
public final class LibrarySignatureAdvice {
  private static final int MODULE_ID = 9;

  private LibrarySignatureAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ThirdPartyQueue enter(@Advice.Argument(0) Order order) {
    if (Argos.skip(MODULE_ID)) {
      return null;
    }
    DemoHelper.record(order);
    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Argument(0) Order[] orders) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    DemoHelper.record(orders);
  }
}
