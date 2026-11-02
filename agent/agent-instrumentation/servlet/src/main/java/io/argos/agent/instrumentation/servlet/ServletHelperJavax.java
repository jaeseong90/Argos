/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * javax.servlet side of the request boundary (10 §6.1), called by {@link JavaxServletAdvice}.
 * Mirror of {@link ServletHelperJakarta} (keep both in step); the logic is in {@link
 * ServletHelperCommon}. Injected into the application class loader: bootstrap API and javax.servlet
 * 3.1 API only, no state.
 *
 * <p>D-26: never calls {@code getParameter*}, {@code getReader}, {@code getInputStream} or {@code
 * getSession}; reads only the method, URI, context path, dispatcher type, the {@code User-Agent}
 * header, the remote address (unless {@code capture.remote-ip=off}), request attributes and the
 * response status.
 */
public final class ServletHelperJavax {
  private ServletHelperJavax() {}

  /**
   * Enter of {@code Filter.doFilter} / {@code HttpServlet.service}: the transaction this call takes
   * part in, or null (not HTTP, excluded, not a REQUEST dispatch without anchor, not recordable).
   */
  public static TraceContext enter(Object servletRequest) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      return null;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    Object anchor = request.getAttribute(ServletHelperCommon.TX_ATTRIBUTE);
    if (anchor != null) {
      return ServletHelperCommon.join(anchor);
    }
    if (request.getDispatcherType() != DispatcherType.REQUEST
        || request.getAttribute(ServletHelperCommon.IGNORED_ATTRIBUTE) != null) {
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
    TraceContext context = ServletHelperCommon.begin(tracer, method, contextPath, path);
    if (context != null) {
      request.setAttribute(ServletHelperCommon.TX_ATTRIBUTE, context);
      String remoteAddress =
          ServletHelperCommon.capturesRemoteIp(tracer) ? request.getRemoteAddr() : null;
      tracer.setHttp(context, method, uri, remoteAddress, null);
    }
    return context;
  }

  /** Exit of the same call: leaves one level; the outermost exit suspends or ends. */
  public static void exit(
      TraceContext context, Object servletRequest, Object servletResponse, Throwable error) {
    if (!ServletHelperCommon.leave(context) || !(servletRequest instanceof HttpServletRequest)) {
      return;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    if (request.isAsyncStarted()) {
      ServletHelperCommon.suspend(context);
      return;
    }
    int status =
        servletResponse instanceof HttpServletResponse
            ? ((HttpServletResponse) servletResponse).getStatus()
            : 0;
    request.removeAttribute(ServletHelperCommon.TX_ATTRIBUTE);
    ServletHelperCommon.end(context, status, rootCause(error));
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
