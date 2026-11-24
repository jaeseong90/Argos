/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.util.Hashtable;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Helper of the Log4j 1.2 and reload4j advice (10 §6.9): the event takes its MDC copy at creation
 * ({@code getMDCCopy()}, the call Log4j's own {@code AsyncAppender} makes on the logging thread),
 * then the advice replaces {@code mdcCopy} with a copy that adds the two Argos keys. {@code
 * LoggingEvent.getMDC(key)} reads {@code mdcCopy} first, so the keys stay with the event on any
 * thread and after the application's {@code MDC.clear()}. Never logs.
 */
public final class Log4j1Helper {
  private Log4j1Helper() {}

  /** {@code getMDCCopy()} of the event (once per event; Log4j ignores later calls). */
  public static void snapshot(Object event) {
    if (event instanceof LoggingEvent) {
      ((LoggingEvent) event).getMDCCopy();
    }
  }

  /**
   * Reads the {@code mdc:<key>} user id from {@code mdcCopy}; returns a new MDC copy with the
   * entries of {@code mdcCopy} and the two keys, or null when {@code mdcCopy} has the trace id key
   * already (a second constructor of the same event, or an application value, is kept).
   */
  @SuppressWarnings("JdkObsolete") // the field mdcCopy of Log4j 1.2 is a Hashtable
  public static Hashtable<Object, Object> withIds(
      Tracer tracer, TraceContext context, Hashtable<?, ?> mdcCopy) {
    String userIdKey = LoggingHelper.userIdKey(tracer);
    if (userIdKey != null && mdcCopy != null) {
      LoggingHelper.userId(tracer, context, mdcCopy.get(userIdKey));
    }
    if (mdcCopy != null && mdcCopy.containsKey(LoggingHelper.TRACE_ID_KEY)) {
      return null;
    }
    Hashtable<Object, Object> copy =
        new Hashtable<Object, Object>(((mdcCopy == null ? 0 : mdcCopy.size()) + 2) * 4 / 3 + 1);
    if (mdcCopy != null) {
      copy.putAll(mdcCopy);
    }
    copy.put(LoggingHelper.TRACE_ID_KEY, LoggingHelper.traceId(context));
    copy.put(LoggingHelper.TX_ID_KEY, LoggingHelper.txId(context));
    return copy;
  }
}
