/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.MappingMatch;

/**
 * The javax.servlet 4.0 {@code HttpServletMapping} of a request (10 §6.1, F-A02 fallback for
 * applications without Spring MVC), read once when the REQUEST dispatch begins the transaction.
 * Compiled on its own against {@code javax.servlet-api} 4.0 ({@code src/servlet4}, {@code
 * compileOnly}), so that the other javax helpers stay compiled against Servlet 3.1. {@link
 * ServletHelperJavax} calls it only when the container reports Servlet 4 or later ({@code
 * ServletContext.getMajorVersion()}): on a Servlet 3.1 container this class is injected but never
 * linked, so {@code HttpServletMapping} is never resolved there. Injected into the application
 * class loader: javax.servlet 4.0 API only, no state.
 */
public final class ServletMappingHelperJavax {
  private ServletMappingHelperJavax() {}

  /** {@code {pattern, match name}} of the request's mapping, or null when there is none. */
  public static String[] mapping(HttpServletRequest request) {
    HttpServletMapping mapping = request.getHttpServletMapping();
    if (mapping == null) {
      return null;
    }
    MappingMatch match = mapping.getMappingMatch();
    String pattern = mapping.getPattern();
    if (match == null || pattern == null) {
      return null;
    }
    return new String[] {pattern, match.name()};
  }
}
