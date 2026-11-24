/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code injectContextData(List, StringMap)} of Log4j 2's {@code
 * ThreadContextDataInjector$ForDefaultThreadContextMap}, {@code $ForGarbageFreeThreadContextMap}
 * and {@code $ForCopyOnWriteThreadContextMap} (10 §6.9): Log4j calls it once per new log event on
 * the logging thread; the returned context data becomes the event's. {@link Log4j2Helper} adds the
 * two keys when that map is mutable; the return value itself is never replaced (read only).
 */
public final class Log4j2InjectAdvice {
  private Log4j2InjectAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Return Object contextData) {
    if (Argos.skip(LoggingModule.MODULE_ID)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.current();
    if (context != null && contextData != null) {
      Log4j2Helper.inject(tracer, context, contextData);
    }
  }
}
