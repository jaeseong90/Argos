/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code ThreadPoolExecutor.execute(Runnable)} (java.base, 10 §6.6 submit side):
 * attaches the submitting thread's transaction to the task object. Agent threads are skipped by
 * {@code Argos.skip}; nothing happens outside a transaction.
 */
public final class ExecuteAdvice {
  private ExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Runnable task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    if (task == null) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null || context.isEnded()) {
      return;
    }
    ContextStore.of(Runnable.class, TraceContext.class).put(task, context);
  }
}
