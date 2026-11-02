/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;

/**
 * Namespace-independent part of the Spring MVC naming (10 §6.2), used by {@link
 * SpringWebMvcHelperJavax} and {@link SpringWebMvcHelperJakarta}. Injected into the application
 * class loader: bootstrap API only, no state.
 */
public final class SpringWebMvcHelperCommon {
  /** {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} (Framework 5.3-7.x). */
  public static final String BEST_MATCHING_PATTERN =
      "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

  private SpringWebMvcHelperCommon() {}

  /**
   * Fixes the service name of the current transaction from the handler pattern; called on the
   * REQUEST dispatch only (C-48). Does nothing without a transaction, with a name already fixed or
   * without a pattern (the tracer then applies the F-A29 fallback at the end).
   */
  public static void nameService(String contextPath, String method, Object pattern) {
    if (!(pattern instanceof String)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.current();
    if (context == null || context.serviceFixed) {
      return;
    }
    String name = serviceName(contextPath, method, (String) pattern);
    if (name != null) {
      tracer.setServiceName(context, name, true);
    }
  }

  /** {@code [<context path> ]<METHOD> <pattern>} (F-A36 prefix), or null without pattern. */
  public static String serviceName(String contextPath, String method, String pattern) {
    if (pattern == null || pattern.isEmpty() || method == null) {
      return null;
    }
    if (contextPath == null || contextPath.isEmpty()) {
      return method + " " + pattern;
    }
    return contextPath + " " + method + " " + pattern;
  }
}
