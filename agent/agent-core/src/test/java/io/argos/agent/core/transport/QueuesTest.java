/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The queues of 10 §10.1 (DSN-10-09): count, bytes and age bounds, drop the oldest once and retry
 * once, exact drop counts, and offers that never block (N-05).
 */
@Tag("req:DSN-10-09")
class QueuesTest {

  /** A queue item of a fixed size and creation time. */
  static final class Item implements QueueItem {
    final int id;
    final long bytes;
    final long created;

    Item(int id, long bytes, long created) {
      this.id = id;
      this.bytes = bytes;
      this.created = created;
    }

    @Override
    public long estimatedBytes() {
      return bytes;
    }

    @Override
    public long createdNanos() {
      return created;
    }
  }

  static final class Clock implements BoundedQueue.NanoClock {
    long now;

    @Override
    public long nanoTime() {
      return now;
    }
  }

  private static List<Integer> drain(AgentQueue<QueueItem> queue) {
    final List<Integer> ids = new ArrayList<Integer>();
    queue.drain(
        10_000,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            ids.add(((Item) item).id);
          }
        });
    return ids;
  }

  @Test
  @DisplayName(
      "count bound: full -> the oldest is dropped once and the offer retried; drops counted")
  void countBound() {
    AgentCounters counters = new AgentCounters();
    BoundedQueue queue = new BoundedQueue(Queues.TRANSACTION, 3, null, null, 0, counters, null);
    for (int i = 0; i < 5; i++) {
      assertThat(queue.offer(new Item(i, 10, 0))).isTrue();
    }
    assertThat(queue.size()).isEqualTo(3);
    assertThat(queue.estimatedBytes()).isEqualTo(30);
    assertThat(queue.dropped()).isEqualTo(2);
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(2);
    assertThat(drain(queue)).containsExactly(2, 3, 4);
    assertThat(queue.size()).isZero();
    assertThat(queue.estimatedBytes()).isZero();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "byte bounds: buffer.max-bytes shared by transaction and profile, profile 16 MB of its own;"
          + " an item larger than a budget is dropped without dropping others")
  void byteBounds() {
    AgentCounters counters = new AgentCounters();
    Queues queues = Queues.create(100, 1000, 600, 0, counters, null);
    AgentQueue<QueueItem> tx = queues.transaction();
    AgentQueue<QueueItem> profile = queues.profile();
    assertThat(profile.offer(new Item(1, 300, 0))).isTrue();
    assertThat(profile.offer(new Item(2, 300, 0))).isTrue();
    // Profile own budget (600) full: the oldest profile goes.
    assertThat(profile.offer(new Item(3, 300, 0))).isTrue();
    assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(1);
    // Shared budget 1000: 600 profile + 400 transactions.
    assertThat(tx.offer(new Item(10, 200, 0))).isTrue();
    assertThat(tx.offer(new Item(11, 200, 0))).isTrue();
    assertThat(tx.offer(new Item(12, 200, 0))).as("drops tx 10 and retries").isTrue();
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
    assertThat(tx.estimatedBytes() + profile.estimatedBytes()).isLessThanOrEqualTo(1000);
    // A transaction that does not fit even after one drop: dropped, the queue keeps the rest.
    assertThat(tx.offer(new Item(13, 450, 0))).isFalse();
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(3);
    assertThat(drain(tx)).as("the oldest went first, then the new item itself").containsExactly(12);
    // Larger than the profile budget on its own: dropped at once, nothing else dropped.
    long before = counters.dropped(Queues.PROFILE);
    assertThat(profile.offer(new Item(4, 700, 0))).isFalse();
    assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(before + 1);
    assertThat(drain(profile)).containsExactly(2, 3);
    assertThat(queues.estimatedBytes()).isZero();
  }

  @Test
  @DisplayName(
      "age bound: items older than buffer.max-age-seconds are dropped and counted at drain")
  void ageBound() {
    AgentCounters counters = new AgentCounters();
    Clock clock = new Clock();
    Queues queues = Queues.create(100, 1L << 20, 1L << 20, 120, counters, clock);
    long second = TimeUnit.SECONDS.toNanos(1);
    clock.now = 1_000 * second;
    queues.transaction().offer(new Item(1, 1, clock.now - 121 * second));
    queues.transaction().offer(new Item(2, 1, clock.now - 120 * second));
    queues.transaction().offer(new Item(3, 1, clock.now - 5 * second));
    queues.profile().offer(new Item(4, 1, clock.now - 200 * second));
    assertThat(drain(queues.transaction())).containsExactly(2, 3);
    assertThat(drain(queues.profile())).isEmpty();
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
    assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(1);
    assertThat(((BoundedQueue) queues.transaction()).expired()).isEqualTo(1);
    // Statistics and metric windows keep ten minutes: no age bound.
    queues.stats().offer(new Item(5, 1, clock.now - 600 * second));
    assertThat(drain(queues.stats())).containsExactly(5);
  }

  @Test
  @DisplayName("configured bounds: 20,000 / 32 MB / 120 s, profile 16 MB, stats 60, metric 120")
  void configuredBounds() {
    AgentCounters counters = new AgentCounters();
    Queues queues = Queues.create(AgentConfig.of(Collections.<String, String>emptyMap()), counters);
    assertThat(((BoundedQueue) queues.transaction()).capacity()).isEqualTo(20_000);
    assertThat(((BoundedQueue) queues.profile()).capacity()).isEqualTo(20_000);
    assertThat(((BoundedQueue) queues.stats()).capacity()).isEqualTo(Queues.STATS_CAPACITY);
    assertThat(((BoundedQueue) queues.metric()).capacity()).isEqualTo(Queues.METRIC_CAPACITY);
    assertThat(queues.active()).isInstanceOf(LatestSlot.class);
    for (int i = 0; i < Queues.STATS_CAPACITY + 7; i++) {
      queues.stats().offer(new Item(i, 1, 0));
    }
    assertThat(queues.stats().size()).isEqualTo(60);
    assertThat(counters.dropped(Queues.STATS)).isEqualTo(7);
    // 32 MB shared: 4 MB profiles, 16 MB of them at most.
    for (int i = 0; i < 6; i++) {
      queues.profile().offer(new Item(i, 4L << 20, 0));
    }
    assertThat(queues.profile().estimatedBytes()).isEqualTo(16L << 20);
    assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(2);
    for (int i = 0; i < 6; i++) {
      queues.transaction().offer(new Item(i, 4L << 20, 0));
    }
    assertThat(queues.transaction().estimatedBytes()).isEqualTo(16L << 20);
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(2);
  }

  @Test
  @DisplayName("active: one slot, the latest wins; a replaced snapshot counts as dropped")
  void activeSlot() {
    AgentCounters counters = new AgentCounters();
    LatestSlot active = new LatestSlot(Queues.ACTIVE, counters);
    active.offer(new Item(1, 5, 0));
    active.offer(new Item(2, 7, 0));
    assertThat(active.size()).isEqualTo(1);
    assertThat(active.estimatedBytes()).isEqualTo(7);
    assertThat(drain(active)).containsExactly(2);
    assertThat(active.size()).isZero();
    assertThat(counters.dropped(Queues.ACTIVE)).isEqualTo(1);
  }

  @Test
  @Tag("req:N-05")
  @DisplayName(
      "N-05: 16 threads offer 20,000 items each to a full queue; every offer returns, the bounds"
          + " hold and kept + dropped = offered")
  void offersNeverBlock() throws Exception {
    final AgentCounters counters = new AgentCounters();
    final BoundedQueue queue =
        new BoundedQueue(
            Queues.TRANSACTION, 1_000, null, new ByteBudget(50_000), 0, counters, null);
    for (int i = 0; i < 1_000; i++) {
      queue.offer(new Item(i, 10, 0));
    }
    final int threads = 16;
    final int perThread = 20_000;
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final AtomicLong slowest = new AtomicLong();
    for (int t = 0; t < threads; t++) {
      final int base = (t + 1) * 1_000_000;
      new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                      long before = System.nanoTime();
                      queue.offer(new Item(base + i, 10 + (i % 50), 0));
                      long took = System.nanoTime() - before;
                      if (took > slowest.get()) {
                        slowest.set(took);
                      }
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    done.countDown();
                  }
                }
              })
          .start();
    }
    long began = System.nanoTime();
    start.countDown();
    assertThat(done.await(20, TimeUnit.SECONDS)).as("all offers returned").isTrue();
    long total = System.nanoTime() - began;
    assertThat(queue.size()).isLessThanOrEqualTo(1_000);
    assertThat(queue.estimatedBytes()).isLessThanOrEqualTo(50_000);
    int kept = drain(queue).size();
    assertThat(kept + queue.dropped()).isEqualTo(1_000L + threads * (long) perThread);
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(queue.dropped());
    assertThat(queue.estimatedBytes()).isZero();
    System.out.println(
        "[queues] 16 x 20,000 offers to a full queue: "
            + TimeUnit.NANOSECONDS.toMillis(total)
            + " ms, slowest offer "
            + TimeUnit.NANOSECONDS.toMicros(slowest.get())
            + " µs (reference, shared machine)");
  }

  @Test
  @Tag("req:N-05")
  @DisplayName(
      "single consumer (MPSC): 8 producers dropping the oldest while the sender drains; every item"
          + " is handed over at most once and kept + dropped = offered")
  void producersPollWhileTheSenderDrains() throws Exception {
    final AgentCounters counters = new AgentCounters();
    final BoundedQueue queue =
        new BoundedQueue(Queues.TRANSACTION, 256, null, null, 0, counters, null);
    final int threads = 8;
    final int perThread = 20_000;
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      final int base = (t + 1) * 1_000_000;
      new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                      queue.offer(new Item(base + i, 10, 0));
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    done.countDown();
                  }
                }
              })
          .start();
    }
    final java.util.Set<Integer> handed = new java.util.HashSet<Integer>();
    final int[] twice = new int[1];
    Consumer<QueueItem> sender =
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            if (!handed.add(((Item) item).id)) {
              twice[0]++;
            }
          }
        };
    start.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (done.getCount() > 0 && System.nanoTime() < deadline) {
      queue.drain(50, sender);
    }
    assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    queue.drain(10_000, sender);
    assertThat(twice[0]).as("items handed over twice").isZero();
    assertThat(handed.size() + queue.dropped()).isEqualTo((long) threads * perThread);
    assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(queue.dropped());
    assertThat(queue.size()).isZero();
    assertThat(queue.estimatedBytes()).isZero();
  }
}
