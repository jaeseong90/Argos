/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.config.ArgosProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The token buckets of the REST API (14 §2.7, 16 §7.13, SEC-56): Bucket4j buckets in memory, one
 * per key and {@link RateLimitBucket}, refilled greedily (a bucket of 20 per minute regains one
 * token every 3 s). Single node, no external store (16 §15 #12).
 *
 * <p>Memory is bounded: each bucket kind keeps at most {@link Limit#maxKeys()} keys in a Caffeine
 * cache, and a key unused for longer than the bucket needs to refill completely is dropped (a
 * dropped bucket and a new one are both full, so expiry never loosens the limit; size eviction of
 * an active key under a flood of distinct keys does, which is inherent to keyed limits).
 *
 * <p>Rejections increment {@value #REJECTIONS_METER} with the tag {@code scope} ({@code
 * argos_ratelimit_rejections_total{scope}}, 12 §9.4, ADR-0010 C-106 (8)).
 */
@Component
public class RateLimiter {

  /** Micrometer name of the rejection counter ({@code argos_ratelimit_rejections_total}). */
  public static final String REJECTIONS_METER = "argos.ratelimit.rejections";

  /** Response header: the capacity of the bucket that limits the request (14 §2.7). */
  public static final String LIMIT_HEADER = "X-RateLimit-Limit";

  /** Response header: tokens left in that bucket after this request (14 §2.7). */
  public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

  /** Response header of a 429: seconds until a token is available (14 §2.7). */
  public static final String RETRY_AFTER_HEADER = "Retry-After";

  /** Keys per bucket kind: client addresses of login, refresh and setup. */
  static final int MAX_AUTH_KEYS = 20_000;

  /** Keys per bucket kind: client addresses sending CSP reports. */
  static final int MAX_CSP_KEYS = 10_000;

  /** Keys per bucket kind: sessions and addresses of anonymous API requests. */
  static final int MAX_API_KEYS = 50_000;

  /** Keys per bucket kind: users of heavy requests. */
  static final int MAX_HEAVY_KEYS = 10_000;

  private final Map<RateLimitBucket, Limit> limits;
  private final Map<RateLimitBucket, Cache<String, Bucket>> buckets =
      new EnumMap<>(RateLimitBucket.class);
  private final Map<RateLimitBucket, Counter> rejections = new EnumMap<>(RateLimitBucket.class);
  private final TimeMeter time;

  @Autowired
  public RateLimiter(ArgosProperties properties, ObjectProvider<MeterRegistry> registry) {
    this(
        limits(properties.ratelimit()),
        registry.getIfAvailable(SimpleMeterRegistry::new),
        TimeMeter.SYSTEM_NANOTIME);
  }

  /**
   * A limiter with explicit limits and clock (tests).
   *
   * @param limits the enforced buckets and their limits
   * @param registry meter registry of the rejection counters
   * @param time time source of the buckets
   */
  public RateLimiter(Map<RateLimitBucket, Limit> limits, MeterRegistry registry, TimeMeter time) {
    this.limits = new EnumMap<>(limits);
    this.time = Objects.requireNonNull(time, "time");
    this.limits.forEach(
        (bucket, limit) -> {
          buckets.put(
              bucket,
              Caffeine.newBuilder()
                  .maximumSize(limit.maxKeys())
                  .expireAfterAccess(limit.idleExpiry())
                  .build());
          rejections.put(
              bucket,
              Counter.builder(REJECTIONS_METER)
                  .tag("scope", bucket.scope())
                  .description("API requests rejected by a rate limit (429)")
                  .register(registry));
        });
  }

  /**
   * The limits of the enforced buckets from {@code argos.ratelimit.*}: login, refresh and setup per
   * address and minute; CSP reports per address and minute; the general API per session and second
   * with its burst capacity; heavy requests per user and minute.
   */
  public static Map<RateLimitBucket, Limit> limits(ArgosProperties.RateLimit settings) {
    Map<RateLimitBucket, Limit> limits = new LinkedHashMap<>();
    int perMinuteLogin = settings.loginPerIpPerMinute();
    limits.put(
        RateLimitBucket.AUTH,
        new Limit(perMinuteLogin, perMinuteLogin, Duration.ofMinutes(1), MAX_AUTH_KEYS));
    int perMinuteCsp = settings.cspReportPerIpPerMinute();
    limits.put(
        RateLimitBucket.CSP_REPORT,
        new Limit(perMinuteCsp, perMinuteCsp, Duration.ofMinutes(1), MAX_CSP_KEYS));
    int perSecond = settings.apiPerUserPerSecond();
    limits.put(
        RateLimitBucket.API,
        new Limit(
            Math.max(perSecond, settings.apiBurstPerUser()),
            perSecond,
            Duration.ofSeconds(1),
            MAX_API_KEYS));
    int perMinuteHeavy = settings.exportPerUserPerMinute();
    limits.put(
        RateLimitBucket.HEAVY,
        new Limit(perMinuteHeavy, perMinuteHeavy, Duration.ofMinutes(1), MAX_HEAVY_KEYS));
    return limits;
  }

  /** Whether {@code bucket} is enforced by this limiter. */
  public boolean enforces(RateLimitBucket bucket) {
    return limits.containsKey(bucket);
  }

  /** The limit of an enforced bucket. */
  public Limit limit(RateLimitBucket bucket) {
    Limit limit = limits.get(bucket);
    if (limit == null) {
      throw new IllegalArgumentException("bucket not enforced by the web layer: " + bucket);
    }
    return limit;
  }

  /**
   * Takes one token of {@code key}'s bucket.
   *
   * @param bucket an enforced bucket
   * @param key the client address or user of the bucket, e.g. {@code ip:10.0.0.1}
   * @return whether the request may proceed, with the header values
   * @throws IllegalArgumentException for a bucket this limiter does not enforce
   */
  public Decision tryConsume(RateLimitBucket bucket, String key) {
    Limit limit = limit(bucket);
    Bucket tokens = buckets.get(bucket).get(key, k -> newBucket(limit));
    ConsumptionProbe probe = tokens.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return new Decision(bucket, true, limit.capacity(), probe.getRemainingTokens(), 0);
    }
    rejections.get(bucket).increment();
    long nanos = probe.getNanosToWaitForRefill();
    long seconds = Math.max(1, (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / 1_000_000_000L);
    return new Decision(bucket, false, limit.capacity(), 0, seconds);
  }

  /** Number of keys currently tracked for {@code bucket} (bounded by {@link Limit#maxKeys()}). */
  public long trackedKeys(RateLimitBucket bucket) {
    Cache<String, Bucket> cache = buckets.get(bucket);
    if (cache == null) {
      return 0;
    }
    cache.cleanUp();
    return cache.estimatedSize();
  }

  /** Forgets every bucket: all keys start full again (tests, operator diagnostics). */
  public void reset() {
    buckets.values().forEach(Cache::invalidateAll);
  }

  private Bucket newBucket(Limit limit) {
    return Bucket.builder()
        .addLimit(
            l -> l.capacity(limit.capacity()).refillGreedy(limit.refillTokens(), limit.period()))
        .withCustomTimePrecision(time)
        .build();
  }

  /**
   * The client address of a request as the key of an address bucket: {@code
   * request.getRemoteAddr()} (the socket peer, or the client named by a trusted reverse proxy when
   * {@code server.forward-headers-strategy} is configured), without IPv6 brackets or zone.
   */
  public static String clientAddress(HttpServletRequest request) {
    String address = request.getRemoteAddr();
    if (address == null || address.isBlank()) {
      return "unknown";
    }
    String value = address.strip();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    int zone = value.indexOf('%');
    return zone < 0 ? value : value.substring(0, zone);
  }

  /**
   * The limit of one bucket kind.
   *
   * @param capacity tokens of a full bucket (the burst); {@code X-RateLimit-Limit}
   * @param refillTokens tokens added per {@code period}, spread evenly over it
   * @param period refill period
   * @param maxKeys keys kept at most
   */
  public record Limit(long capacity, long refillTokens, Duration period, int maxKeys) {

    public Limit {
      if (capacity < 1 || refillTokens < 1 || maxKeys < 1) {
        throw new IllegalArgumentException("capacity, refill tokens and max keys must be positive");
      }
      Objects.requireNonNull(period, "period");
      if (period.isNegative() || period.isZero()) {
        throw new IllegalArgumentException("period must be positive");
      }
    }

    /**
     * How long an unused key is kept: twice the time an empty bucket needs to become full (at least
     * one second), so that dropping it is indistinguishable from keeping it.
     */
    public Duration idleExpiry() {
      long periods = (capacity + refillTokens - 1) / refillTokens;
      Duration full = period.multipliedBy(periods);
      Duration twice = full.multipliedBy(2);
      return twice.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : twice;
    }
  }

  /**
   * The outcome of one {@link #tryConsume}.
   *
   * @param bucket the bucket
   * @param allowed whether the request may proceed
   * @param limit capacity of the bucket ({@code X-RateLimit-Limit})
   * @param remaining tokens left ({@code X-RateLimit-Remaining})
   * @param retryAfterSeconds seconds until the next token when not allowed ({@code Retry-After}),
   *     else 0
   */
  public record Decision(
      RateLimitBucket bucket, boolean allowed, long limit, long remaining, long retryAfterSeconds) {

    /** The response headers of this decision ({@code Retry-After} only when not allowed). */
    public Map<String, String> headers() {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put(LIMIT_HEADER, Long.toString(limit));
      headers.put(REMAINING_HEADER, Long.toString(remaining));
      if (!allowed) {
        headers.put(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
      }
      return headers;
    }

    /** Sets {@link #headers()} on {@code response}. */
    public void writeHeaders(HttpServletResponse response) {
      headers().forEach(response::setHeader);
    }
  }
}
