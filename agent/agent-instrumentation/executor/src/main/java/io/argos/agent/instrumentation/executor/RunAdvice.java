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
 * Execution side of 10 §6.6, inlined into the method that runs a task object on its worker: {@code
 * FutureTask.run()}, {@code ForkJoinTask.doExec()}, {@code CompletableFuture$AsyncSupply.run()} /
 * {@code $AsyncRun.run()}, {@code ThreadPerTaskExecutor$TaskRunner.run()} and the {@code run()} /
 * {@code call()} of application {@code Runnable}/{@code Callable} classes.
 *
 * <p>Enter always <em>removes</em> the task's context from the store (a pooled or reused task
 * object never carries an old transaction into its next run) and activates it unless the
 * transaction has ended; with {@code async.step=true} an ASYNC step named after the task class
 * opens. Exit closes exactly what enter opened, decided by the enter value only (C-24): the step
 * (error flag when the method threw) and the scope, which restores the worker's previous binding.
 * When an outer execution point already took the context (the pool's {@code beforeExecute}, {@code
 * doExec} around {@code run}), the store is empty here and nothing happens, so a task is activated
 * once.
 */
public final class RunAdvice {
  private RunAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object enter(@Advice.This Object task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return null;
    }
    ContextStore<Object, TraceContext> tasks =
        (ContextStore) ContextStore.of(Runnable.class, TraceContext.class);
    TraceContext context = tasks.remove(task);
    if (context == null || context.isEnded()) {
      return null;
    }
    Tracer tracer = Argos.tracer();
    Scope scope = tracer.activate(context);
    if (!tracer.capture().asyncStep()) {
      return scope;
    }
    int seq =
        tracer.stepStart(
            context,
            StepType.ASYNC,
            tracer.dict(DictKind.CLASS, task.getClass().getName()),
            0L,
            System.nanoTime());
    if (seq < 0) {
      return scope;
    }
    return new Object[] {scope, context, Integer.valueOf(seq)};
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object opened, @Advice.Thrown Throwable error) {
    if (opened == null) {
      return;
    }
    if (opened instanceof Object[]) {
      Object[] step = (Object[]) opened;
      Argos.tracer()
          .stepEnd(
              (TraceContext) step[1],
              ((Integer) step[2]).intValue(),
              0,
              error != null,
              0L,
              System.nanoTime());
      ((Scope) step[0]).close();
      return;
    }
    ((Scope) opened).close();
  }
}
