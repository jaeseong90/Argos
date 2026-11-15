/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transaction ids as the agent makes them (DSN-02-09, 13 §2): {@code (ms − 2026-01-01T00:00:00Z) <<
 * 23 | node (12 bits) << 11 | seq (11 bits)}; the time prefix {@code tx_id >> 23} is the start time
 * on the agent clock (D-11, ADR-0010 C-121).
 */
public final class TxIds {

  /** 2026-01-01T00:00:00Z in epoch milliseconds, the origin of the time prefix. */
  public static final long EPOCH_MS = 1_767_225_600_000L;

  private final int node;
  private final AtomicInteger seq = new AtomicInteger();

  /** Ids of agent node {@code node} (0..4095). */
  public TxIds(int node) {
    if (node < 0 || node > 0xFFF) {
      throw new IllegalArgumentException("node out of range: " + node);
    }
    this.node = node;
  }

  /** The next id with start time {@code startMs} (agent clock). */
  public long next(long startMs) {
    return of(startMs, node, seq.getAndIncrement() & 0x7FF);
  }

  /** The id of {@code startMs}, {@code node} and {@code seq}. */
  public static long of(long startMs, int node, int seq) {
    if (startMs < EPOCH_MS) {
      throw new IllegalArgumentException("start before 2026-01-01: " + startMs);
    }
    return ((startMs - EPOCH_MS) << 23) | ((long) (node & 0xFFF) << 11) | (seq & 0x7FF);
  }

  /** The start time (epoch milliseconds, agent clock) in the prefix of {@code txId}. */
  public static long startMs(long txId) {
    return (txId >>> 23) + EPOCH_MS;
  }
}
