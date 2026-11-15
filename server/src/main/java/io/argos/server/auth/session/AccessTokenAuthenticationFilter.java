/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosAuthentication;
import io.argos.server.auth.AuthenticationFailure;
import io.argos.server.web.security.ApiFilter;
import io.argos.server.web.security.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Authenticates an API request from its {@value SessionCookies#ACCESS_COOKIE} cookie ({@link
 * FilterOrder#ACCESS_TOKEN_AUTHENTICATION}, 16 §7.3). The filter never rejects for the token
 * itself: {@code PUBLIC} endpoints (refresh, login, CSRF token) must work with an expired or
 * revoked access cookie, so it only records why the request stays anonymous ({@link
 * AuthenticationFailure}) and {@code RoleAuthorizationInterceptor} answers a protected endpoint
 * with that 401 code.
 *
 * <p>When the session record cannot be read the outcome is unknown, and the request fails closed:
 * 503 {@code storage_unavailable}.
 */
@Component
public class AccessTokenAuthenticationFilter implements ApiFilter {

  private static final Logger LOG = LoggerFactory.getLogger(AccessTokenAuthenticationFilter.class);

  private final SessionAuthenticator authenticator;

  public AccessTokenAuthenticationFilter(SessionAuthenticator authenticator) {
    this.authenticator = authenticator;
  }

  @Override
  public int position() {
    return FilterOrder.ACCESS_TOKEN_AUTHENTICATION;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    Optional<String> token = SessionCookies.read(request, SessionCookies.ACCESS_COOKIE);
    if (token.isPresent()) {
      SessionAuthenticator.Outcome outcome;
      try {
        outcome = authenticator.authenticate(token.get());
      } catch (DataAccessException e) {
        LOG.warn("The session record cannot be read: {}", e.getClass().getSimpleName());
        ErrorBodyWriter.write(request, response, ErrorCode.STORAGE_UNAVAILABLE);
        return;
      }
      if (outcome.authenticated()) {
        ArgosAuthentication.authenticate(outcome.principal());
      } else {
        AuthenticationFailure.record(request, outcome.failure());
      }
    }
    chain.doFilter(request, response);
  }
}
