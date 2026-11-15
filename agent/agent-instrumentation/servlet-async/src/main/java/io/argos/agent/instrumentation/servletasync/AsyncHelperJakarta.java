/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.TraceContext;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * jakarta.servlet side of the servlet-async hooks (Tomcat 10.1, 11), called by {@link
 * JakartaStartAsyncAdvice}. Mirror of {@link AsyncHelperJavax}. Injected into the container's class
 * loader: bootstrap API and jakarta.servlet 5.0 API only, no state. Reads a request attribute, the
 * response status and the response wrappers' {@code getResponse()} only (D-26).
 */
public final class AsyncHelperJakarta {
  private AsyncHelperJakarta() {}

  /**
   * Exit of {@code Request.startAsync(request, response)}: {@code containerRequest} is the
   * container's request (the anchor's holder), {@code response} the response the application passed
   * (possibly wrapped).
   */
  public static void started(Object containerRequest, Object response) {
    if (!(containerRequest instanceof HttpServletRequest)) {
      return;
    }
    TraceContext context =
        AsyncHelperCommon.live(
            ((HttpServletRequest) containerRequest).getAttribute(AsyncHelperCommon.TX_ATTRIBUTE));
    if (context == null) {
      return;
    }
    Object innermost = innermost(response);
    int status =
        innermost instanceof HttpServletResponse
            ? ((HttpServletResponse) innermost).getStatus()
            : 0;
    AsyncHelperCommon.started(context, status, innermost);
  }

  /** The container's own response below every {@code ServletResponseWrapper}. */
  static Object innermost(Object response) {
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
}
