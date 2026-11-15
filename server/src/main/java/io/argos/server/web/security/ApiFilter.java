/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A filter of the {@code /api/**} security chain (WO-24): rate limits, access-token authentication,
 * CSRF, the password-change gate. Declare an implementation as a bean (e.g. {@code @Component});
 * {@link SecurityConfig} picks up every bean of this type and runs them in ascending {@link
 * #position()} (one of the API chain positions of {@link FilterOrder}), once per request, inside
 * the Spring Security chain of {@code /api/**}. Nothing else needs to change when a filter is
 * added.
 *
 * <p>Deliberately not a {@code jakarta.servlet.Filter}: Spring Boot registers every {@code Filter}
 * bean as a servlet filter for all paths, which these must not be.
 *
 * <p>A filter that rejects a request writes the error with {@link
 * io.argos.server.api.error.ErrorBodyWriter} and does not call the chain; otherwise it calls {@code
 * chain.doFilter(request, response)} exactly once.
 */
public interface ApiFilter {

  /** Position in the {@code /api} chain, e.g. {@link FilterOrder#CSRF}; unique per filter. */
  int position();

  /** Filters one API request. */
  void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException;
}
