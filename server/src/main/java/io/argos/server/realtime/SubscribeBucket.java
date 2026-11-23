/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The subscription rate limit of one WebSocket connection ({@code RateLimitBucket.WS_SUBSCRIBE}, 14
 * §2.7, 16 §7.13; the numbers are WO-22's decision under ADR-0010 C-136 (2)): a token bucket of
 * {@value #CAPACITY} tokens, refilled by one token per second (60 per minute, the 16 §7.13 rate).
 * Every {@code subscribe} frame takes a token; {@code unsubscribe} and {@code ping} take none (an
 * unsubscribe only lowers the load, and refusing it would leave a stream the client already
 * dropped). The capacity equals the subscription limit of a connection, so a client that reconnects
 * can subscribe everything again at once.
 */
final class SubscribeBucket {

  /** Tokens of a full bucket: the 20 subscriptions of a connection (14 §5.1). */
  static final int CAPACITY = WsConnection.MAX_SUBSCRIPTIONS;

  /** Nanoseconds per refilled token (one per second). */
  static final long REFILL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final LongSupplier nanoTime;
  private long tokens = CAPACITY;
  private long lastRefill;

  /** A full bucket on {@code nanoTime} ({@link System#nanoTime} in the server). */
  SubscribeBucket(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
    this.lastRefill = nanoTime.getAsLong();
  }

  /** Takes one token; false when the bucket is empty (the frame is refused). */
  synchronized boolean tryTake() {
    long now = nanoTime.getAsLong();
    long elapsed = now - lastRefill;
    if (elapsed >= REFILL_NANOS) {
      long refill = elapsed / REFILL_NANOS;
      tokens = Math.min(CAPACITY, tokens + refill);
      lastRefill = tokens == CAPACITY ? now : lastRefill + refill * REFILL_NANOS;
    }
    if (tokens == 0) {
      return false;
    }
    tokens--;
    return true;
  }
}
