/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the asynchronous dependent-stage methods of {@code CompletableFuture} ({@code
 * thenApplyAsync}, {@code thenAcceptAsync}, {@code thenRunAsync}, {@code thenComposeAsync}, {@code
 * whenCompleteAsync}, {@code handleAsync}, with and without an executor; 10 §6.6, CompletableFuture
 * row). Inside a transaction, arms a one-shot marker on the calling thread (the value slot of
 * {@link CallDepth#EXECUTOR}) for the duration of the call: the first {@code
 * CompletableFuture$Uni*} stage that the call constructs takes the transaction ({@link
 * UniCreatedAdvice}). The stage object is created by this very call on this thread on JDK 8-25, so
 * no shared object carries the context in between (a non-capturing lambda passed by two requests at
 * once is one object). Synchronous {@code thenApply} and friends arm nothing, so their stages stay
 * untouched.
 *
 * <p>Exit restores the previous value (nested calls from a direct executor), paired by the enter
 * value (C-24). The user function (argument 0) decides the exclusions of {@link ExecutorModule}.
 */
public final class AsyncStageAdvice {
  private AsyncStageAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Long enter(@Advice.Argument(0) Object function) {
    if (Argos.skip(ExecutorModule.MODULE_ID)) {
      return null;
    }
    if (function == null) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null || context.isEnded()) {
      return null;
    }
    String type = function.getClass().getName();
    if (type.startsWith("java.lang.")
        || type.startsWith("jdk.")
        || type.startsWith("sun.")
        || type.startsWith("reactor.")
        || type.startsWith("io.argos.agent.")
        || type.startsWith("org.apache.tomcat.")
        || type.startsWith("org.apache.coyote.")
        || type.startsWith("org.apache.catalina.")
        || (type.startsWith("io.") && type.startsWith("netty.", 3))) {
      return null;
    }
    Long previous = Long.valueOf(CallDepth.value(CallDepth.EXECUTOR));
    CallDepth.setValue(CallDepth.EXECUTOR, ExecutorModule.CF_STAGE_ARMED);
    return previous;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Long previous) {
    if (previous == null) {
      return;
    }
    CallDepth.setValue(CallDepth.EXECUTOR, previous.longValue());
  }
}
