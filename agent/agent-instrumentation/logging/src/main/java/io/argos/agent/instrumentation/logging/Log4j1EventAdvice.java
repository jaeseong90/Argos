/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.util.Hashtable;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of the {@code org.apache.log4j.spi.LoggingEvent} constructors of Log4j 1.2
 * and reload4j (10 §6.9, constructor exit only, A-7): when a transaction is active, the event takes
 * its MDC copy now ({@link Log4j1Helper#snapshot}) and {@code mdcCopy} is set to a copy with {@code
 * argos.trace_id} and {@code argos.tx_id}. {@code mdcCopy} is the only value written ({@code
 * argos-advice-allowlist.txt}, 10 §5.3 A-5); the thread MDC stays as it is.
 */
public final class Log4j1EventAdvice {
  private Log4j1EventAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(
      @Advice.This Object event,
      @Advice.FieldValue(value = "mdcCopy", readOnly = false) Hashtable<?, ?> mdcCopy) {
    if (Argos.skip(LoggingModule.MODULE_ID)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.current();
    if (context == null) {
      return;
    }
    Log4j1Helper.snapshot(event);
    Hashtable<Object, Object> withIds = Log4j1Helper.withIds(tracer, context, mdcCopy);
    if (withIds != null) {
      mdcCopy = withIds;
    }
  }
}
