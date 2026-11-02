/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;

/**
 * javax.servlet side of the Spring MVC naming (Framework 5.3), called by {@link
 * JavaxHandlerAdapterAdvice}. Mirror of {@link SpringWebMvcHelperJakarta}. Reads the dispatcher
 * type, method, context path and one request attribute only (D-26).
 */
public final class SpringWebMvcHelperJavax {
  private SpringWebMvcHelperJavax() {}

  public static void nameService(Object servletRequest) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      return;
    }
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    // C-48: only the REQUEST dispatch names the service (never the ERROR dispatch's /error).
    if (request.getDispatcherType() != DispatcherType.REQUEST) {
      return;
    }
    SpringWebMvcHelperCommon.nameService(
        request.getContextPath(),
        request.getMethod(),
        request.getAttribute(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN));
  }
}
