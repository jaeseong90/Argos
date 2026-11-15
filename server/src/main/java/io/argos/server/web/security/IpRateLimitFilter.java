/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The per-address limits of the {@code /api} chain ({@link FilterOrder#IP_RATE_LIMIT}, before
 * authentication; 14 §2.7, 16 §7.13, 16 §9.9, SEC-56), by path:
 *
 * <ul>
 *   <li>{@link RateLimitBucket#AUTH}: {@value #LOGIN_PATH}, {@value #REFRESH_PATH} and every
 *       request under {@value #SETUP_PREFIX} except reads (the setup token, 14 §4.1);
 *   <li>{@link RateLimitBucket#CSP_REPORT}: {@value #CSP_REPORT_PATH}.
 * </ul>
 *
 * The paths are the contract of 14 §4.1 and 16 §9.9, so the limit holds whatever handler serves
 * them, before any password or token is checked. Over the limit: 429 {@code rate_limited} with
 * {@code Retry-After}; every limited response carries {@code X-RateLimit-Limit} and {@code
 * X-RateLimit-Remaining}.
 */
@Component
public class IpRateLimitFilter implements ApiFilter {

  /** Login (14 §4.1). */
  public static final String LOGIN_PATH = "/api/v1/auth/login";

  /** Refresh-token rotation (14 §4.1). */
  public static final String REFRESH_PATH = "/api/v1/auth/refresh";

  /** Setup endpoints (14 §4.1): everything but reads is limited ({@code POST .../admin}). */
  public static final String SETUP_PREFIX = "/api/v1/setup/";

  /** CSP violation reports (16 §9.9). */
  public static final String CSP_REPORT_PATH = "/api/v1/csp-report";

  /**
   * Request attribute: the {@code Set<RateLimitBucket>} already charged for this request, so that
   * {@link RateLimitInterceptor} does not charge an annotated handler twice.
   */
  public static final String APPLIED_ATTRIBUTE = IpRateLimitFilter.class.getName() + ".applied";

  private static final Logger LOG = LoggerFactory.getLogger(IpRateLimitFilter.class);
  private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

  private final RateLimiter limiter;

  public IpRateLimitFilter(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public int position() {
    return FilterOrder.IP_RATE_LIMIT;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    RateLimitBucket bucket = bucketOf(request);
    if (bucket == null) {
      chain.doFilter(request, response);
      return;
    }
    RateLimiter.Decision decision =
        limiter.tryConsume(bucket, "ip:" + RateLimiter.clientAddress(request));
    markApplied(request, bucket);
    if (!decision.allowed()) {
      LOG.debug("Rate limit {} exceeded", bucket.scope());
      ErrorBodyWriter.write(
          request, response, ErrorCode.RATE_LIMITED, null, List.of(), decision.headers());
      return;
    }
    decision.writeHeaders(response);
    chain.doFilter(request, response);
  }

  /** The address bucket of a request, or null when none applies. */
  static RateLimitBucket bucketOf(HttpServletRequest request) {
    String path = RequestPaths.of(request);
    if (path.equals(LOGIN_PATH) || path.equals(REFRESH_PATH)) {
      return RateLimitBucket.AUTH;
    }
    if (path.startsWith(SETUP_PREFIX) && !READS.contains(request.getMethod())) {
      return RateLimitBucket.AUTH;
    }
    if (path.equals(CSP_REPORT_PATH)) {
      return RateLimitBucket.CSP_REPORT;
    }
    return null;
  }

  /** Records that {@code bucket} was charged for {@code request}. */
  @SuppressWarnings("unchecked")
  static void markApplied(HttpServletRequest request, RateLimitBucket bucket) {
    Object current = request.getAttribute(APPLIED_ATTRIBUTE);
    Set<RateLimitBucket> applied =
        current instanceof Set<?> set
            ? (Set<RateLimitBucket>) set
            : EnumSet.noneOf(RateLimitBucket.class);
    applied.add(bucket);
    request.setAttribute(APPLIED_ATTRIBUTE, applied);
  }

  /** Whether {@code bucket} was already charged for {@code request}. */
  static boolean applied(HttpServletRequest request, RateLimitBucket bucket) {
    return request.getAttribute(APPLIED_ATTRIBUTE) instanceof Set<?> set && set.contains(bucket);
  }
}
