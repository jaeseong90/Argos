/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

/**
 * Submit side of 10 §6.6 for {@code ForkJoinPool.execute/submit/invoke} (enter): a {@code
 * ForkJoinTask} argument is the object that a pool worker runs ({@code doExec}), so the submitting
 * thread's transaction is attached to it. A {@code Runnable} or {@code Callable} argument is not a
 * task the pool runs: the pool creates an adapter, which {@link CreatedTaskAdvice} handles. A
 * context already attached to the task (a {@code CompletableFuture$AsyncSupply} created in the
 * transaction) is kept.
 */
public final class ForkJoinSubmitAdvice {
  private ForkJoinSubmitAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void enter(@Advice.Argument(0) Object task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    if (!(task instanceof ForkJoinTask)) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null || context.isEnded()) {
      return;
    }
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
    ContextStore<Object, TraceContext> tasks =
        (ContextStore) ContextStore.of(Runnable.class, TraceContext.class);
    TraceContext attached = tasks.get(task);
    if (attached == null || attached.isEnded()) {
      tasks.put(task, context);
    }
  }
}
