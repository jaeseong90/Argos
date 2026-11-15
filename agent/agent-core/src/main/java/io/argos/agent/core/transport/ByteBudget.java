/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A bound on estimated bytes, shared by the queues that draw from it (10 §10.1, §14: {@code
 * buffer.max-bytes} 32 MB for the transaction and profile queues together; the profile queue's own
 * 16 MB). Lock-free: a reservation is a compare-and-set loop that never waits.
 */
public final class ByteBudget {
  private final long limit;
  private final AtomicLong used = new AtomicLong();

  /** A budget of {@code limit} bytes (at least 1). */
  public ByteBudget(long limit) {
    this.limit = Math.max(1L, limit);
  }

  /** Takes {@code bytes} if they fit; false (nothing taken) otherwise. */
  public boolean tryReserve(long bytes) {
    if (bytes < 0) {
      return false;
    }
    while (true) {
      long current = used.get();
      if (current + bytes > limit) {
        return false;
      }
      if (used.compareAndSet(current, current + bytes)) {
        return true;
      }
    }
  }

  /** Gives back {@code bytes} taken with {@link #tryReserve(long)}. */
  public void release(long bytes) {
    used.addAndGet(-bytes);
  }

  public long limit() {
    return limit;
  }

  /** Bytes taken now. */
  public long used() {
    return Math.max(0L, used.get());
  }
}
