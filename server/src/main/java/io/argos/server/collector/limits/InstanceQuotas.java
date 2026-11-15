/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.config.ArgosProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * Per-instance token buckets of the throughput quotas (12 §3.3, 16 §4.3, SEC-12): rate = the
 * configured quota, capacity = twice one quota period, a new bucket starts full.
 *
 * <p>DSN-12-04 / ADR-0010 C-130 (1): a batch is never refused for its quota. {@link #takePrefix}
 * grants the longest prefix of the batch's items that the bucket covers; the collector removes the
 * rest from the <b>end</b> of the batch and answers {@code ACK_PARTIAL_DROP} with the number
 * removed. One instance's bucket never affects another instance (T-SV-013).
 *
 * <p>Tokens taken for a batch that is then not written to the WAL (busy, failed or full WAL, an
 * internal error) are given back with {@link #giveBack}, once per such batch, so that a stalled WAL
 * and the agent's resends do not drain the bucket of data that was never stored (DSN-12-04, D-06;
 * WO-21 follow-up N-1).
 *
 * <p>Memory is bounded: at most {@value #MAX_BUCKETS} buckets, idle ones are evicted after {@link
 * #IDLE_EXPIRY} (an evicted bucket comes back full, which only matters for an instance silent for
 * that long). Thread-safe.
 */
public final class InstanceQuotas {

  /** Upper bound of buckets held (4 kinds × 25,000 instances). */
  static final long MAX_BUCKETS = 100_000;

  /** Buckets unused for this long are dropped. */
  static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

  private final ArgosProperties.Collector.Quota quota;
  private final LongSupplier nanoTime;
  private final Cache<Key, TokenBucket> buckets =
      Caffeine.newBuilder().maximumSize(MAX_BUCKETS).expireAfterAccess(IDLE_EXPIRY).build();

  /**
   * @param quota the configured quotas ({@code argos.collector.quota.*})
   * @param nanoTime the monotonic clock of the buckets ({@code System::nanoTime}; tests step it)
   */
  public InstanceQuotas(ArgosProperties.Collector.Quota quota, LongSupplier nanoTime) {
    this.quota = Objects.requireNonNull(quota, "quota");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /**
   * Takes tokens for the leading items of a batch: item {@code i} costs {@code size.applyAsInt(i)}
   * tokens; items are granted in order until one does not fit, the rest are not.
   *
   * @param count the number of items
   * @return how many leading items are granted ({@code 0..count})
   */
  public int takePrefix(int instanceId, QuotaKind kind, IntUnaryOperator size, int count) {
    if (count == 0) {
      return 0;
    }
    TokenBucket bucket =
        buckets.get(
            new Key(instanceId, kind),
            k ->
                new TokenBucket(
                    kind.ratePerSecond(quota), kind.capacity(quota), nanoTime.getAsLong()));
    return bucket.takePrefix(size, count, nanoTime.getAsLong());
  }

  /** {@link #takePrefix} for items of one token each. */
  public int take(int instanceId, QuotaKind kind, int count) {
    return takePrefix(instanceId, kind, i -> 1, count);
  }

  /**
   * Gives back {@code tokens} taken earlier for items that were then not stored: the bucket gains
   * them up to its capacity, without a refill step (its refill time is unchanged). A bucket that is
   * no longer held (evicted) is not created again: a new bucket starts full anyway. Zero, negative
   * and NaN amounts are ignored.
   */
  public void giveBack(int instanceId, QuotaKind kind, double tokens) {
    if (!(tokens > 0)) {
      return;
    }
    TokenBucket bucket = buckets.getIfPresent(new Key(instanceId, kind));
    if (bucket != null) {
      bucket.giveBack(tokens);
    }
  }

  private record Key(int instanceId, QuotaKind kind) {}

  /** One token bucket (16 §4.3). */
  static final class TokenBucket {
    private final double ratePerNano;
    private final double capacity;
    private double tokens;
    private long lastNanos;

    TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
      this.ratePerNano = ratePerSecond / 1e9;
      this.capacity = capacity;
      this.tokens = capacity;
      this.lastNanos = nowNanos;
    }

    synchronized int takePrefix(IntUnaryOperator size, int count, long nowNanos) {
      long elapsed = nowNanos - lastNanos;
      if (elapsed > 0) {
        tokens = Math.min(capacity, tokens + elapsed * ratePerNano);
        lastNanos = nowNanos;
      }
      double left = tokens;
      int granted = 0;
      while (granted < count) {
        int cost = Math.max(0, size.applyAsInt(granted));
        if (cost > left) {
          break;
        }
        left -= cost;
        granted++;
      }
      tokens = left;
      return granted;
    }

    synchronized void giveBack(double amount) {
      tokens = Math.min(capacity, tokens + amount);
    }

    /** The tokens held now, without a refill step (tests). */
    synchronized double tokens() {
      return tokens;
    }
  }

  /** The tokens of the bucket of ({@code instanceId}, {@code kind}), or -1 when none is held. */
  double tokensForTest(int instanceId, QuotaKind kind) {
    TokenBucket bucket = buckets.getIfPresent(new Key(instanceId, kind));
    return bucket == null ? -1 : bucket.tokens();
  }
}
