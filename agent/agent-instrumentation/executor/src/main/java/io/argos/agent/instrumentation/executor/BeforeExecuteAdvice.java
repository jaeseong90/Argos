/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code beforeExecute(Thread, Runnable)} of {@code
 * java.util.concurrent.ThreadPoolExecutor}, of Tomcat's {@code ThreadPoolExecutor} and of the pools
 * that Spring's {@code ThreadPoolTaskExecutor}/{@code ThreadPoolTaskScheduler} create, which
 * override the hook without calling {@code super} (Spring 6.1+). Execution side of 10 §6.6 for pool
 * workers, the only point where a lambda task can be seen (its hidden class cannot be
 * instrumented): right before {@code task.run()}, takes the transaction attached to the task and
 * activates it; the scope (and the ASYNC step of {@code async.step=true}) is kept per worker thread
 * for {@link AfterExecuteAdvice}. A scope that an earlier task left open on this worker (module
 * disabled between the two hooks, a subclass whose {@code afterExecute} does not reach an
 * instrumented hook) is closed first, so a task never runs with another task's transaction.
 *
 * <p>An override that calls {@code super.beforeExecute} runs this advice twice on one task: the
 * nested run is recognised by the {@link CallDepth#EXECUTOR} depth that the outer run holds until
 * its exit, and does nothing.
 *
 * <p>ASYNC step name: the task class name, a lambda class cut after {@code $$Lambda} (its suffix
 * differs per run and would grow the dictionary).
 */
public final class BeforeExecuteAdvice {
  private BeforeExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Boolean enter(@Advice.Argument(1) Runnable task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return null;
    }
    if (CallDepth.depth(CallDepth.EXECUTOR) != 0) {
      return null;
    }
    Thread worker = Thread.currentThread();
    ContextStore<Thread, Scope> scopes = ContextStore.of(Thread.class, Scope.class);
    Scope stale = scopes.remove(worker);
    if (stale != null) {
      ContextStore.of(Thread.class, Integer.class).remove(worker);
      ContextStore.of(Thread.class, TraceContext.class).remove(worker);
      stale.close();
    }
    TraceContext context =
        task == null ? null : ContextStore.of(Runnable.class, TraceContext.class).remove(task);
    if (context != null && !context.isEnded()) {
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
    // Last: an exception above leaves the depth unchanged (the exit then does nothing).
    CallDepth.enter(CallDepth.EXECUTOR);
    return Boolean.TRUE;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Boolean entered) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.EXECUTOR);
  }
}
