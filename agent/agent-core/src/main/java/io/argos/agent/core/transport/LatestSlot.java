/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.guard.AgentCounters;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The single-slot queue of 10 §10.1 ({@code ActiveQueue}: "단일 슬롯(최신만)", "덮어쓰기"): an offer replaces
 * the item that is there. A replaced item was never sent, so it counts as dropped ({@code
 * argos_agent_dropped_total{queue="active"}}). Lock-free ({@link AtomicReference#getAndSet}).
 */
public final class LatestSlot implements AgentQueue<QueueItem> {
  private final String name;
  private final AgentCounters counters;
  private final AtomicReference<QueueItem> slot = new AtomicReference<QueueItem>();

  public LatestSlot(String name, AgentCounters counters) {
    this.name = Objects.requireNonNull(name, "name");
    this.counters = counters;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean offer(QueueItem item) {
    if (item == null) {
      return false;
    }
    QueueItem replaced = slot.getAndSet(item);
    if (replaced != null && counters != null) {
      counters.incDropped(name);
    }
    return true;
  }

  @Override
  public int drain(int max, Consumer<? super QueueItem> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    if (max < 1) {
      return 0;
    }
    QueueItem item = slot.getAndSet(null);
    if (item == null) {
      return 0;
    }
    consumer.accept(item);
    return 1;
  }

  @Override
  public int size() {
    return slot.get() == null ? 0 : 1;
  }

  @Override
  public long estimatedBytes() {
    QueueItem item = slot.get();
    return item == null ? 0L : Math.max(0L, item.estimatedBytes());
  }
}
