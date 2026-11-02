/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;

/**
 * Namespace-independent part of the request boundary state machine (10 §7.1), shared by {@link
 * ServletHelperJavax} and {@link ServletHelperJakarta}. Injected into the application class loader
 * (10 §5.4): bootstrap API only, no state.
 *
 * <ul>
 *   <li>NONE -> ACTIVE: REQUEST dispatch, not excluded: {@link #begin}, depth 1, anchored on the
 *       request attribute {@value #TX_ATTRIBUTE}.
 *   <li>ACTIVE: every nested filter/servlet call and every later dispatch of the same request
 *       ({@link #join}) adds one level; {@link #leave} removes it.
 *   <li>Depth 0 with {@code isAsyncStarted()}: ASYNC_PENDING ({@link #suspend}); the ASYNC dispatch
 *       that completes the request ends it later.
 *   <li>Depth 0 otherwise: ENDING ({@link #end}): final status, D-21 (an exception that escaped
 *       with a status below 400 is a 500), single end (CAS in the tracer).
 * </ul>
 */
public final class ServletHelperCommon {
  /** Request attribute that anchors the transaction (F-A28: the request is the truth). */
  public static final String TX_ATTRIBUTE = "argos.tx";

  /** Request attribute of an excluded request (10 §7.1 IGNORED): later dispatches stay ignored. */
  public static final String IGNORED_ATTRIBUTE = "argos.ignored";

  private ServletHelperCommon() {}

  /**
   * Joins the transaction anchored on the request; null when there is none (or it ended). The
   * dispatches of one request run one after another (the container hands an ASYNC dispatch over
   * only after the previous thread left the request), so the depth needs no atomic update.
   */
  @SuppressWarnings("NonAtomicVolatileUpdate")
  public static TraceContext join(Object anchor) {
    if (!(anchor instanceof TraceContext)) {
      return null;
    }
    TraceContext context = (TraceContext) anchor;
    if (context.isEnded()) {
      return null;
    }
    context.dispatchDepth = context.dispatchDepth + 1;
    return context;
  }

  /** The path within the web application: {@code requestUri} without the context path. */
  public static String pathWithinApp(String requestUri, String contextPath) {
    if (requestUri == null || requestUri.isEmpty()) {
      return "/";
    }
    if (contextPath != null
        && !contextPath.isEmpty()
        && requestUri.startsWith(contextPath)
        && requestUri.length() > contextPath.length()) {
      return requestUri.substring(contextPath.length());
    }
    if (contextPath != null && !contextPath.isEmpty() && requestUri.equals(contextPath)) {
      return "/";
    }
    return requestUri;
  }

  /**
   * The provisional service name {@code [<context path> ]<METHOD> <path>} (F-A36 prefix for a
   * non-root web application). A module that knows the handler pattern replaces it ({@code
   * setServiceName(.., true)}); otherwise the tracer normalizes it at the end (F-A29).
   */
  public static String provisionalName(String contextPath, String method, String path) {
    if (contextPath == null || contextPath.isEmpty()) {
      return method + " " + path;
    }
    return contextPath + " " + method + " " + path;
  }

  /** NONE -> ACTIVE: a new HTTP transaction at depth 1, or null when the tracer cannot record. */
  public static TraceContext begin(
      Tracer tracer, String method, String contextPath, String pathWithinApp) {
    TraceContext context =
        tracer.begin(
            Kind.HTTP, provisionalName(contextPath, method, pathWithinApp), System.nanoTime());
    if (context != null) {
      context.dispatchDepth = 1;
    }
    return context;
  }

  /** False when {@code capture.remote-ip} is off: the helper does not even read the address. */
  public static boolean capturesRemoteIp(Tracer tracer) {
    return tracer.capture().remoteIpMode() != CaptureConfig.REMOTE_IP_OFF;
  }

  /** Leaves one dispatch level; true when it was the outermost one (depth 0). */
  public static boolean leave(TraceContext context) {
    int depth = context.dispatchDepth - 1;
    if (depth < 0) {
      depth = 0;
    }
    context.dispatchDepth = depth;
    return depth == 0;
  }

  /**
   * ASYNC_PENDING: the request continues asynchronously. The transaction stays open (the ASYNC
   * dispatch that completes the request ends it) and is unbound from this container thread.
   */
  public static void suspend(TraceContext context) {
    context.asyncStarted = true;
    Tracer tracer = Argos.tracer();
    if (tracer.current() == context) {
      // Binding null unbinds the context from this thread (the returned scope is not needed).
      tracer.activate(null);
    }
  }

  /** ENDING: ends the transaction with the final status (D-21). */
  public static void end(TraceContext context, int status, Throwable error) {
    int finalStatus = error != null && status < 400 ? 500 : status;
    Argos.tracer().end(context, finalStatus, null, null, error, System.nanoTime());
  }
}
