/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.web.security.ApiFilter;
import io.argos.server.web.security.FilterOrder;
import io.argos.server.web.security.IpRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CSRF protection of the REST API ({@link FilterOrder#CSRF}, 16 §7.6, 14 §2.2, SEC-43, DSN-16-11).
 * POST, PUT, PATCH and DELETE need all of:
 *
 * <ol>
 *   <li>{@code Sec-Fetch-Site} is not {@code cross-site};
 *   <li>{@code Origin}, or {@code Referer} when {@code Origin} is absent, is the origin of {@code
 *       argos.web.public-url} ({@link PublicOrigin}); a request with neither is refused;
 *   <li>the {@value SessionCookies#CSRF_HEADER} header equals the {@value
 *       SessionCookies#CSRF_COOKIE} cookie (double submit, compared in constant time), except on
 *       login and setup ({@link #TOKEN_EXEMPT}): their first request has no cookie yet, so they
 *       pass rules 1 and 2 only.
 * </ol>
 *
 * Failure: 403 {@code csrf_invalid}. Paths are compared as received, so a differently encoded path
 * never gains an exemption.
 *
 * <p>Outside this filter: {@value io.argos.server.web.security.IpRateLimitFilter#CSP_REPORT_PATH}
 * ({@link #NOT_A_SESSION_REQUEST}): browsers send CSP reports without custom headers, the endpoint
 * acts on nothing but a counter and is limited by size and rate (16 §9.9). A later endpoint that
 * authenticates by something other than the session cookies ({@code POST /events} with an agent
 * token, 14 §2.2, WO-23/WO-26) is added to that set by its work order.
 */
@Component
public class CsrfFilter implements ApiFilter {

  /** Login (14 §4.1): Origin check only (16 §7.6). */
  public static final String LOGIN_PATH = IpRateLimitFilter.LOGIN_PATH;

  /** Creation of the first administrator (14 §4.1): Origin check only. */
  public static final String SETUP_ADMIN_PATH = IpRateLimitFilter.SETUP_PREFIX + "admin";

  /** Paths that pass the Origin check without a token. */
  static final Set<String> TOKEN_EXEMPT = Set.of(LOGIN_PATH, SETUP_ADMIN_PATH);

  /** Paths that no browser session stands behind. */
  static final Set<String> NOT_A_SESSION_REQUEST = Set.of(IpRateLimitFilter.CSP_REPORT_PATH);

  private static final Logger LOG = LoggerFactory.getLogger(CsrfFilter.class);
  private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

  private final PublicOrigin origin;

  public CsrfFilter(PublicOrigin origin) {
    this.origin = origin;
  }

  @Override
  public int position() {
    return FilterOrder.CSRF;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = path(request);
    if (UNSAFE.contains(request.getMethod()) && !NOT_A_SESSION_REQUEST.contains(path)) {
      Optional<String> refusal = refusal(request, path);
      if (refusal.isPresent()) {
        LOG.debug("CSRF check failed: {}", refusal.get());
        ErrorBodyWriter.write(request, response, ErrorCode.CSRF_INVALID);
        return;
      }
    }
    chain.doFilter(request, response);
  }

  /** Why a state-changing request is refused: a fixed reason name, never request data. */
  private Optional<String> refusal(HttpServletRequest request, String path) {
    String site = request.getHeader(SEC_FETCH_SITE);
    if (site != null && "cross-site".equals(site.strip().toLowerCase(Locale.ROOT))) {
      return Optional.of("cross_site");
    }
    String originHeader = request.getHeader("Origin");
    boolean sameOrigin =
        originHeader != null
            ? origin.matchesOrigin(originHeader)
            : origin.matchesUrl(request.getHeader("Referer"));
    if (!sameOrigin) {
      return Optional.of("origin");
    }
    if (TOKEN_EXEMPT.contains(path)) {
      return Optional.empty();
    }
    Optional<String> cookie = SessionCookies.read(request, SessionCookies.CSRF_COOKIE);
    if (cookie.isEmpty() || !SecureTokens.looksLikeToken(cookie.get())) {
      return Optional.of("no_cookie");
    }
    if (!SecureTokens.constantTimeEquals(
        cookie.get(), request.getHeader(SessionCookies.CSRF_HEADER))) {
      return Optional.of("token_mismatch");
    }
    return Optional.empty();
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context != null && !context.isEmpty() && uri.startsWith(context)
        ? uri.substring(context.length())
        : uri;
  }
}
