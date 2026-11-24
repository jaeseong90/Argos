/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the entry of {@code prepareForDeferredProcessing()} of Logback's {@code LoggingEvent}
 * (10 §6.9): an {@code AsyncAppender} (and any appender that formats on another thread) calls it on
 * the logging thread before queueing the event. The active transaction is attached to the event, so
 * that the worker thread's {@code getMDCPropertyMap()} adds the keys of the transaction that
 * created the event ({@code LogbackMdcAdvice}). Nothing of the event is changed.
 */
public final class LogbackDeferredAdvice {
  private LogbackDeferredAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.This Object event) {
    if (Argos.skip(LoggingModule.MODULE_ID)) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context != null) {
      LoggingHelper.attach(event, context);
    }
  }
}
