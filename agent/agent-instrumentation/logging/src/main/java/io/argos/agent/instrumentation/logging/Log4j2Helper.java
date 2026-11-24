/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import org.apache.logging.log4j.util.StringMap;

/**
 * Helper of the Log4j 2 advice (10 §6.9): the context data a {@code ThreadContextDataInjector}
 * returned for a new log event gets the two Argos keys when it is mutable. A frozen map (the
 * thread's own read-only context of the copy-on-write and default injectors, 10 §18 U-13) is never
 * changed: those events carry no Argos keys (module README). Log4j API types only ({@code
 * StringMap}, Apache-2.0); never logs.
 */
public final class Log4j2Helper {
  private Log4j2Helper() {}

  /**
   * Reads the {@code mdc:<key>} user id from {@code contextData} and adds the trace and transaction
   * ids when the map is a mutable {@code StringMap} without them.
   *
   * @return true when the keys were added
   */
  public static boolean inject(Tracer tracer, TraceContext context, Object contextData) {
    if (!(contextData instanceof StringMap)) {
      return false;
    }
    StringMap map = (StringMap) contextData;
    String userIdKey = LoggingHelper.userIdKey(tracer);
    if (userIdKey != null) {
      LoggingHelper.userId(tracer, context, map.getValue(userIdKey));
    }
    if (map.isFrozen() || map.getValue(LoggingHelper.TRACE_ID_KEY) != null) {
      return false;
    }
    try {
      map.putValue(LoggingHelper.TRACE_ID_KEY, LoggingHelper.traceId(context));
      map.putValue(LoggingHelper.TX_ID_KEY, LoggingHelper.txId(context));
      return true;
    } catch (UnsupportedOperationException readOnly) {
      // A read-only view that does not report itself frozen: left unchanged.
      return false;
    }
  }
}
