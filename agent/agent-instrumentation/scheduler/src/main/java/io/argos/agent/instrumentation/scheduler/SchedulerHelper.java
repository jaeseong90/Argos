/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;

/**
 * The common rule of 10 §6.8 for every entry point of the module (bootstrap API only, injected
 * helper without state):
 *
 * <ul>
 *   <li>no transaction on the thread: {@code begin(BATCH, name)} with the service name fixed; the
 *       enter value is the {@link TraceContext} and the exit ends it (status 0, 11 {@code
 *       http_status} "BATCH는 0");
 *   <li>a transaction of the same entry point (same kind, same fixed name): a nested call of the
 *       same job, nothing is recorded (enter value null);
 *   <li>any other transaction (HTTP request, a Quartz job starting a Spring Batch job): a METHOD
 *       step named after the text without the {@code BATCH } prefix; the enter value is {@code
 *       {context, seq}}.
 * </ul>
 *
 * <p>Nothing here throws into the application (the advice suppresses, the tracer never throws) and
 * nothing changes the application's exception or return value.
 */
public final class SchedulerHelper {
  /** Prefix of every service name of the module (10 §6.8). */
  public static final String PREFIX = "BATCH ";

  /** Status of an ended BATCH transaction (11 {@code http_status}: 0 for BATCH). */
  static final int BATCH_STATUS = 0;

  private SchedulerHelper() {}

  /**
   * Opens what the entry point named {@code serviceName} records on the current thread; returns the
   * enter value for {@link #exit}, or null when nothing was opened (no name, the agent cannot
   * record, a nested call of the same entry point).
   */
  public static Object enter(String serviceName) {
    if (serviceName == null || !serviceName.startsWith(PREFIX)) {
      return null;
    }
    Tracer tracer = Argos.tracer();
    TraceContext current = tracer.current();
    long now = System.nanoTime();
    if (current == null) {
      TraceContext context = tracer.begin(Kind.BATCH, serviceName, now);
      if (context == null) {
        return null;
      }
      tracer.setServiceName(context, serviceName, true);
      return context;
    }
    if (sameEntryPoint(current, serviceName)) {
      return null;
    }
    String stepName = serviceName.substring(PREFIX.length());
    int seq =
        tracer.stepStart(current, StepType.METHOD, tracer.dict(DictKind.METHOD, stepName), 0L, now);
    if (seq < 0) {
      return null;
    }
    return new Object[] {current, Integer.valueOf(seq)};
  }

  /**
   * Closes what {@link #enter} opened: ends the transaction or the step. {@code error} is the
   * thrown exception or the failure the entry point recorded itself (a FAILED Spring Batch step);
   * {@code errorClass} and {@code errorMessage} describe a failure without an exception (null
   * otherwise).
   */
  public static void exit(Object opened, Throwable error, String errorClass, String errorMessage) {
    if (opened == null) {
      return;
    }
    Tracer tracer = Argos.tracer();
    long now = System.nanoTime();
    if (opened instanceof TraceContext) {
      tracer.end(
          (TraceContext) opened,
          BATCH_STATUS,
          error == null ? errorClass : null,
          error == null ? errorMessage : null,
          error,
          now);
      return;
    }
    if (opened instanceof Object[]) {
      Object[] step = (Object[]) opened;
      tracer.stepEnd(
          (TraceContext) step[0],
          ((Integer) step[1]).intValue(),
          0,
          error != null || errorClass != null,
          0L,
          now);
    }
  }

  /** The current transaction was begun by the same entry point (a nested call). */
  static boolean sameEntryPoint(TraceContext current, String serviceName) {
    return current.kind == Kind.BATCH
        && current.serviceFixed
        && serviceName.equals(current.serviceName);
  }

  /**
   * The user class of an instance's class name (WO-18 assumption A-1): a generated subclass ({@code
   * Foo$$SpringCGLIB$$0}, {@code Foo$$EnhancerBySpringCGLIB$$1a2b}) is cut before {@code $$}; a JDK
   * proxy ({@code jdk.proxy2.$Proxy12}, {@code com.sun.proxy.$Proxy12}) is replaced by {@code
   * declaringClassName}; null falls back to {@code declaringClassName}.
   */
  static String userClassName(String instanceClassName, String declaringClassName) {
    if (instanceClassName == null) {
      return declaringClassName;
    }
    int generated = instanceClassName.indexOf("$$");
    if (generated > 0) {
      return instanceClassName.substring(0, generated);
    }
    int dot = instanceClassName.lastIndexOf('.');
    if (instanceClassName.startsWith("$Proxy", dot + 1) && declaringClassName != null) {
      return declaringClassName;
    }
    return instanceClassName;
  }
}
