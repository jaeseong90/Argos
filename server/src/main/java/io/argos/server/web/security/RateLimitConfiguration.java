/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds {@link RateLimitInterceptor} to Spring MVC, right after the role check ({@code
 * RoleAuthorizationInterceptor} has {@link Ordered#HIGHEST_PRECEDENCE}), so heavy tokens are
 * charged only for requests the role allows. The filters of the {@code /api} chain ({@link
 * IpRateLimitFilter}, {@link UserRateLimitFilter}) are {@link ApiFilter} beans and need no
 * registration.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration implements WebMvcConfigurer {

  /** Order of {@link RateLimitInterceptor}: after the role check. */
  public static final int INTERCEPTOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  private final RateLimiter limiter;

  public RateLimitConfiguration(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RateLimitInterceptor(limiter)).order(INTERCEPTOR_ORDER);
  }
}
