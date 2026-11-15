/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.web.WebConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;

/**
 * The order of the request filters of the HTTP port (8080), in one place. A request passes them in
 * this total order:
 *
 * <pre>
 *   RequestId                                          servlet filter of WO-20 (WebConfiguration)
 *   &lt; SecurityHeaders (early pass)                     servlet filter, every path (16 §9.8)
 *   &lt; UrlLimit &lt; BodyLimit                              servlet filters of WO-20 (WebConfiguration)
 *   &lt; SecurityHeaders                                  servlet filter, every path (16 §9.8)
 *   &lt; Spring Security FilterChainProxy                 (SecurityConfig), and inside its /api chain:
 *       IpRateLimit &lt; CorsPreflight &lt; AccessTokenAuthentication &lt; Csrf &lt; UserRateLimit
 *       &lt; MustChangePassword
 * </pre>
 *
 * <p>Two kinds of positions:
 *
 * <ul>
 *   <li><b>Servlet filter orders</b> ({@link #REQUEST_ID} to {@link #SPRING_SECURITY}): the order
 *       of a {@code FilterRegistrationBean}. They run for every path of the port, before Spring
 *       Security. {@code SecurityHeadersFilter} registers at {@link #SECURITY_HEADERS} so that
 *       every response, errors and static files included, carries the headers.
 *   <li><b>API chain positions</b> ({@link #IP_RATE_LIMIT} to {@link #MUST_CHANGE_PASSWORD}): the
 *       {@link ApiFilter#position()} of a filter of the {@code /api/**} chain. {@link
 *       SecurityConfig} runs every {@link ApiFilter} bean in ascending position, after Spring's
 *       security context is set up and before its anonymous filter, so the access-token filter
 *       decides whether the request is authenticated. Positions must be unique (checked at start).
 * </ul>
 */
public final class FilterOrder {

  /** {@code RequestIdFilter} (WO-20): first, every later filter logs with the request id. */
  public static final int REQUEST_ID = WebConfiguration.REQUEST_ID_ORDER;

  /**
   * {@code SecurityHeadersFilter.Early}: right after the request id, because the URL and body limit
   * filters answer without calling the chain and their 400 responses carry the headers too.
   */
  public static final int SECURITY_HEADERS_EARLY = REQUEST_ID + 1;

  /** {@code RequestUrlLimitFilter} (WO-20, 14 §7.2). */
  public static final int URL_LIMIT = WebConfiguration.URL_LIMIT_ORDER;

  /** {@code RequestBodyLimitFilter} (WO-20, 14 §7.2). */
  public static final int BODY_LIMIT = WebConfiguration.BODY_LIMIT_ORDER;

  /** {@code SecurityHeadersFilter} (16 §9.8, SEC-45): a servlet filter for every path. */
  public static final int SECURITY_HEADERS = Ordered.HIGHEST_PRECEDENCE + 20;

  /**
   * Spring Security's {@code FilterChainProxy} ({@code spring.security.filter.order}, Spring Boot's
   * default): after every Argos servlet filter above.
   */
  public static final int SPRING_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER;

  /** API chain: per-address limits of login, setup, refresh and CSP reports (14 §2.7, 16 §7.13). */
  public static final int IP_RATE_LIMIT = 100;

  /**
   * API chain: CORS preflights get the 403 {@code ErrorBody}: after the address limit, before
   * authentication (a preflight is not a session request).
   */
  public static final int CORS_PREFLIGHT = 110;

  /** API chain: access JWT and session record check, sets the {@code ArgosPrincipal} (16 §7.3). */
  public static final int ACCESS_TOKEN_AUTHENTICATION = 200;

  /** API chain: CSRF double submit, Origin and {@code Sec-Fetch-Site} (16 §7.6). */
  public static final int CSRF = 300;

  /** API chain: per-user limits (general API, {@code @RateLimited} handlers) (14 §2.7). */
  public static final int USER_RATE_LIMIT = 400;

  /** API chain: 403 {@code password_change_required} while the password must change (16 §7.9). */
  public static final int MUST_CHANGE_PASSWORD = 500;

  private FilterOrder() {}
}
