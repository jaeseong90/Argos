/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Submit side of 10 §6.6 at the creation of the JDK task object that the worker will run, for
 * submissions whose {@code execute} never sees that object: constructor exit (A-7) of the {@code
 * ForkJoinTask} adapters ({@code ForkJoinPool.execute/submit} of a {@code Runnable} or {@code
 * Callable}, {@code ForkJoinTask.adapt}), whose user task is constructor argument 0 ({@link
 * CreatedTaskArg1Advice} covers the task objects whose user task is argument 1). The constructor
 * runs inside the submitting call, on the submitting thread, before the task is queued: no race
 * with the worker, and {@code cancel()}/{@code remove()} still see the application's own task
 * (nothing is wrapped).
 *
 * <p>The user task (argument 0) decides the exclusions of {@link ExecutorModule}: the continuations
 * of virtual threads ({@code java.lang.VirtualThread}) and container, Netty, Reactor, JDK-internal
 * and Argos tasks get no context.
 */
public final class CreatedTaskAdvice {
  private CreatedTaskAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void exit(@Advice.This Object created, @Advice.Argument(0) Object task) {
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
