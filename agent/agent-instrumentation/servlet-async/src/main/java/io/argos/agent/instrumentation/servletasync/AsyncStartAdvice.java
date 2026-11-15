/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the enter of Tomcat's {@code AsyncContextImpl.start(Runnable)} (10 §6.7): the task
 * carries the current transaction ({@link AsyncHelperCommon#propagate}); it is never wrapped or
 * replaced.
 */
public final class AsyncStartAdvice {
  private AsyncStartAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Runnable task) {
    if (Argos.skip(ServletAsyncModule.MODULE_ID)) {
      return;
    }
    AsyncHelperCommon.propagate(task);
  }
}
