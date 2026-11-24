/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of the constructors (with arguments) of Logback's {@code LoggingEvent} (10
 * §6.9, constructor exit only, A-7): with {@code capture.user-id.source: mdc:<key>} the value of
 * the key in the logging thread's MDC is handed to the tracer when the event is created (ADR-0010
 * C-125 (3)), whether or not a layout ever reads the event's MDC. Nothing else is done here and
 * nothing of the event is changed.
 */
public final class LogbackEventAdvice {
  private LogbackEventAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit() {
    if (Argos.skip(LoggingModule.MODULE_ID)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    String key = LoggingHelper.userIdKey(tracer);
    if (key == null) {
      return;
    }
    TraceContext context = tracer.current();
    if (context != null) {
      LogbackHelper.userId(tracer, context, key);
    }
  }
}
