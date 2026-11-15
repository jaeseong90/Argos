/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The general API limit of the {@code /api} chain ({@link FilterOrder#USER_RATE_LIMIT}, after
 * authentication and CSRF; 14 §2.7, 16 §7.13, SEC-56): {@link RateLimitBucket#API}, one bucket per
 * session ({@code sid}); requests without a session share one bucket per client address. Over the
 * limit: 429 {@code rate_limited} with {@code Retry-After}.
 *
 * <p>Every API response gets {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}; when a
 * more specific bucket already set them (the address buckets of {@link IpRateLimitFilter}) they
 * stay. Heavy handlers ({@code @RateLimited(HEAVY)}) are charged in addition by {@link
 * RateLimitInterceptor}, once the handler is known.
 */
@Component
public class UserRateLimitFilter implements ApiFilter {

  private static final Logger LOG = LoggerFactory.getLogger(UserRateLimitFilter.class);

  private final RateLimiter limiter;

  public UserRateLimitFilter(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public int position() {
    return FilterOrder.USER_RATE_LIMIT;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    RateLimiter.Decision decision = limiter.tryConsume(RateLimitBucket.API, key(request));
    IpRateLimitFilter.markApplied(request, RateLimitBucket.API);
    if (!decision.allowed()) {
      LOG.debug("Rate limit {} exceeded", RateLimitBucket.API.scope());
      ErrorBodyWriter.write(
          request, response, ErrorCode.RATE_LIMITED, null, List.of(), decision.headers());
      return;
    }
    if (response.getHeader(RateLimiter.LIMIT_HEADER) == null) {
      decision.writeHeaders(response);
    }
    chain.doFilter(request, response);
  }

  /** {@code sid:<session>} for an authenticated request, else {@code ip:<client address>}. */
  static String key(HttpServletRequest request) {
    Optional<ArgosPrincipal> principal = ArgosPrincipal.current();
    return principal
        .map(p -> "sid:" + p.sid())
        .orElseGet(() -> "ip:" + RateLimiter.clientAddress(request));
  }
}
