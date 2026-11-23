/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Constructor exit (A-7) of the concrete {@code CompletableFuture$Uni*} stages: when the calling
 * thread is inside an asynchronous stage method ({@link AsyncStageAdvice} armed the marker), the
 * marker is consumed and the thread's transaction attached to the new stage, which {@link
 * TryFireAdvice} restores when the stage runs on the executor. A stage created outside such a call
 * (synchronous {@code thenApply}, a {@code UniRelay} of {@code thenCompose}) attaches nothing.
 */
public final class UniCreatedAdvice {
  private UniCreatedAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void exit(@Advice.This Object stage) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return;
    }
    if (CallDepth.value(CallDepth.EXECUTOR) != ExecutorModule.CF_STAGE_ARMED) {
      return;
    }
    CallDepth.setValue(CallDepth.EXECUTOR, 0L);
    TraceContext context = Argos.tracer().current();
    if (context == null || context.isEnded()) {
      return;
    }
    ContextStore<Object, TraceContext> tasks =
        (ContextStore) ContextStore.of(Runnable.class, TraceContext.class);
    tasks.put(stage, context);
  }
}
