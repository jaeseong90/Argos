/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import java.util.function.Consumer;

/**
 * One bounded queue between application threads and the sender (10 §10.1, DSN-10-09, A-12).
 *
 * <p>Contract (implemented by the queue lane of WO-10; consumed by the sender of WO-11):
 *
 * <ul>
 *   <li>{@link #offer(QueueItem)} is called on application threads: it never blocks, never waits
 *       and never throws (N-05). At a bound it drops the oldest item once and retries once (the
 *       {@code active} queue overwrites its single slot instead); an item that still does not fit
 *       is dropped. Every drop counts in {@code argos_agent_dropped_total{queue}} ({@code
 *       AgentCounters#incDropped}).
 *   <li>Bounds: item count ({@code buffer.transaction.capacity} 20,000; stats 60 windows; metric
 *       120 snapshots; active 1), estimated bytes ({@code buffer.max-bytes} 32 MB shared by the
 *       transaction and profile queues; profile alone {@code buffer.profile.max-bytes} 16 MB of 10
 *       §10.1, a constant because 10 §15 has no such key) and age ({@code buffer.max-age-seconds}
 *       120: older items are dropped and counted when they are drained).
 *   <li>{@link #drain(int, Consumer)} is called by one consumer thread ({@code argos-sender}) and
 *       hands over at most {@code max} items, oldest first.
 *   <li>Lock-free multi-producer single-consumer queue: JCTools {@code MpscAtomicArrayQueue} of the
 *       {@code atomic} package (no {@code Unsafe}, 10 §10.1, §14), shaded under {@code
 *       io.argos.agent.shaded}; a producer that polls the oldest item when full takes the consumer
 *       token first and skips the poll when it is taken ({@link BoundedQueue}).
 * </ul>
 *
 * @param <T> the item type
 */
public interface AgentQueue<T extends QueueItem> {
  /** The {@code queue} label of this queue ({@code transaction}, {@code profile}, ...). */
  String name();

  /** Adds {@code item}; false when it (or nothing at all) could be kept. Never blocks. */
  boolean offer(T item);

  /**
   * Hands at most {@code max} items, oldest first, to {@code consumer} and returns how many; items
   * older than the age bound are dropped and counted instead of handed over. One consumer thread.
   */
  int drain(int max, Consumer<? super T> consumer);

  /** Items in the queue now (approximate under concurrency). */
  int size();

  /** Estimated bytes of the items in the queue now (approximate under concurrency). */
  long estimatedBytes();
}
