/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/**
 * The path of a request as Spring MVC matches it: decoded and below the context path. A path check
 * on the raw {@code getRequestURI()} is not enough, because {@code /api/v1/auth/logi%6E} reaches
 * the login handler too; a limit or a header rule that compared raw text could be walked around.
 */
final class RequestPaths {

  private RequestPaths() {}

  /** The decoded path below the context path; never null. */
  static String of(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    String pathInfo = request.getPathInfo();
    String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
    if (!path.isEmpty()) {
      return path;
    }
    // A servlet mapped as "/*" or a request not yet routed: the URI, decoded.
    return decoded(withoutContext(request, request.getRequestURI()));
  }

  /**
   * The path of the request a response answers: on an error dispatch the path of the request that
   * failed, otherwise {@link #of(HttpServletRequest)}.
   */
  static String original(HttpServletRequest request) {
    if (request.getDispatcherType() == DispatcherType.ERROR
        && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String failed) {
      return decoded(withoutContext(request, failed));
    }
    return of(request);
  }

  /** Whether {@code path} is the REST API ({@code /api} and below). */
  static boolean isApi(String path) {
    return path.equals("/api") || path.startsWith("/api/");
  }

  private static String withoutContext(HttpServletRequest request, String uri) {
    if (uri == null) {
      return "";
    }
    String context = request.getContextPath();
    return context != null && !context.isEmpty() && uri.startsWith(context)
        ? uri.substring(context.length())
        : uri;
  }

  private static String decoded(String path) {
    try {
      return UriUtils.decode(path, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformed) {
      return path;
    }
  }
}
