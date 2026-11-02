/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code tx_id} = 41-bit milliseconds since 2026-01-01T00:00:00Z | 12-bit node | 11-bit sequence
 * (DSN-02-09, 10 §9.5). Unique and increasing per instance.
 *
 * <p>Deviation from 10 §9.5 ("초과 시 다음 ms까지 스핀"): when the 2,048 sequence numbers of a millisecond
 * are used up, or the clock moves backwards, the generator borrows the next millisecond instead of
 * waiting, so an application thread never parks here (CLAUDE.md: never block application threads).
 * The id's time drifts ahead by at most the borrowed milliseconds.
 *
 * <p>Second deviation: the provisional node id (before {@code ServerHello}) comes from {@link
 * ThreadLocalRandom} mixed with the clock instead of {@code SecureRandom}, whose seeding loads the
 * security providers and took 0.7-0.9 s of premain on Java 8 / Windows (N-30 budget 500 ms, WO-02
 * measurement). The id only has to make collisions between instances unlikely until the server
 * assigns the node.
 */
public final class TxIdGenerator {
  /** 2026-01-01T00:00:00Z in epoch milliseconds. */
  public static final long EPOCH_MS = 1767225600000L;

  static final int SEQUENCE_BITS = 11;
  static final int NODE_BITS = 12;
  static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
  static final long MAX_NODE = (1L << NODE_BITS) - 1;

  private final AtomicLong state = new AtomicLong(-1L);
  private volatile long node;

  public TxIdGenerator() {
    this(
        ThreadLocalRandom.current().nextInt()
            ^ System.nanoTime()
            ^ System.identityHashCode(new Object()));
  }

  TxIdGenerator(long node) {
    setNode(node);
  }

  /** Replaces the node id (after {@code ServerHello}: {@code instance_id mod 4096}). */
  public void setNode(long value) {
    node = value & MAX_NODE;
  }

  public long next() {
    return next(System.currentTimeMillis());
  }

  long next(long nowMillis) {
    long now = Math.max(0, nowMillis - EPOCH_MS);
    while (true) {
      long current = state.get();
      long lastMillis = current < 0 ? -1 : current >>> SEQUENCE_BITS;
      long sequence = current < 0 ? 0 : current & MAX_SEQUENCE;
      long millis;
      long nextSequence;
      if (now > lastMillis) {
        millis = now;
        nextSequence = 0;
      } else if (sequence < MAX_SEQUENCE) {
        millis = lastMillis;
        nextSequence = sequence + 1;
      } else {
        millis = lastMillis + 1;
        nextSequence = 0;
      }
      if (state.compareAndSet(current, (millis << SEQUENCE_BITS) | nextSequence)) {
        return (millis << (NODE_BITS + SEQUENCE_BITS)) | (node << SEQUENCE_BITS) | nextSequence;
      }
    }
  }

  /** The epoch milliseconds encoded in {@code txId}. */
  public static long epochMillisOf(long txId) {
    return (txId >>> (NODE_BITS + SEQUENCE_BITS)) + EPOCH_MS;
  }
}
