/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;

/**
 * An exact sliding-window event counter per key with a fixed threshold: "at most {@code limit}
 * events within {@code window}" (16 §2.5: authentication failures per address per minute, new
 * sessions per token per minute). Each key keeps the times of its last {@code limit} events in a
 * ring, so the threshold is reached exactly when the oldest of the last {@code limit} events lies
 * within the window.
 *
 * <p>Bounded memory: {@code limit} longs per key, at most {@code maxKeys} keys, a key idle for
 * {@code idleExpiry} is dropped. Times are milliseconds of the caller's clock (an injectable {@code
 * Clock} in the collector). Thread-safe.
 *
 * @param <K> the key type (an address, a token id)
 */
public final class SlidingWindowCounter<K> {

  private final int limit;
  private final long windowMs;
  private final Cache<K, long[]> rings;

  /**
   * @param limit events allowed within the window (at least 1)
   * @param window the window length
   * @param maxKeys upper bound of keys held
   * @param idleExpiry keys without events for this long are dropped (at least the window)
   */
  public SlidingWindowCounter(int limit, Duration window, long maxKeys, Duration idleExpiry) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit < 1: " + limit);
    }
    this.limit = limit;
    this.windowMs = Objects.requireNonNull(window, "window").toMillis();
    this.rings =
        Caffeine.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterAccess(idleExpiry.compareTo(window) < 0 ? window : idleExpiry)
            .build();
  }

  /**
   * Records an event of {@code key} at {@code nowMs}.
   *
   * @return whether {@code key} now has {@code limit} events within the window
   */
  public boolean record(K key, long nowMs) {
    long[] ring = rings.get(key, k -> newRing());
    synchronized (ring) {
      // ring[0] = next slot, ring[1] = events stored, ring[2..] = times
      int next = (int) ring[0];
      ring[2 + next] = nowMs;
      ring[0] = (next + 1L) % limit;
      ring[1] = Math.min(limit, ring[1] + 1);
      return full(ring, nowMs);
    }
  }

  /** Whether {@code key} has {@code limit} events within the window ending at {@code nowMs}. */
  public boolean atLimit(K key, long nowMs) {
    long[] ring = rings.getIfPresent(key);
    if (ring == null) {
      return false;
    }
    synchronized (ring) {
      return full(ring, nowMs);
    }
  }

  /** Forgets the events of {@code key}. */
  public void reset(K key) {
    rings.invalidate(key);
  }

  private boolean full(long[] ring, long nowMs) {
    if (ring[1] < limit) {
      return false;
    }
    long oldest = ring[2 + (int) ring[0]];
    return nowMs - oldest < windowMs;
  }

  private long[] newRing() {
    return new long[2 + limit];
  }
}
