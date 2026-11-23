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
 * Execution side of 10 §6.6 for the dependent stages of {@code CompletableFuture} ({@code
 * thenApplyAsync}, {@code thenAcceptAsync}, {@code thenRunAsync}, {@code thenComposeAsync}, {@code
 * whenCompleteAsync}, {@code handleAsync}): inlined into {@code tryFire(int mode)} of the concrete
 * {@code CompletableFuture$Uni*} classes, it acts only in mode {@code ASYNC} (1), the call that
 * {@code Completion.run()}/{@code exec()} make on the executor's thread (JDK 8-25, U-12). The
 * synchronous modes run on the completing or registering thread and are left alone, so a
 * synchronous {@code thenApply} is never touched.
 *
 * <p>Same contract as {@link RunAdvice}: enter removes the stage's context (attached by {@link
 * UniCreatedAdvice}) and activates it, exit closes what enter opened.
 */
public final class TryFireAdvice {
  private TryFireAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object enter(@Advice.This Object stage, @Advice.Argument(0) int mode) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return null;
    }
    if (mode != ExecutorModule.CF_ASYNC_MODE) {
      return null;
    }
    ContextStore<Object, TraceContext> tasks =
        (ContextStore) ContextStore.of(Runnable.class, TraceContext.class);
    TraceContext context = tasks.remove(stage);
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
            tracer.dict(DictKind.CLASS, stage.getClass().getName()),
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
