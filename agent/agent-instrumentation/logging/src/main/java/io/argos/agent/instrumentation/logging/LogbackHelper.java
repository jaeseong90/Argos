/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import org.slf4j.MDC;

/**
 * Helper of the Logback advice (10 §6.9): reads the {@code mdc:<key>} user id source when a Logback
 * event is created (ADR-0010 C-125 (3)). Logback events take their MDC from the thread's SLF4J MDC
 * adapter, so the value read here is the one the event's MDC copy holds; the read has no side
 * effect (SLF4J is initialized: a Logback logger is creating the event). Uses the SLF4J API only
 * (MIT), never Logback types, and never logs.
 */
public final class LogbackHelper {
  private LogbackHelper() {}

  /** The user id of the MDC key {@code key} of the current thread, handed to the tracer. */
  public static void userId(Tracer tracer, TraceContext context, String key) {
    LoggingHelper.userId(tracer, context, MDC.get(key));
  }
}
