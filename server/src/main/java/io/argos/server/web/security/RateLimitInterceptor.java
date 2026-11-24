/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Charges the bucket of a handler annotated {@link RateLimited} (14 §2.7, 16 §7.13, SEC-56), after
 * the role check ({@code RoleAuthorizationInterceptor} runs first), so a refused request costs no
 * heavy token:
 *
 * <ul>
 *   <li>{@link RateLimitBucket#HEAVY} and {@link RateLimitBucket#PRIVACY_DELETE}: one bucket per
 *       user ({@code users.id}; a handler without a session, which these endpoints never are, per
 *       client address);
 *   <li>{@link RateLimitBucket#AUTH}, {@link RateLimitBucket#CSP_REPORT}: per client address,
 *       unless {@link IpRateLimitFilter} already charged that bucket for the request by its path;
 *   <li>buckets the web layer does not enforce: nothing (their work order does).
 * </ul>
 *
 * Over the limit: {@link ApiException} 429 {@code rate_limited} with {@code Retry-After}, {@code
 * X-RateLimit-Limit} and {@code X-RateLimit-Remaining}; otherwise the two limit headers of this
 * bucket replace those of the general bucket.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimiter limiter;

  public RateLimitInterceptor(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    Optional<RateLimitBucket> annotated = bucketOf(method);
    if (annotated.isEmpty()) {
      return true;
    }
    RateLimitBucket bucket = annotated.get();
    if (!bucket.enforcedByWebLayer()
        || !limiter.enforces(bucket)
        || IpRateLimitFilter.applied(request, bucket)) {
      return true;
    }
    String key =
        bucket == RateLimitBucket.HEAVY || bucket == RateLimitBucket.PRIVACY_DELETE
            ? ArgosPrincipal.current()
                .map(p -> "user:" + p.userId())
                .orElseGet(() -> "ip:" + RateLimiter.clientAddress(request))
            : "ip:" + RateLimiter.clientAddress(request);
    RateLimiter.Decision decision = limiter.tryConsume(bucket, key);
    IpRateLimitFilter.markApplied(request, bucket);
    if (!decision.allowed()) {
      ApiException error = new ApiException(ErrorCode.RATE_LIMITED);
      decision.headers().forEach(error::header);
      throw error;
    }
    decision.writeHeaders(response);
    return true;
  }

  /** The bucket of {@link RateLimited} on the method, else on its class. */
  public static Optional<RateLimitBucket> bucketOf(HandlerMethod handler) {
    RateLimited onMethod =
        AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RateLimited.class);
    if (onMethod != null) {
      return Optional.of(onMethod.value());
    }
    RateLimited onClass =
        AnnotatedElementUtils.findMergedAnnotation(
            ClassUtils.getUserClass(handler.getBeanType()), RateLimited.class);
    return onClass == null ? Optional.empty() : Optional.of(onClass.value());
  }
}
