/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * The {@code AsyncListener} of an async request (10 §6.7, 10 §7.1 ASYNC_PENDING -> ENDING), as the
 * invocation handler of a {@code java.lang.reflect.Proxy} that implements the namespace's {@code
 * AsyncListener} ({@link ServletHelperJavax#listener}, {@link ServletHelperJakarta#listener}). A
 * helper class that implemented {@code javax.servlet.AsyncListener} itself could not be defined in
 * a loader without javax.servlet (a class's interfaces are resolved when it is defined, and the
 * helpers of both namespaces go into every matching loader); this handler implements a JDK
 * interface only.
 *
 * <ul>
 *   <li>{@code onComplete}: ends with the response status, unless the transaction already ended
 *       (the ASYNC dispatch that completed the request, the first flush of a streaming response,
 *       {@code transaction.max-duration-ms}): one end only.
 *   <li>{@code onError}: ends with the status, 500 when below 400, and the exception.
 *   <li>{@code onTimeout}: marks the timeout and leaves the end to the container's handling that
 *       follows (an ASYNC or ERROR dispatch, then {@code onComplete}), so the transaction keeps the
 *       status the container sets, or 500 when that stays below 400.
 *   <li>{@code onStartAsync}: nothing (the dispatch that called {@code startAsync} again registers
 *       a new listener at its exit).
 * </ul>
 *
 * <p>One instance per async cycle, registered by the servlet helper at the exit of the dispatch
 * that left the request in async mode (works on every container). It holds the transaction's
 * context and its namespace only (A-9: no request, response or application object), and the
 * container drops it when the cycle ends.
 */
public final class AsyncListenerHelper implements InvocationHandler {
  /** Event kinds passed to the namespace helpers. */
  static final int COMPLETE = 0;

  static final int ERROR = 1;

  private final TraceContext context;
  private final boolean jakarta;

  public AsyncListenerHelper(TraceContext context, boolean jakarta) {
    this.context = context;
    this.jakarta = jakarta;
  }

  /**
   * The container calls this directly (not an inlined advice with {@code suppress = Throwable}):
   * nothing may escape to the container thread (10 §5.3), so every listener event runs under one
   * guard, and a failure is counted as a module error of servlet-async ({@code Tracer.moduleError},
   * 10 §5.2).
   */
  @Override
  @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
  public Object invoke(Object proxy, Method method, Object[] args) {
    String name = method.getName();
    Object event = args != null && args.length > 0 ? args[0] : null;
    switch (name) {
      case "hashCode":
        return System.identityHashCode(proxy);
      case "equals":
        return proxy == event;
      case "toString":
        return "ArgosAsyncListener";
      default:
        break;
    }
    try {
      switch (name) {
        case "onComplete":
          event(event, COMPLETE);
          break;
        case "onError":
          event(event, ERROR);
          break;
        case "onTimeout":
          context.asyncTimedOut = true;
          break;
        default:
          // onStartAsync and anything else: nothing to do.
          break;
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, t);
    }
    return null;
  }

  private void event(Object event, int kind) {
    if (jakarta) {
      ServletHelperJakarta.asyncEvent(context, event, kind);
    } else {
      ServletHelperJavax.asyncEvent(context, event, kind);
    }
  }

  /** The transaction this listener ends (tests). */
  TraceContext context() {
    return context;
  }
}
