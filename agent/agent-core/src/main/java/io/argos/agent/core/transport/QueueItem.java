/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * An item of an {@link AgentQueue}: a transaction record, a profile, a statistics window, a metric
 * snapshot or an active snapshot (10 §10.1). Both values are fixed once the item is offered, so a
 * queue adds and subtracts the same estimate.
 */
public interface QueueItem {
  /** Rough heap size in bytes ({@code buffer.max-bytes}, N-02 heap budget). */
  long estimatedBytes();

  /** {@code System.nanoTime()} when the item was created ({@code buffer.max-age-seconds}). */
  long createdNanos();
}
