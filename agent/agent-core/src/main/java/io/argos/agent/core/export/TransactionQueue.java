/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Transaction;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ended transactions waiting for the exporter (10 §10.1 TransactionQueue, A-12). Lock-free
 * multi-producer queue bounded by count ({@code buffer.transaction.capacity}, 20,000) and by
 * estimated bytes ({@code buffer.max-bytes}). Application threads only {@link #offer}: when full,
 * the oldest entry is dropped and the offer is retried once (never waiting, N-05).
 *
 * <p>WO-11 replaces the queue with the JCTools {@code MpscArrayQueue} (atomic variant) and adds the
 * age bound ({@code buffer.max-age-seconds}); the contract stays the same.
 */
public final class TransactionQueue {
  private final ConcurrentLinkedQueue<Transaction> queue = new ConcurrentLinkedQueue<Transaction>();
  private final AtomicInteger count = new AtomicInteger();
  private final AtomicLong bytes = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final int capacity;
  private final long maxBytes;

  public TransactionQueue(int capacity, long maxBytes) {
    this.capacity = Math.max(1, capacity);
    this.maxBytes = Math.max(1, maxBytes);
  }

  /** Adds {@code tx}; drops the oldest entry (or {@code tx} itself) when a bound is reached. */
  public void offer(Transaction tx) {
    long size = tx.estimatedBytes();
    if (count.get() >= capacity || bytes.get() + size > maxBytes) {
      dropOldest();
      if (count.get() >= capacity || bytes.get() + size > maxBytes) {
        dropped.incrementAndGet();
        return;
      }
    }
    count.incrementAndGet();
    bytes.addAndGet(size);
    queue.offer(tx);
  }

  private void dropOldest() {
    Transaction oldest = queue.poll();
    if (oldest != null) {
      count.decrementAndGet();
      bytes.addAndGet(-oldest.estimatedBytes());
      dropped.incrementAndGet();
    }
  }

  /** Moves up to {@code max} transactions, oldest first, into {@code batch}; returns how many. */
  public int drainTo(List<Transaction> batch, int max) {
    int moved = 0;
    while (moved < max) {
      Transaction tx = queue.poll();
      if (tx == null) {
        break;
      }
      count.decrementAndGet();
      bytes.addAndGet(-tx.estimatedBytes());
      batch.add(tx);
      moved++;
    }
    return moved;
  }

  public int size() {
    return Math.max(0, count.get());
  }

  /** Transactions dropped because a bound was reached ({@code argos_agent_dropped_total}). */
  public long dropped() {
    return dropped.get();
  }
}
