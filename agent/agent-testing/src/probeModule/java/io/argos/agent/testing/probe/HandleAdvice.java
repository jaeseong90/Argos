/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.shaded.net.bytebuddy.asm.Advice;

/**
 * Transaction per {@code Handler.handle(String)}: begin in enter, end in exit (C-24 exit guard).
 */
public final class HandleAdvice {
  private HandleAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(@Advice.Argument(0) String name) {
    if (Argos.skip(ProbeModule.MODULE_ID)) {
      return null;
    }
    return ProbeHelper.begin(name);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter TraceContext context, @Advice.Thrown Throwable error) {
    if (context == null) {
      return;
    }
    ProbeHelper.end(context, error);
  }
}
