/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Submit side of 10 §6.6 for the executors whose {@code execute} receives the object that the
 * worker runs: {@code java.util.concurrent.ThreadPoolExecutor.execute(Runnable)}, Tomcat's {@code
 * org.apache.tomcat.util.threads.ThreadPoolExecutor.execute(Runnable[, ..])} and Spring's {@code
 * SimpleAsyncTaskExecutor.execute(Runnable[, long])}. Attaches the submitting thread's transaction
 * to the task object (the module's single task store, {@code ContextStore.of(Runnable.class,
 * TraceContext.class)}); the task is never wrapped or replaced.
 *
 * <p>Nothing happens outside a transaction, on agent threads ({@code Argos.skip}), for an ended
 * transaction, or for container, Netty, Reactor, JDK-internal and Argos tasks (the exclusions of
 * {@link ExecutorModule}). A context that is already attached (a task created inside the
 * transaction, such as {@code CompletableFuture$AsyncSupply}) is kept: the creating thread knows
 * the transaction better than a later submitter.
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
    ContextStore<Runnable, TraceContext> tasks =
        ContextStore.of(Runnable.class, TraceContext.class);
    TraceContext attached = tasks.get(task);
    if (attached == null || attached.isEnded()) {
      tasks.put(task, context);
    }
  }
}
