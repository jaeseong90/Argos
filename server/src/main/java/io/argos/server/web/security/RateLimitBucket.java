/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import java.util.Locale;

/**
 * The rate-limit buckets of the REST API (14 §2.7, 16 §7.13, SEC-56). Every bucket is a token
 * bucket in memory per node (Bucket4j, 16 §15 #12), keyed per client address or per user; a request
 * over the limit is answered 429 {@code rate_limited} with {@code Retry-After} (seconds), and every
 * limited response carries {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}. Rejections
 * are counted in {@code argos_ratelimit_rejections_total{scope}} (12 §9.4, ADR-0010 C-106 (8)) with
 * {@link #scope()} as the label.
 *
 * <p>Enforced since WO-24: {@link #AUTH}, {@link #CSP_REPORT} (per client address, {@link
 * IpRateLimitFilter}), {@link #API} (per session, {@link UserRateLimitFilter}) and {@link #HEAVY}
 * (per user, handlers annotated {@link RateLimited @RateLimited(HEAVY)}, {@link
 * RateLimitInterceptor}); since WO-28 also {@link #PRIVACY_DELETE} (per user). The other values
 * name the buckets of later work orders, which enforce them where their requests are handled;
 * {@link #enforcedByWebLayer()} is false for them.
 */
public enum RateLimitBucket {

  /**
   * Login, refresh and the setup token: {@code argos.ratelimit.login-per-ip-per-minute} (20) per
   * client address and minute.
   */
  AUTH(true),

  /**
   * CSP violation reports ({@code POST /api/v1/csp-report}, 16 §9.9): {@code
   * argos.ratelimit.csp-report-per-ip-per-minute} (60) per client address and minute.
   */
  CSP_REPORT(true),

  /**
   * Every API request: {@code argos.ratelimit.api-per-user-per-second} (50) per session and second
   * with a burst of {@code argos.ratelimit.api-burst-per-user} (100); requests without a session
   * share one bucket per client address.
   */
  API(true),

  /**
   * Heavy requests (text and ID search, transaction search, CSV export, traces over long ranges,
   * alert preview; 14 §2.7): {@code argos.ratelimit.export-per-user-per-minute} (10) per user and
   * minute, for handlers annotated {@code @RateLimited(HEAVY)} (WO-23 annotates them).
   */
  HEAVY(true),

  /** Command requests per instance and user (14 §2.7, SEC-24): enforced by WO-26. */
  COMMAND(false),

  /**
   * The privacy delete API ({@code POST /api/v1/admin/privacy/deletions}, 16 §7.13, ADR-0010 C-136
   * (2)): {@value RateLimiter#PRIVACY_DELETES_PER_HOUR} per user and hour (WO-28), for handlers
   * annotated {@code @RateLimited(PRIVACY_DELETE)}.
   */
  PRIVACY_DELETE(true),

  /** WebSocket subscription changes per connection (14 §2.7, 16 §7.13): enforced by WO-22. */
  WS_SUBSCRIBE(false);

  private final boolean enforcedByWebLayer;

  RateLimitBucket(boolean enforcedByWebLayer) {
    this.enforcedByWebLayer = enforcedByWebLayer;
  }

  /** The metric label: the lower-case name, e.g. {@code csp_report}. */
  public String scope() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether the web layer of WO-24 enforces this bucket. */
  public boolean enforcedByWebLayer() {
    return enforcedByWebLayer;
  }
}
