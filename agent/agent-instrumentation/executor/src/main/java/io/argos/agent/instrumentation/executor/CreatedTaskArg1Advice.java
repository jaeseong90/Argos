/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * {@link CreatedTaskAdvice} for the JDK task objects whose user task is constructor argument 1:
 * {@code ScheduledThreadPoolExecutor$ScheduledFutureTask(pool, task, ..)} (an inner class: argument
 * 0 is the enclosing pool; {@code schedule*}, {@code execute} and {@code submit} of a scheduled
 * pool, whose task object never passes through {@code execute}; {@code cancel()}/{@code remove()}
 * see the application's own object), {@code CompletableFuture$AsyncSupply(dep, supplier)} / {@code
 * $AsyncRun(dep, runnable)} ({@code supplyAsync}, {@code runAsync}, {@code completeAsync}: the
 * stage runs on any executor, allow- listed or not) and {@code
 * ThreadPerTaskExecutor$TaskRunner(executor, task)} / {@code $ThreadBoundFuture(executor,
 * callable)} ({@code execute}/{@code submit} of a thread-per-task executor, JDK 21+, virtual
 * threads included). The constructor runs on the submitting thread before the task can start.
 */
public final class CreatedTaskArg1Advice {
  private CreatedTaskArg1Advice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void exit(@Advice.This Object created, @Advice.Argument(1) Object task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null || context.isEnded()) {
      return;
    }
    if (task != null) {
      String type = task.getClass().getName();
      if (type.startsWith("java.lang.")
          || type.startsWith("jdk.")
          || type.startsWith("sun.")
          || type.startsWith("reactor.")
          || type.startsWith("io.argos.agent.")
          || type.startsWith("org.apache.tomcat.")
          || type.startsWith("org.apache.coyote.")
          || type.startsWith("org.apache.catalina.")
          || (type.startsWith("io.") && type.startsWith("netty.", 3))) {
        return;
      }
    }
    ContextStore<Object, TraceContext> tasks =
        (ContextStore) ContextStore.of(Runnable.class, TraceContext.class);
    tasks.put(created, context);
  }
}
