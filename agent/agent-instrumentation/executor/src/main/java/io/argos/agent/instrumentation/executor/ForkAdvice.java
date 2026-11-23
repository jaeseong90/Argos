/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Submit side of 10 §6.6 for {@code ForkJoinTask.fork()} (enter): the forking thread's transaction
 * is attached to the forked task, which a pool worker (or a joining thread) later runs through
 * {@code doExec}. This is how the subtasks of a parallel stream on the common pool join the request
 * that started the stream: the root task runs on the calling thread, every split is forked from a
 * thread on which the transaction is active. A context already attached is kept.
 */
public final class ForkAdvice {
  private ForkAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void enter(@Advice.This Object task) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
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
