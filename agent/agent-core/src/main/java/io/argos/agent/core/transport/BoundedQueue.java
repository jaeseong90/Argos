/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.guard.AgentCounters;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jctools.queues.atomic.MpscAtomicArrayQueue;

/**
 * A bounded queue of 10 §10.1 (DSN-10-09): item count, estimated bytes and age bounds, offer-only
 * for application threads (N-05).
 *
 * <p><b>Storage</b>: a JCTools {@code MpscAtomicArrayQueue} (10 §10.1: the multi-producer
 * single-consumer array queue of the {@code atomic} package: {@code AtomicReferenceArray}, no
 * {@code sun.misc.Unsafe}; shaded under {@code io.argos.agent.shaded}). The drop policy of 10 §10.1
 * has producers {@code poll()} the oldest item, and a single-consumer queue allows one polling
 * thread at a time only: every poll therefore takes the consumer token first (one {@code
 * compareAndSet}, never a wait). The sender drains with the token; a producer that finds it taken
 * (the sender or another producer is polling at that instant) skips its poll and goes straight to
 * the retry. Polls use {@code relaxedPoll()}, which returns null instead of spinning on a slot that
 * another producer claimed but has not written yet, so a producer never waits for another thread.
 *
 * <p><b>Offer</b> (application threads, never blocks, never throws): the item reserves one count
 * slot, its bytes in the queue's own budget (if any) and its bytes in the shared budget (if any).
 * If something does not fit, the oldest item is polled and dropped once and the reservation is
 * retried once; if it still does not fit, the new item is dropped. An item larger than a byte
 * budget on its own is dropped at once (dropping others would not make room). Every dropped item
 * counts {@code argos_agent_dropped_total{queue}}; a dropped profile also counts {@code
 * argos_agent_profile_dropped_total} (10 §9.2 step 3).
 *
 * <p><b>Drain</b> (one consumer, {@code argos-sender}): oldest first; items older than the age
 * bound ({@code buffer.max-age-seconds}, 120) are dropped and counted instead of handed over.
 */
public final class BoundedQueue implements AgentQueue<QueueItem> {
  /** Monotonic clock of the age bound ({@code System.nanoTime()}; tests inject one). */
  public interface NanoClock {
    long nanoTime();
  }

  private static final NanoClock SYSTEM = new SystemNanoClock();

  /** Attempts of a drain to take the consumer token from a producer that polls. */
  static final int DRAIN_TOKEN_ATTEMPTS = 100;

  private final String name;
  private final int capacity;
  private final ByteBudget ownBytes;
  private final ByteBudget sharedBytes;
  private final long maxAgeNanos;
  private final AgentCounters counters;
  private final NanoClock clock;
  private final MpscAtomicArrayQueue<QueueItem> queue;

  /** The single-consumer token of {@link #queue}: held by whoever polls it (see class comment). */
  private final AtomicBoolean pollToken = new AtomicBoolean();

  private final AtomicInteger count = new AtomicInteger();
  private final AtomicLong bytes = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong expired = new AtomicLong();

  /**
   * @param name the {@code queue} label ({@link Queues#TRANSACTION}, ...)
   * @param capacity item count bound (at least 2, the JCTools minimum)
   * @param ownBytes this queue's byte bound, or null
   * @param sharedBytes a byte bound shared with other queues, or null
   * @param maxAgeSeconds age bound applied when draining, 0 for none
   * @param counters drop counters (may be null in tests)
   * @param clock clock of the age bound, null for {@code System.nanoTime()}
   */
  public BoundedQueue(
      String name,
      int capacity,
      ByteBudget ownBytes,
      ByteBudget sharedBytes,
      int maxAgeSeconds,
      AgentCounters counters,
      NanoClock clock) {
    this.name = Objects.requireNonNull(name, "name");
    this.capacity = Math.max(2, capacity);
    this.ownBytes = ownBytes;
    this.sharedBytes = sharedBytes;
    this.maxAgeNanos = maxAgeSeconds > 0 ? TimeUnit.SECONDS.toNanos(maxAgeSeconds) : 0L;
    this.counters = counters;
    this.clock = clock == null ? SYSTEM : clock;
    this.queue = new MpscAtomicArrayQueue<QueueItem>(this.capacity);
  }

  @Override
  public String name() {
    return name;
  }

  /** The item count bound. */
  public int capacity() {
    return capacity;
  }

