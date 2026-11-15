/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.argos.server.ops.management.MetricsAccessPolicy.Decision;
import io.argos.server.web.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * The only door of the management port (8081, SEC-55, 16 §7.12). Spring Security is not used here
 * (WO-24 adds it for the HTTP API); this filter is small enough to read in one go.
 *
 * <ul>
 *   <li>{@code <base>/health} and its groups ({@code /health/liveness}, {@code /health/readiness}):
 *       open, the endpoint never shows details.
 *   <li>{@code <base>/info} and {@code <base>/prometheus}: {@link MetricsAccessPolicy}: 403 for a
 *       client outside {@code argos.metrics.allow-cidrs}, 401 with {@code WWW-Authenticate: Bearer}
 *       for a missing or wrong token.
 *   <li>Every other path below the base path: 404, whatever Actuator exposes, so an endpoint added
 *       to the exposure list by configuration still stays unreachable until it is added here.
 *   <li>Paths outside the base path are left alone (on the management port nothing serves them).
 * </ul>
 *
 * The base path is {@code management.endpoints.web.base-path} ({@code /actuator}).
 */
public class ManagementAccessFilter extends OncePerRequestFilter {

  private final MetricsAccessPolicy policy;
  private final String basePath;

  /**
   * @param policy access rule of info and prometheus; null refuses them to everybody
   * @param basePath Actuator base path, e.g. {@code /actuator}
   */
  public ManagementAccessFilter(MetricsAccessPolicy policy, String basePath) {
    this.policy = policy;
    String base = basePath == null || basePath.isBlank() ? "" : basePath.strip();
    this.basePath = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** What the filter does with a path. */
  enum Access {
    /** Not an Actuator path: left alone (the management port has nothing else to serve). */
    PASS,
    OPEN,
    PROTECTED,
    NOT_FOUND
  }

  Access classify(String path) {
    String p =
        path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    if (!basePath.isEmpty() && !p.equals(basePath) && !p.startsWith(basePath + "/")) {
      return Access.PASS;
    }
    String health = basePath + "/health";
    if (p.equals(health) || p.startsWith(health + "/")) {
      return Access.OPEN;
    }
    if (p.equals(basePath + "/info") || p.equals(basePath + "/prometheus")) {
      return Access.PROTECTED;
    }
    return Access.NOT_FOUND;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    switch (classify(path)) {
      case PASS, OPEN -> chain.doFilter(request, response);
      case NOT_FOUND ->
          ErrorResponses.write(
              request, response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Not found");
      case PROTECTED -> {
        Decision decision =
            policy == null
                ? Decision.FORBIDDEN
                : policy.decide(
                    request.getRemoteAddr(), request.getHeader(HttpHeaders.AUTHORIZATION));
        switch (decision) {
          case ALLOW -> chain.doFilter(request, response);
          case FORBIDDEN ->
              ErrorResponses.write(
                  request,
                  response,
                  HttpServletResponse.SC_FORBIDDEN,
                  "forbidden",
                  "Client address not allowed (argos.metrics.allow-cidrs)");
          case UNAUTHENTICATED -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"argos-management\"");
            ErrorResponses.write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "unauthenticated",
                "Bearer token required");
          }
        }
      }
    }
  }
}
