/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.TraceContext;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * javax.servlet side of the servlet-async hooks (Tomcat 9), called by {@link
 * JavaxStartAsyncAdvice}. Mirror of {@link AsyncHelperJakarta}. Injected into the container's class
 * loader: bootstrap API and javax.servlet 3.1 API only, no state. Reads a request attribute, the
 * response status and the response wrappers' {@code getResponse()} only (D-26).
 */
public final class AsyncHelperJavax {
  private AsyncHelperJavax() {}

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
