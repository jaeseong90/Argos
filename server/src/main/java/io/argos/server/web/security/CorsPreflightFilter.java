/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Answers a CORS preflight to the REST API with the {@code ErrorBody} of 14 §2.6: 403 {@code
 * forbidden}. The API has one origin, the public URL (16 §7.6, 16 §9.9 {@code connect-src 'self'}),
 * so it grants no cross-origin access; without this filter Spring MVC refuses a preflight too, but
 * with a plain-text body outside the error contract. A preflight is not a session request and
 * passes neither authentication nor CSRF.
 */
@Component
public class CorsPreflightFilter implements ApiFilter {

  @Override
  public int position() {
    return FilterOrder.CORS_PREFLIGHT;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if ("OPTIONS".equals(request.getMethod())
        && request.getHeader("Origin") != null
        && request.getHeader("Access-Control-Request-Method") != null) {
      ErrorBodyWriter.write(request, response, ErrorCode.FORBIDDEN);
      return;
    }
    chain.doFilter(request, response);
  }
}
