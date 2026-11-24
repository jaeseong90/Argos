/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code getMDCPropertyMap()} and {@code getMdc()} of Logback's {@code LoggingEvent}
 * (10 §6.9): the map returned to the caller (pattern converters, encoders, appenders) is the
 * event's map plus {@code argos.trace_id} and {@code argos.tx_id} when a transaction is active on
 * this thread, or when the event was handed to another thread with the transaction attached ({@code
 * LogbackDeferredAdvice}). The return value is the only value written ({@code
 * argos-advice-allowlist.txt}, 10 §5.3 A-5); the event's own field and the thread MDC stay as they
 * are. {@code CallDepth.LOGGING}: {@code getMdc()} calls {@code getMDCPropertyMap()} in Logback
 * 1.2, only the outermost call adds the keys.
 */
public final class LogbackMdcAdvice {
  private LogbackMdcAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter() {
    if (Argos.skip(LoggingModule.MODULE_ID)) {
      return null;
    }
    return CallDepth.enter(CallDepth.LOGGING) == 1 ? Boolean.TRUE : Boolean.FALSE;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object entered,
      @Advice.This Object event,
      @Advice.Return(readOnly = false) Map<String, String> map,
      @Advice.Thrown Throwable error) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.LOGGING);
    if (!Boolean.TRUE.equals(entered) || error != null) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      context = LoggingHelper.attached(event);
    }
    if (context != null) {
      map = LoggingHelper.withIds(map, context);
    }
  }
}
