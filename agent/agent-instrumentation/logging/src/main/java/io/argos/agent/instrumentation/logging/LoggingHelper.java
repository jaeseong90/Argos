/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper of the logging module shared by the three frameworks (10 §6.9): the MDC keys and their
 * values, the deferred-processing context of Logback events and the {@code mdc:<key>} user id
 * source (ADR-0010 C-125 (3)). JDK and bootstrap types only.
 *
 * <p>The key names are a public contract (O-R03): {@value #TRACE_ID_KEY} is the W3C trace id (32
 * lower-case hex digits, the {@code traceparent} id), {@value #TX_ID_KEY} the transaction id (16
 * lower-case hex digits of the unsigned 64-bit {@code tx_id}).
 */
public final class LoggingHelper {
  public static final String TRACE_ID_KEY = "argos.trace_id";
  public static final String TX_ID_KEY = "argos.tx_id";

  private LoggingHelper() {}

  /** The trace id of {@code context}: 32 lower-case hex digits. */
  public static String traceId(TraceContext context) {
    char[] out = new char[32];
    hex(out, 0, context.traceIdHi);
    hex(out, 16, context.traceIdLo);
    return new String(out);
  }

  /** The transaction id of {@code context}: 16 lower-case hex digits. */
  public static String txId(TraceContext context) {
    char[] out = new char[16];
    hex(out, 0, context.txId);
    return new String(out);
  }

  private static void hex(char[] out, int offset, long value) {
    long rest = value;
    for (int i = 15; i >= 0; i--) {
      int digit = (int) (rest & 0xF);
      out[offset + i] = (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
      rest >>>= 4;
    }
  }

  /**
   * {@code map} when it has the trace id key already (an application value is kept), otherwise a
   * new unmodifiable map with the entries of {@code map} (none when null) and the two keys of
   * {@code context}. Logback's event map (10 §6.9).
   */
  public static Map<String, String> withIds(Map<String, String> map, TraceContext context) {
    if (map != null && map.containsKey(TRACE_ID_KEY)) {
      return map;
    }
    Map<String, String> copy =
        new LinkedHashMap<String, String>(((map == null ? 0 : map.size()) + 2) * 4 / 3 + 1);
    if (map != null) {
      copy.putAll(map);
    }
    copy.put(TRACE_ID_KEY, traceId(context));
    copy.put(TX_ID_KEY, txId(context));
    return Collections.unmodifiableMap(copy);
  }

  /**
   * Attaches {@code context} to a log event that another thread will format later (Logback's {@code
   * prepareForDeferredProcessing()}, {@code AsyncAppender}); the store holds the event weakly and
   * is bounded ({@link ContextStore}).
   */
  public static void attach(Object event, TraceContext context) {
    store(event).put(event, context);
  }

  /** The context attached to {@code event} by {@link #attach}, or null. */
  public static TraceContext attached(Object event) {
    return store(event).get(event);
  }

  @SuppressWarnings("unchecked")
  private static ContextStore<Object, TraceContext> store(Object event) {
    // Keyed by the event's own class name: no Logback type here, the module is compiled without
    // Logback (EPL/LGPL, not on the license allow-list; docs/licenses/dev-only.md).
    return ContextStore.of((Class<Object>) event.getClass(), TraceContext.class);
  }

  /**
   * The MDC key of {@code capture.user-id.source: mdc:<key>} when user ids are recorded ({@code
   * plain} or {@code hashed}); null for any other source or with {@code capture.user-id.mode: off}.
   */
  public static String userIdKey(Tracer tracer) {
    CaptureConfig capture = tracer.capture();
    if (capture.userIdMode() == CaptureConfig.USER_ID_OFF
        || !CaptureConfig.USER_ID_SOURCE_MDC.equals(capture.userIdSourceType())) {
      return null;
    }
    return capture.userIdSourceKey();
  }

  /**
   * Hands an MDC value of the user id key to the tracer, which keeps the first decided value of a
   * transaction and applies {@code capture.user-id.mode} (16 §6.3, SEC-32). Only text and numbers
   * count; anything else (and null) is ignored. The value is never logged.
   */
  public static void userId(Tracer tracer, TraceContext context, Object value) {
    if (value instanceof String || value instanceof Number) {
      tracer.setUserId(context, value.toString());
    }
  }
}
