/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code afterExecute(Runnable, Throwable)} of the pools of {@link
 * BeforeExecuteAdvice} (10 §6.6): on the worker thread, right after the task, closes the ASYNC step
 * (error flag when the task threw) and the scope that {@link BeforeExecuteAdvice} opened, restoring
 * the worker's previous binding. An override that calls {@code super.afterExecute} finds the scope
 * already closed in the nested call and does nothing there.
 */
public final class AfterExecuteAdvice {
  private AfterExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(1) Throwable error) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    Thread worker = Thread.currentThread();
    Scope scope = ContextStore.of(Thread.class, Scope.class).remove(worker);
    if (scope == null) {
      return;
    }
    Integer seq = ContextStore.of(Thread.class, Integer.class).remove(worker);
    TraceContext stepContext = ContextStore.of(Thread.class, TraceContext.class).remove(worker);
    if (seq != null && stepContext != null) {
      Argos.tracer().stepEnd(stepContext, seq.intValue(), 0, error != null, 0L, System.nanoTime());
    }
    scope.close();
  }
}
