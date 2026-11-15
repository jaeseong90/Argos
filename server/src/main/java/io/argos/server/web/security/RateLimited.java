/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts a REST handler into a rate-limit bucket besides the general per-session API bucket (14 §2.7,
 * 16 §7.13, SEC-56). A method annotation wins over the class one.
 *
 * <pre>{@code
 * @GetMapping("/api/v1/apps/{appId}/transactions/search")
 * @RequiresRole(Role.VIEWER)
 * @RateLimited(RateLimitBucket.HEAVY)
 * public Envelope<...> search(...) { ... }
 * }</pre>
 *
 * {@link RateLimitBucket#HEAVY} is keyed per user, {@link RateLimitBucket#AUTH} and {@link
 * RateLimitBucket#CSP_REPORT} per client address ({@link RateLimitInterceptor}); a bucket that the
 * web layer does not enforce ({@link RateLimitBucket#enforcedByWebLayer()} false) is only a marker
 * for the work order that enforces it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimited {

  /** The bucket. */
  RateLimitBucket value();
}
