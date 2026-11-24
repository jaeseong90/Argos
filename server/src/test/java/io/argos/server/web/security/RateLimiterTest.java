/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.ArgosProperties;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The token buckets of 14 §2.7 and 16 §7.13 on a manual clock. */
@Tag("req:SEC-56")
class RateLimiterTest {

  /** A clock the test moves. */
  private static final class ManualTime implements TimeMeter {
    private long nanos;

    void advance(Duration by) {
      nanos += by.toNanos();
    }

    @Override
    public long currentTimeNanos() {
      return nanos;
    }

    @Override
    public boolean isWallClockBased() {
      return false;
    }
  }

  private final ManualTime time = new ManualTime();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private RateLimiter defaults() {
    return new RateLimiter(
        RateLimiter.limits(new ArgosProperties.RateLimit(20, 50, 10, 100, 60)), registry, time);
  }

  private RateLimiter limiter(RateLimitBucket bucket, RateLimiter.Limit limit) {
    return new RateLimiter(Map.of(bucket, limit), registry, time);
  }

  private static int consumeUntilRefused(RateLimiter limiter, RateLimitBucket bucket, String key) {
    int allowed = 0;
    while (limiter.tryConsume(bucket, key).allowed()) {
      allowed++;
      assertThat(allowed).as("runaway bucket").isLessThan(1_000);
    }
    return allowed;
  }