  @Override
  public boolean offer(QueueItem item) {
    if (item == null) {
      return false;
    }
    try {
      long size = Math.max(0L, item.estimatedBytes());
      if (tooLarge(ownBytes, size) || tooLarge(sharedBytes, size)) {
        drop();
        return false;
      }
      if (!reserve(size)) {
        dropOldest();
        if (!reserve(size)) {
          drop();
          return false;
        }
      }
      if (!queue.offer(item)) {
        // Cannot happen while the count reservation holds; never lose the reservation.
        release(size);
        drop();
        return false;
      }
      return true;
    } catch (RuntimeException e) {
      drop();
      return false;
    }
  }

  private static boolean tooLarge(ByteBudget budget, long size) {
    return budget != null && size > budget.limit();
  }

  private boolean reserve(long size) {
    while (true) {
      int current = count.get();
      if (current >= capacity) {
        return false;
      }
      if (count.compareAndSet(current, current + 1)) {
        break;
      }
    }
    if (ownBytes != null && !ownBytes.tryReserve(size)) {
      count.decrementAndGet();
      return false;
    }
    if (sharedBytes != null && !sharedBytes.tryReserve(size)) {
      if (ownBytes != null) {
        ownBytes.release(size);
      }
      count.decrementAndGet();
      return false;
    }
    bytes.addAndGet(size);
    return true;
  }

  private void release(long size) {
    bytes.addAndGet(-size);
    if (sharedBytes != null) {
      sharedBytes.release(size);
    }
    if (ownBytes != null) {
      ownBytes.release(size);
    }
    count.decrementAndGet();
  }

  /** Drops the oldest item, unless another thread is polling right now (then nothing). */
  private void dropOldest() {
    QueueItem oldest = pollOnce();
    if (oldest != null) {
      release(Math.max(0L, oldest.estimatedBytes()));
      drop();
    }
  }

  /**
   * One {@code relaxedPoll()} under the consumer token, or null when the token is taken or the
   * oldest slot is not written yet. Never waits.
   */
  private QueueItem pollOnce() {
    if (!pollToken.compareAndSet(false, true)) {
      return null;
    }
    try {
      return queue.relaxedPoll();
    } finally {
      pollToken.set(false);
    }
  }

  /**
   * The drain's poll (agent thread): waits for the consumer token while a producer holds it for its
   * single poll (a few instructions), at most {@value #DRAIN_TOKEN_ATTEMPTS} attempts.
   */
  private QueueItem pollForDrain() {
    for (int attempt = 0; attempt < DRAIN_TOKEN_ATTEMPTS; attempt++) {
      if (pollToken.compareAndSet(false, true)) {
        try {
          return queue.relaxedPoll();
        } finally {
          pollToken.set(false);
        }
      }
      LockSupport.parkNanos(1_000L); // agent thread only (argos-sender)
    }
    return null;
  }

  private void drop() {
    dropped.incrementAndGet();
    if (counters != null) {
      counters.incDropped(name);
      if (Queues.PROFILE.equals(name)) {
        // 10 §9.2 step 3: a captured profile that is not sent ({@code profile_captured} stays
        // true on its record; the server shows it as lost, D-07).
        counters.incProfileDropped();
      }
    }
  }

  @Override
  public int drain(int max, Consumer<? super QueueItem> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    int handed = 0;
    long now = maxAgeNanos > 0 ? clock.nanoTime() : 0L;
    while (handed < max) {
      QueueItem item = pollForDrain();
      if (item == null) {
        break;
      }
      release(Math.max(0L, item.estimatedBytes()));
      if (maxAgeNanos > 0 && now - item.createdNanos() > maxAgeNanos) {
        expired.incrementAndGet();
        drop();
        continue;
      }
      consumer.accept(item);
      handed++;
    }
    return handed;
  }

  @Override
  public int size() {
    return Math.max(0, count.get());
  }

  @Override
  public long estimatedBytes() {
    return Math.max(0L, bytes.get());
  }

  /** Items dropped by this queue (bounds and age). */
  public long dropped() {
    return dropped.get();
  }

  /** Items dropped at drain time because they were older than the age bound. */
  public long expired() {
    return expired.get();
  }

  private static final class SystemNanoClock implements NanoClock {
    @Override
    public long nanoTime() {
      return System.nanoTime();
    }
  }
}
