/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * javax.servlet side of the request boundary (10 §6.1), called by {@link JavaxServletAdvice} and
 * {@link JavaxFilterAdvice}. Mirror of {@link ServletHelperJakarta} (keep both in step); the logic
 * is in {@link ServletHelperCommon}. Injected into the application class loader: bootstrap API and
 * javax.servlet 3.1 API only, no state. The Servlet 4 mapping is read by {@link
 * ServletMappingHelperJavax}, only on a container that reports Servlet 4 or later.
 *
 * <p>D-26: never calls {@code getParameter*}, {@code getReader}, {@code getInputStream}, {@code
 * getParts} or {@code getSession()}/{@code getSession(true)}. Reads the method, URI, context path,
 * dispatcher type, request attributes, the {@code User-Agent} header, and only what the capture
 * modes ask for: the query string, the remote address, {@code X-Forwarded-For} (trusted proxies
 * set), the whitelisted headers, the request id header and the user id source ({@code
 * getSession(false)} for a session attribute).
 */
public final class ServletHelperJavax {
  private ServletHelperJavax() {}

  /**
   * Enter of {@code Filter.doFilter} / {@code HttpServlet.service}: the transaction this call takes
   * part in, or null (not HTTP, excluded, not a REQUEST dispatch without anchor, not recordable).
   *
   * @param servlet the call is {@code HttpServlet.service} (counts FORWARD, INCLUDE, ERROR and
   *     ASYNC dispatches)
   */
  public static TraceContext enter(Object servletRequest, boolean servlet) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      return null;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    Object anchor = request.getAttribute(ServletHelperCommon.TX_ATTRIBUTE);
    DispatcherType type = request.getDispatcherType();
    if (anchor != null) {
      return ServletHelperCommon.join(anchor, servlet && type != DispatcherType.REQUEST, request);
    }
    if (request.getAttribute(ServletHelperCommon.IGNORED_ATTRIBUTE) != null) {
      return null;
    }
    if (type != DispatcherType.REQUEST) {
      if (request.getAttribute(ServletHelperCommon.UNANCHORED_ATTRIBUTE) == null) {
        request.setAttribute(ServletHelperCommon.UNANCHORED_ATTRIBUTE, true);
        Argos.tracer().countUnanchored();
      }
      return null;
    }
    Tracer tracer = Argos.tracer();
    String method = request.getMethod();
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    String path = ServletHelperCommon.pathWithinApp(uri, contextPath);
    String reason = tracer.ignoreReason(method, path, request.getHeader("User-Agent"));
    if (reason != null) {
      request.setAttribute(ServletHelperCommon.IGNORED_ATTRIBUTE, true);
      tracer.countIgnored(reason);
      return null;
    }
    // 10 §8: tracestate means nothing without a traceparent (W3C), so it is read only then.
    String traceparent = request.getHeader("traceparent");
    TraceContext context =
        ServletHelperCommon.begin(
            tracer,
            method,
            contextPath,
            path,
            traceparent,
            traceparent == null ? null : tracestate(request));
    if (context != null) {
      request.setAttribute(ServletHelperCommon.TX_ATTRIBUTE, context);
      mappingCandidate(context, request, contextPath, method, uri);
      capture(tracer, context, request, method, uri);
    }
    return context;
  }

  /** The request data the capture modes ask for (16 §6.1-§6.4); the core applies the modes. */
  static void capture(
      Tracer tracer, TraceContext context, HttpServletRequest request, String method, String uri) {
    CaptureConfig capture = tracer.capture();
    String query =
        capture.queryStringMode() != CaptureConfig.QUERY_STRING_OFF
            ? request.getQueryString()
            : null;
    String socket =
        capture.remoteIpMode() != CaptureConfig.REMOTE_IP_OFF ? request.getRemoteAddr() : null;
    String forwarded =
        socket != null && capture.trustedProxies().length > 0 ? forwardedFor(request) : null;
    tracer.setHttpRequest(context, method, uri, query, socket, forwarded);
    String[] headers = capture.headerWhitelist();
    if (headers.length > 0) {
      String[] values = new String[headers.length * 2];
      for (int i = 0; i < headers.length; i++) {
        values[2 * i] = headers[i];
        values[2 * i + 1] = request.getHeader(headers[i]);
      }
      tracer.setHeaders(context, values);
    }
    String requestIdHeader = capture.requestIdHeader();
    if (requestIdHeader != null) {
      tracer.setRequestId(context, request.getHeader(requestIdHeader));
    }
    userId(tracer, context, request, capture);
  }

  /**
   * The {@code tracestate} header lines of the request joined with commas (W3C), read up to the
   * bound of {@link ServletHelperCommon#appendTracestate}; null when there is none. The core checks
   * and keeps the value (10 §8, WO-14); it is never logged.
   */
  static String tracestate(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders("tracestate");
    if (values == null || !values.hasMoreElements()) {
      return null;
    }
    StringBuilder joined = new StringBuilder(64);
    int lines = 0;
    while (values.hasMoreElements()) {
      lines++;
      if (!ServletHelperCommon.appendTracestate(joined, values.nextElement(), lines)) {
        break;
      }
    }
    return joined.length() == 0 ? null : joined.toString();
  }

  /**
   * The {@code X-Forwarded-For} value of the request for the tracer: every header line is read, the
   * last {@link ServletHelperCommon#MAX_FORWARDED_LINES} are kept in a fixed ring (the lines
   * trusted proxies append come last, SEC-37), joined and bounded by {@link
   * ServletHelperCommon#forwarded}.
   */
  static String forwardedFor(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders("X-Forwarded-For");
    if (values == null) {
      return null;
    }
    String[] lines = new String[ServletHelperCommon.MAX_FORWARDED_LINES];
    int count = 0;
    while (values.hasMoreElements()) {
      String value = values.nextElement();
      if (value != null) {
        lines[count % lines.length] = value;
        // Past MAX_VALUE lines (never sent in practice) the ring position stays consistent.
        count = count == Integer.MAX_VALUE ? lines.length : count + 1;
      }
    }
    return ServletHelperCommon.forwarded(lines, count);
  }

  /**
   * The raw user id of {@code capture.user-id.source} for the tracer (which applies the mode): a
   * header, an attribute of an existing session ({@code getSession(false)}, never a new session,
   * D-26), or nothing for an {@code mdc:} source: the logging module reads that one from the log
   * events of the transaction (C-125 (3), WO-18).
   */
  static void userId(
      Tracer tracer, TraceContext context, HttpServletRequest request, CaptureConfig capture) {
    if (capture.userIdMode() == CaptureConfig.USER_ID_OFF) {
      return;
    }
    String type = capture.userIdSourceType();
    String key = capture.userIdSourceKey();
    if (CaptureConfig.USER_ID_SOURCE_HEADER.equals(type)) {
      tracer.setUserId(context, request.getHeader(key));
    } else if (CaptureConfig.USER_ID_SOURCE_SESSION.equals(type)) {
      HttpSession session = request.getSession(false);
      if (session != null) {
        try {
          tracer.setUserId(context, ServletHelperCommon.userIdText(session.getAttribute(key)));
        } catch (IllegalStateException invalidated) {
          // The session was invalidated meanwhile: no user id.
        }
      }
    }
  }

  /** Exit of the same call: leaves one level; the outermost exit suspends or ends. */
  public static void exit(
      TraceContext context, Object servletRequest, Object servletResponse, Throwable error) {
    if (!ServletHelperCommon.leave(context) || !(servletRequest instanceof HttpServletRequest)) {
      return;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response =
        servletResponse instanceof HttpServletResponse
            ? (HttpServletResponse) servletResponse
            : null;
    if (context.isEnded()) {
      // Ended meanwhile (a first flush of a streaming response, on this thread right after
      // startAsync or on another one; max-duration): clean up, and drop this thread's binding of
      // it. While the request is in async mode the ended anchor stays, so that the ASYNC or ERROR
      // dispatch that follows (Spring's StreamingResponseBody, SseEmitter.complete()) joins
      // nothing, exactly as when the flush comes after this exit, instead of counting as
      // unanchored: 10 §7.1 counts a dispatch without any anchor only. The container's recycle of
      // the request removes the attribute.
      if (!request.isAsyncStarted()) {
        request.removeAttribute(ServletHelperCommon.TX_ATTRIBUTE);
      }
      ServletHelperCommon.unmapResponse(servletResponse);
      ServletHelperCommon.release(context);
      return;
    }
    // The container's ERROR dispatch of an async request (after onTimeout, or an async error)
    // reports isAsyncStarted() (Tomcat's ERROR state) but completes the request: it ends here with
    // its final status, and the onComplete that follows finds the transaction ended.
    if (request.isAsyncStarted() && request.getDispatcherType() != DispatcherType.ERROR) {
      // The REQUEST dispatch's mapping named the service at enter already: the end may come from a
      // listener, a flush or max-duration, without another exit.
      ServletHelperCommon.suspend(
          context, response == null ? 0 : response.getStatus(), servletResponse);
      if (ServletHelperCommon.asyncListenerEnabled()) {
        try {
          request.getAsyncContext().addListener(listener(context));
        } catch (RuntimeException notAsync) {
          // Completed or dispatched meanwhile (IllegalStateException), or a container that does
          // not accept listeners: the ASYNC dispatch or max-duration ends it.
        }
      }
      return;
    }
    int status = response == null ? 0 : response.getStatus();
    boolean streaming =
        response != null
            && ServletHelperCommon.isUpgrade(status, response.containsHeader("Upgrade"));
    Tracer tracer = Argos.tracer();
    CaptureConfig capture = tracer.capture();
    if (CaptureConfig.USER_ID_SOURCE_SESSION.equals(capture.userIdSourceType())) {
      // The session attribute may have been set by this request (login).
      userId(tracer, context, request, capture);
    }
    request.removeAttribute(ServletHelperCommon.TX_ATTRIBUTE);
    ServletHelperCommon.unmapResponse(servletResponse);
    ServletHelperCommon.end(context, status, rootCause(error), streaming);
  }

  /**
   * The Servlet 4+ {@code HttpServletMapping} of the request as a service name candidate ({@link
   * ServletHelperCommon#mappingCandidate}), unless a module fixed the name; read through {@link
   * ServletMappingHelperJavax} only on a Servlet 4+ container. Called once, right after the REQUEST
   * dispatch began the transaction (the container maps the request before the filter chain, so the
   * outermost filter sees the mapping): the non-fixed candidate names the transaction whichever end
   * comes first, also a flush on the request thread right after {@code startAsync} that ends it
   * before any exit; a module that knows the handler pattern (spring-webmvc) still replaces it.
   */
  static void mappingCandidate(
      TraceContext context,
      HttpServletRequest request,
      String contextPath,
      String method,
      String requestUri) {
    if (context.serviceFixed || !servlet4(request)) {
      return;
    }
    String[] mapping = ServletMappingHelperJavax.mapping(request);
    if (mapping != null) {
      ServletHelperCommon.mappingCandidate(
          context, contextPath, method, requestUri, mapping[0], mapping[1]);
    }
  }

  /**
   * True when the container implements Servlet 4 or later ({@code HttpServletMapping}): the witness
   * of {@link ServletMappingHelperJavax}, read from the servlet context without loading a Servlet 4
   * class.
   */
  static boolean servlet4(HttpServletRequest request) {
    ServletContext servletContext = request.getServletContext();
    return servletContext != null && servletContext.getMajorVersion() >= 4;
  }

  /**
   * The {@code AsyncListener} of {@code context}: a {@code java.lang.reflect.Proxy} of this
   * namespace's interface over {@link AsyncListenerHelper} (see there why it is not a helper class
   * implementing the interface), in this helper's class loader, which sees the servlet API.
   */
  static AsyncListener listener(TraceContext context) {
    return (AsyncListener)
        Proxy.newProxyInstance(
            ServletHelperJavax.class.getClassLoader(),
            new Class<?>[] {AsyncListener.class},
            new AsyncListenerHelper(context, false));
  }

  /**
   * An {@code onComplete} ({@link AsyncListenerHelper#COMPLETE}) or {@code onError} ({@link
   * AsyncListenerHelper#ERROR}) event of the listener of {@code context}.
   */
  static void asyncEvent(TraceContext context, Object event, int kind) {
    if (!(event instanceof AsyncEvent)) {
      return;
    }
    AsyncEvent asyncEvent = (AsyncEvent) event;
    asyncEnded(
        context,
        asyncEvent.getSuppliedRequest(),
        asyncEvent.getSuppliedResponse(),
        kind == AsyncListenerHelper.ERROR ? asyncEvent.getThrowable() : null);
  }

  /**
   * End of an async request by its {@code AsyncListener} ({@link AsyncListenerHelper}): the final
   * status of {@code response}, the attribute and the response mapping removed. Runs on a container
   * callback, not in an inlined advice: each call into the application's request and response
   * (wrappers may be application classes) is guarded on its own, a failure counts as a module error
   * of servlet-async, and the transaction still ends once (with the status recorded so far when the
   * response cannot tell it) instead of staying open until {@code transaction.max-duration-ms}.
   */
  static void asyncEnded(
      TraceContext context, Object servletRequest, Object servletResponse, Throwable error) {
    Throwable failure = null;
    if (servletRequest instanceof HttpServletRequest) {
      try {
        ((HttpServletRequest) servletRequest).removeAttribute(ServletHelperCommon.TX_ATTRIBUTE);
      } catch (Throwable t) {
        failure = t;
      }
    }
    try {
      ServletHelperCommon.unmapResponse(innermost(servletResponse));
    } catch (Throwable t) {
      failure = t;
    }
    if (!context.isEnded()) {
      int status = context.status;
      if (servletResponse instanceof HttpServletResponse) {
        try {
          status = ((HttpServletResponse) servletResponse).getStatus();
        } catch (Throwable t) {
          failure = t;
        }
      }
      ServletHelperCommon.end(context, status, rootCause(error));
    }
    if (failure != null) {
      Argos.tracer().moduleError(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, failure);
    }
  }

  /** The container's own response below every {@code ServletResponseWrapper}. */
  public static Object innermost(Object response) {
    Object current = response;
    for (int i = 0; i < 32 && current instanceof ServletResponseWrapper; i++) {
      ServletResponse wrapped = ((ServletResponseWrapper) current).getResponse();
      if (wrapped == null) {
        break;
      }
      current = wrapped;
    }
    return current;
  }

  /**
   * The exception to record: the root cause of a {@code ServletException} wrapper (Spring's
   * "Request processing failed"), else {@code error} itself.
   */
  static Throwable rootCause(Throwable error) {
    if (error instanceof ServletException) {
      Throwable root = ((ServletException) error).getRootCause();
      if (root != null) {
        return root;
      }
    }
    return error;
  }
}
