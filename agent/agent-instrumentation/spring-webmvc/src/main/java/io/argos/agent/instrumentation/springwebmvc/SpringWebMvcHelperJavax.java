/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import io.argos.agent.bootstrap.TraceContext;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;

/**
 * javax.servlet side of the Spring MVC naming (Framework 5.3), called by {@link
 * JavaxHandlerAdapterAdvice}. Mirror of {@link SpringWebMvcHelperJakarta}. D-26: reads the
 * dispatcher type, method, context path and one request attribute only (never parameters, body or
 * session).
 */
public final class SpringWebMvcHelperJavax {
  private SpringWebMvcHelperJavax() {}

  /**
   * Enter of {@code handle(request, response, handler)}: names the transaction on the REQUEST
   * dispatch only (C-48: never the ERROR dispatch's {@code /error}; a FORWARD keeps the name).
   * Returns the context when the exit should retry ({@link #retry}), else null.
   */
  public static TraceContext nameService(Object servletRequest, Object handler) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      return null;
    }
    TraceContext context = SpringWebMvcHelperCommon.unnamedContext();
    if (context == null) {
      return null;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    if (request.getDispatcherType() != DispatcherType.REQUEST) {
      return null;
    }
    return SpringWebMvcHelperCommon.nameService(
        context,
        request.getContextPath(),
        request.getMethod(),
        request.getAttribute(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN),
        handler);
  }

  /** Exit of the same call after a handler-method name: the pattern, if the request has it now. */
  public static void retry(TraceContext context, Object servletRequest) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      return;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    if (request.getDispatcherType() != DispatcherType.REQUEST) {
      return;
    }
    SpringWebMvcHelperCommon.retry(
        context,
        request.getContextPath(),
        request.getMethod(),
        request.getAttribute(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN));
  }
}