  @Test
  @DisplayName(
      "T-SEC-56 the default limits: login 20 a minute, API burst 100 at 50 a second, heavy 10 a"
          + " minute, CSP reports 60 a minute")
  void defaultLimits() {
    RateLimiter limiter = defaults();
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.AUTH, "ip:a")).isEqualTo(20);
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.API, "sid:a")).isEqualTo(100);
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.HEAVY, "user:a")).isEqualTo(10);
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.CSP_REPORT, "ip:a")).isEqualTo(60);

    time.advance(Duration.ofSeconds(1));
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.API, "sid:a"))
        .as("a second gives back the 50 of the rate, not the burst")
        .isEqualTo(50);
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:a").allowed())
        .as("20 a minute: one token every 3 s, none after one second")
        .isFalse();
    time.advance(Duration.ofSeconds(2));
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:a").allowed()).isTrue();
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:a").allowed()).isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-56 a refused request has the limit, nothing remaining and Retry-After in whole seconds,"
          + " rounded up")
  void decisionHeaders() {
    RateLimiter limiter = defaults();
    for (int i = 0; i < 19; i++) {
      limiter.tryConsume(RateLimitBucket.AUTH, "ip:a");
    }
    RateLimiter.Decision last = limiter.tryConsume(RateLimitBucket.AUTH, "ip:a");
    assertThat(last.allowed()).isTrue();
    assertThat(last.headers())
        .containsExactly(
            Map.entry(RateLimiter.LIMIT_HEADER, "20"),
            Map.entry(RateLimiter.REMAINING_HEADER, "0"));

    RateLimiter.Decision refused = limiter.tryConsume(RateLimitBucket.AUTH, "ip:a");
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.headers())
        .containsExactly(
            Map.entry(RateLimiter.LIMIT_HEADER, "20"),
            Map.entry(RateLimiter.REMAINING_HEADER, "0"),
            Map.entry(RateLimiter.RETRY_AFTER_HEADER, "3"));

    time.advance(Duration.ofMillis(2_500));
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:a").retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("T-SEC-56 keys are independent")
  void keys() {
    RateLimiter limiter = defaults();
    assertThat(consumeUntilRefused(limiter, RateLimitBucket.AUTH, "ip:a")).isEqualTo(20);
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:b").allowed()).isTrue();
    assertThat(limiter.tryConsume(RateLimitBucket.CSP_REPORT, "ip:a").allowed())
        .as("buckets are independent")
        .isTrue();
  }

  @Test
  @DisplayName("T-SEC-56 argos_ratelimit_rejections_total counts refusals per scope")
  void metric() {
    RateLimiter limiter = defaults();
    consumeUntilRefused(limiter, RateLimitBucket.AUTH, "ip:a");
    limiter.tryConsume(RateLimitBucket.AUTH, "ip:a");
    consumeUntilRefused(limiter, RateLimitBucket.CSP_REPORT, "ip:a");
    assertThat(registry.get(RateLimiter.REJECTIONS_METER).tag("scope", "auth").counter().count())
        .isEqualTo(2);
    assertThat(
            registry.get(RateLimiter.REJECTIONS_METER).tag("scope", "csp_report").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(RateLimiter.REJECTIONS_METER).tag("scope", "api").counter().count())
        .isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-56 the bucket map is bounded: a flood of keys never keeps more than the limit")
  void boundedKeys() {
    RateLimiter limiter =
        limiter(RateLimitBucket.AUTH, new RateLimiter.Limit(5, 5, Duration.ofMinutes(1), 1_000));
    for (int i = 0; i < 20_000; i++) {
      limiter.tryConsume(RateLimitBucket.AUTH, "ip:" + i);
    }
    assertThat(limiter.trackedKeys(RateLimitBucket.AUTH)).isLessThanOrEqualTo(1_000);
  }

  @Test
  @DisplayName(
      "T-SEC-56 an idle key is forgotten only after a full refill, so expiry never loosens a limit")
  void idleExpiry() {
    assertThat(new RateLimiter.Limit(20, 20, Duration.ofMinutes(1), 10).idleExpiry())
        .isEqualTo(Duration.ofMinutes(2));
    assertThat(new RateLimiter.Limit(100, 50, Duration.ofSeconds(1), 10).idleExpiry())
        .isEqualTo(Duration.ofSeconds(4));
    assertThat(new RateLimiter.Limit(1, 1000, Duration.ofMillis(1), 10).idleExpiry())
        .isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("the buckets of later work orders exist, are not enforced here, and are refused")
  void bucketsOfLaterWorkOrders() {
    RateLimiter limiter = defaults();
    for (RateLimitBucket bucket :
        new RateLimitBucket[] {
          // PRIVACY_DELETE is enforced since WO-28 (5 per user and hour, C-136 (2)).
          RateLimitBucket.COMMAND, RateLimitBucket.WS_SUBSCRIBE
        }) {
      assertThat(bucket.enforcedByWebLayer()).isFalse();
      assertThat(limiter.enforces(bucket)).isFalse();
      assertThatThrownBy(() -> limiter.tryConsume(bucket, "x"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(limiter.trackedKeys(bucket)).isZero();
    }
    assertThat(RateLimitBucket.CSP_REPORT.scope()).isEqualTo("csp_report");
  }

  @Test
  @DisplayName("C-136 (2): the privacy deletion bucket allows 5 requests per user and hour")
  void privacyDeleteBucket() {
    RateLimiter limiter = defaults();
    assertThat(RateLimitBucket.PRIVACY_DELETE.enforcedByWebLayer()).isTrue();
    assertThat(limiter.limit(RateLimitBucket.PRIVACY_DELETE))
        .isEqualTo(new RateLimiter.Limit(5, 5, Duration.ofHours(1), RateLimiter.MAX_PRIVACY_KEYS));
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume(RateLimitBucket.PRIVACY_DELETE, "user:a").allowed()).isTrue();
    }
    RateLimiter.Decision sixth = limiter.tryConsume(RateLimitBucket.PRIVACY_DELETE, "user:a");
    assertThat(sixth.allowed()).isFalse();
    assertThat(sixth.retryAfterSeconds()).isBetween(1L, 720L);
    assertThat(limiter.tryConsume(RateLimitBucket.PRIVACY_DELETE, "user:b").allowed()).isTrue();
  }

  @Test
  @DisplayName("reset fills every bucket again; limits are validated")
  void resetAndValidation() {
    RateLimiter limiter = defaults();
    consumeUntilRefused(limiter, RateLimitBucket.AUTH, "ip:a");
    limiter.reset();
    assertThat(limiter.tryConsume(RateLimitBucket.AUTH, "ip:a").allowed()).isTrue();

    assertThatThrownBy(() -> new RateLimiter.Limit(0, 1, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateLimiter.Limit(1, 1, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TimeUnit.SECONDS.toNanos(1)).isEqualTo(1_000_000_000L);
  }

  @Test
  @DisplayName("the API bucket capacity is at least the rate, whatever the burst setting says")
  void burstBelowRate() {
    RateLimiter limiter =
        new RateLimiter(
            RateLimiter.limits(new ArgosProperties.RateLimit(20, 50, 10, 5, 60)), registry, time);
    assertThat(limiter.limit(RateLimitBucket.API).capacity()).isEqualTo(50);
  }
}
