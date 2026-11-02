/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code ThreadPoolExecutor.beforeExecute(Thread, Runnable)} (java.base, 10 §6.6
 * execution side): on the worker thread, right before {@code task.run()}, takes the transaction
 * attached by {@link ExecuteAdvice} and activates it; the scope (and the ASYNC step of {@code
 * async.step=true}) is kept per worker thread for {@link AfterExecuteAdvice}. A scope that an
 * earlier task left open on this worker (module disabled between the two hooks) is closed first.
 *
 * <p>ASYNC step name: the task class name, a lambda class cut after {@code $$Lambda} (its suffix
 * differs per run and would grow the dictionary).
 */
public final class BeforeExecuteAdvice {
  private BeforeExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(1) Runnable task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    Thread worker = Thread.currentThread();
    ContextStore<Thread, Scope> scopes = ContextStore.of(Thread.class, Scope.class);
    Scope stale = scopes.remove(worker);
    if (stale != null) {
      ContextStore.of(Thread.class, Integer.class).remove(worker);
      ContextStore.of(Thread.class, TraceContext.class).remove(worker);
      stale.close();
    }
    if (task == null) {
      return;
    }
    TraceContext context = ContextStore.of(Runnable.class, TraceContext.class).remove(task);
    if (context == null || context.isEnded()) {
      return;
    }
    Tracer tracer = Argos.tracer();
    scopes.put(worker, tracer.activate(context));
    if (tracer.capture().asyncStep()) {
      String name = task.getClass().getName();
      int lambda = name.indexOf("$$Lambda");
      if (lambda > 0) {
        name = name.substring(0, lambda + 8);
      }
      int seq =
          tracer.stepStart(
              context, StepType.ASYNC, tracer.dict(DictKind.CLASS, name), 0L, System.nanoTime());
      if (seq >= 0) {
        ContextStore.of(Thread.class, TraceContext.class).put(worker, context);
        ContextStore.of(Thread.class, Integer.class).put(worker, Integer.valueOf(seq));
      }
    }
  }
}
