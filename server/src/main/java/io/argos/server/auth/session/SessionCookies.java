/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.auth.Sensitive;
import io.argos.server.config.ArgosProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The three cookies of a UI session, their names and paths in one place (16 §7.1, 14 §2.2; WO-22
 * and WO-30 reuse the constants):
 *
 * <ul>
 *   <li>{@value #ACCESS_COOKIE}: the access JWT. {@code HttpOnly; Secure; SameSite=Strict; Path=/}
 *       (ADR-0010 C-107: the WebSocket path {@code /ws} must receive it); {@code Max-Age} = {@code
 *       argos.auth.access-ttl} (900 s).
 *   <li>{@value #REFRESH_COOKIE}: the opaque refresh token. {@code HttpOnly; Secure;
 *       SameSite=Strict; Path=}{@value #REFRESH_PATH}; {@code Max-Age} 86400 s (ADR-0010 C-106 (3);
 *       longer only when {@code auth.refresh-idle-ttl} or a kiosk session's idle lifetime is
 *       configured longer, so the cookie never ends before the session's idle expiry).
 *   <li>{@value #CSRF_COOKIE}: the CSRF token, readable by the UI script (not {@code HttpOnly}).
 *       {@code Secure; SameSite=Strict; Path=/}, a browser-session cookie.
 * </ul>
 *
 * {@code Secure} is always set unless {@code argos.security.cookie.secure=false} (non-production,
 * {@link CookieSecurityCheck} warns at every start). The token values never appear in a log or a
 * response body (the CSRF token excepted: {@code GET /auth/csrf} returns it, 14 §4.1).
 */
@Component
public class SessionCookies {

  /** Access-token cookie. */
  public static final String ACCESS_COOKIE = "argos_at";

  /** Refresh-token cookie. */
  public static final String REFRESH_COOKIE = "argos_rt";

  /** CSRF cookie. */
  public static final String CSRF_COOKIE = "argos_csrf";

  /** The header that repeats the CSRF cookie on state-changing requests (16 §7.6). */
  public static final String CSRF_HEADER = "X-Argos-CSRF";

  /** Path of the access cookie (ADR-0010 C-107). */
  public static final String ACCESS_PATH = "/";

  /** Path of the refresh cookie: only the refresh endpoint ever receives it. */
  public static final String REFRESH_PATH = "/api/v1/auth/refresh";

  /** Path of the CSRF cookie. */
  public static final String CSRF_PATH = "/";

  /** {@code Max-Age} of the refresh cookie (ADR-0010 C-106 (3)). */
  public static final Duration REFRESH_MAX_AGE = Duration.ofDays(1);

  private static final String SAME_SITE = "Strict";

  private final boolean secure;
  private final Duration accessMaxAge;

  public SessionCookies(ArgosProperties properties) {
    this.secure = properties.security().cookie().secure();
    this.accessMaxAge = properties.auth().accessTtl();
  }

  /** Sets {@value #ACCESS_COOKIE}. */
  public void setAccess(HttpServletResponse response, @Sensitive String accessToken) {
    add(response, base(ACCESS_COOKIE, accessToken, ACCESS_PATH, true).maxAge(accessMaxAge));
  }

  /**
   * Sets {@value #REFRESH_COOKIE}.
   *
   * @param idleLifetime the idle lifetime of the session: the cookie lives at least {@link
   *     #REFRESH_MAX_AGE} and at least this long
   */
  public void setRefresh(
      HttpServletResponse response, @Sensitive String refreshToken, Duration idleLifetime) {
    Duration maxAge = idleLifetime.compareTo(REFRESH_MAX_AGE) > 0 ? idleLifetime : REFRESH_MAX_AGE;
    add(response, base(REFRESH_COOKIE, refreshToken, REFRESH_PATH, true).maxAge(maxAge));
  }

  /** Sets {@value #CSRF_COOKIE}. */
  public void setCsrf(HttpServletResponse response, @Sensitive String csrfToken) {
    add(response, base(CSRF_COOKIE, csrfToken, CSRF_PATH, false));
  }

  /** Deletes the access and refresh cookies (a failed refresh). */
  public void clearSession(HttpServletResponse response) {
    add(response, base(ACCESS_COOKIE, "", ACCESS_PATH, true).maxAge(Duration.ZERO));
    add(response, base(REFRESH_COOKIE, "", REFRESH_PATH, true).maxAge(Duration.ZERO));
  }

  /** Deletes all three cookies (logout). */
  public void clearAll(HttpServletResponse response) {
    clearSession(response);
    add(response, base(CSRF_COOKIE, "", CSRF_PATH, false).maxAge(Duration.ZERO));
  }

  /** The non-empty value of a request cookie. */
  public static Optional<String> read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
        return Optional.of(cookie.getValue());
      }
    }
    return Optional.empty();
  }

  private ResponseCookie.ResponseCookieBuilder base(
      String name, String value, String path, boolean httpOnly) {
    return ResponseCookie.from(name, value)
        .path(path)
        .secure(secure)
        .httpOnly(httpOnly)
        .sameSite(SAME_SITE);
  }

  private static void add(HttpServletResponse response, ResponseCookie.ResponseCookieBuilder c) {
    response.addHeader(HttpHeaders.SET_COOKIE, c.build().toString());
  }
}
