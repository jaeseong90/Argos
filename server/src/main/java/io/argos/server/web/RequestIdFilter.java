/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import io.argos.server.config.ServerBuild;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of the HTTP port (8080): gives every request an id and every response the server
 * version.
 *
 * <ul>
 *   <li>A new id ({@link RequestIds}, ULID layout) per request, generated here; an incoming {@code
 *       X-Request-Id} is not trusted. It is the MDC key {@value #MDC_KEY} while the request runs
 *       (the {@code request_id} field of every log line, 17 §11.3), the request attribute {@value
 *       #ATTRIBUTE} (error bodies, audit records: {@code request_id} of 14 §2.6 and 12 §8.1) and
 *       the response header {@value #HEADER}.
 *   <li>{@value #VERSION_HEADER} on every response (14 §2.1).
 * </ul>
 *
 * Both headers are set before the request is handled, so error responses carry them too.
 */
public class RequestIdFilter extends OncePerRequestFilter {

  /** MDC key of the request id (log field {@code request_id}). */
  public static final String MDC_KEY = "request_id";

  /** Request attribute holding the id. */
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

  /** Response header with the request id. */
  public static final String HEADER = "X-Request-Id";

  /** Response header with the server version (14 §2.1). */
  public static final String VERSION_HEADER = "X-Argos-Server-Version";

  private final RequestIds ids;
  private final ServerBuild build;

  public RequestIdFilter(RequestIds ids, ServerBuild build) {
    this.ids = ids;
    this.build = build;
  }

  /** The id of the current request, or null outside a request handled by this filter. */
  public static String currentId(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
  }

  /** Error dispatches (the error page of a failed request) keep the id of their request. */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = currentId(request);
    if (id == null) {
      id = ids.next();
      request.setAttribute(ATTRIBUTE, id);
    }
    if (!response.isCommitted()) {
      response.setHeader(HEADER, id);
      response.setHeader(VERSION_HEADER, build.version());
    }
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }
}
