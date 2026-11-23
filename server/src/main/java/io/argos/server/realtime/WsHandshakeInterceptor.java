/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.session.PublicOrigin;
import io.argos.server.auth.session.SessionAuthenticator;
import io.argos.server.auth.session.SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * The authentication of the {@code /ws} handshake (14 §5.1, 16 §7.7, SEC-44), before any upgrade:
 *
 * <ol>
 *   <li>the {@code Origin} header must be the public URL's origin ({@code argos.web.public-url}):
 *       otherwise 403 {@code forbidden} (cross-site WebSocket hijacking);
 *   <li>the {@code argos_at} cookie (ADR-0010 C-107: {@code Path=/}) must authenticate a valid
 *       session ({@link SessionAuthenticator}, the same check as every API request): otherwise 401
 *       with the API's code ({@code unauthenticated}, {@code session_expired}, {@code
 *       session_revoked});
 *   <li>a session that must change its password first gets 403 {@code password_change_required}, as
 *       on the API.
 * </ol>
 *
 * The refusals carry the {@code ErrorBody} of 14 §2.6 and the security headers of every response.
 * There is no token in the query string (SEC-44), and the cookie value is never logged.
 */
final class WsHandshakeInterceptor implements HandshakeInterceptor {

  /** Handshake attribute of the authenticated {@link ArgosPrincipal}. */
  static final String PRINCIPAL = "argos.ws.principal";

  private static final Logger LOG = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

  private final PublicOrigin origin;
  private final SessionAuthenticator authenticator;

  WsHandshakeInterceptor(PublicOrigin origin, SessionAuthenticator authenticator) {
    this.origin = Objects.requireNonNull(origin, "origin");
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes)
      throws IOException {
    if (!(request instanceof ServletServerHttpRequest servletRequest)
        || !(response instanceof ServletServerHttpResponse servletResponse)) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    HttpServletRequest req = servletRequest.getServletRequest();
    HttpServletResponse res = servletResponse.getServletResponse();
    if (!origin.matchesOrigin(req.getHeader("Origin"))) {
      refuse(req, res, ErrorCode.FORBIDDEN, "origin");
      return false;
    }
    SessionAuthenticator.Outcome outcome = authenticate(req);
    if (!outcome.authenticated()) {
      refuse(req, res, outcome.failure(), "session");
      return false;
    }
    ArgosPrincipal principal = outcome.principal();
    if (principal.mustChangePassword()) {
      refuse(req, res, ErrorCode.PASSWORD_CHANGE_REQUIRED, "password change");
      return false;
    }
    attributes.put(PRINCIPAL, principal);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
    // Nothing: the connection registers itself when it is established.
  }

  /** The session of the access cookie; no logging here (the token passes through). */
  private SessionAuthenticator.Outcome authenticate(HttpServletRequest request) {
    return SessionCookies.read(request, SessionCookies.ACCESS_COOKIE)
        .map(authenticator::authenticate)
        .orElseGet(() -> new SessionAuthenticator.Outcome(null, ErrorCode.UNAUTHENTICATED));
  }

  private static void refuse(
      HttpServletRequest request, HttpServletResponse response, ErrorCode code, String check)
      throws IOException {
    LOG.debug("WebSocket handshake refused ({}): {}", check, code.code());
    ErrorBodyWriter.write(request, response, code);
  }
}
