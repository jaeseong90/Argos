/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.StepType;
import io.argos.agent.core.context.ThreadNames;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * StepBuffer (10 §9.1, DSN-10-08): structure of arrays, growth 64 -> 2x up to max-steps, summary
 * mode, sealing, and every memory cap of the buffer (10 §14 "상한").
 */
@Tag("req:N-02")
class StepBufferTest {
  private static final Thread ME = Thread.currentThread();

  @Test
  @DisplayName("records steps with offsets, elapsed, status, rows, fetch, detail and parent")
  void recordsSteps() {
    StepBuffer steps = new StepBuffer(1_000, 1000);
    int outer = steps.start(StepType.METHOD, 11, 0, 1_500, StepBuffer.NO_PARENT, ME);
    int inner = steps.start(StepType.SQL, 22, 33, 2_000, outer, ME);
    steps.end(inner, 0, false, 5, 5_000);
    steps.fetch(inner, 7, 400);
    steps.detail(inner, "detail");
    steps.end(outer, 1, true, 0, 9_000);
    int sibling = steps.start(StepType.HTTP, 44, 0, 9_500, StepBuffer.NO_PARENT, ME);
    assertThat(steps.size()).isEqualTo(3);
    assertThat(steps.totalSteps()).isEqualTo(3);
    assertThat(steps.parent(outer)).isEqualTo(StepBuffer.NO_PARENT);
    assertThat(steps.parent(inner)).isEqualTo(outer);
    assertThat(steps.type(inner)).isEqualTo(StepType.SQL);
    assertThat(steps.nameId(inner)).isEqualTo(22);
    assertThat(steps.targetId(inner)).isEqualTo(33);
    assertThat(steps.startOffsetNanos(inner)).isEqualTo(1_000);
    assertThat(steps.elapsedNanos(inner)).isEqualTo(3_000);
    assertThat(steps.rows(inner)).isEqualTo(12);
    assertThat(steps.fetchNanos(inner)).isEqualTo(400);
    assertThat(steps.detail(inner)).isEqualTo("detail");
    assertThat(steps.error(outer)).isTrue();
    assertThat(steps.status(outer)).isEqualTo(1);
    assertThat(steps.elapsedNanos(sibling)).isEqualTo(-1); // never closed
    assertThat(steps.isClosed(sibling)).isFalse();
    assertThat(steps.threadName(inner)).isEqualTo(ME.getName());
    assertThat(steps.summarized()).isFalse();
  }

  @Test
  @DisplayName(
      "WO-02 A-2: the rows and fetch arrays of a chunk are created by the first step that has"
          + " them; the estimate counts them from then on")
  void lazyRowsAndFetch() {
    StepBuffer steps = new StepBuffer(0, 1000);
    int method = steps.start(StepType.METHOD, 1, 0, 0, StepBuffer.NO_PARENT, ME);
    steps.end(method, 0, false, 0, 10);
    long withoutRows = steps.estimatedBytes();
    assertThat(steps.rows(method)).isZero();
    assertThat(steps.fetchNanos(method)).isZero();
    int sql = steps.start(StepType.SQL, 2, 0, 20, method, ME);
    steps.end(sql, 0, false, 3, 30);
    long rowsArray = 32L + 64L * StepBuffer.LAZY_LONG_BYTES;
    assertThat(steps.estimatedBytes()).isEqualTo(withoutRows + rowsArray);
    steps.fetch(sql, 2, 500);
    assertThat(steps.estimatedBytes()).isEqualTo(withoutRows + 2 * rowsArray);
    assertThat(steps.rows(sql)).isEqualTo(5);
    assertThat(steps.fetchNanos(sql)).isEqualTo(500);
    assertThat(steps.rows(method)).isZero();
  }

  @Test
  @DisplayName("ignores unknown, closed and negative sequence numbers; a later parent is ignored")
  void ignoresInvalidHandles() {
    StepBuffer steps = new StepBuffer(0, 10);
    int seq = steps.start(StepType.SQL, 1, 0, 10, 5, ME);
    assertThat(steps.parent(seq)).as("parent must precede the step").isEqualTo(-1);
    steps.end(seq, 0, false, 1, 20);
    steps.end(seq, 9, true, 100, 99);
    steps.end(-1, 0, false, 0, 0);
    steps.end(5, 0, false, 0, 0);
    steps.fetch(-1, 1, 1);
    steps.detail(7, "x");
    steps.stack(7, new long[] {1});
    assertThat(steps.elapsedNanos(seq)).isEqualTo(10);
    assertThat(steps.rows(seq)).isEqualTo(1);
    assertThat(steps.status(seq)).isZero();
    assertThat(steps.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("growth: chunks of 64, 64, 128, 256, 512 slots, cut at max-steps (1,000)")
  void growth() {
    assertThat(StepBuffer.chunkIndex(0)).isZero();
    assertThat(StepBuffer.chunkIndex(63)).isZero();
    assertThat(StepBuffer.chunkIndex(64)).isEqualTo(1);
    assertThat(StepBuffer.chunkIndex(127)).isEqualTo(1);
    assertThat(StepBuffer.chunkIndex(128)).isEqualTo(2);
    assertThat(StepBuffer.chunkIndex(999)).isEqualTo(4);
    assertThat(StepBuffer.chunkStart(4)).isEqualTo(512);
    assertThat(StepBuffer.chunkSize(4)).isEqualTo(512);
    // Capacity after each chunk doubles: 64, 128, 256, 512, 1024 (10 §9.1 "초기 64, 2배").
    for (int k = 0; k < 5; k++) {
      assertThat(StepBuffer.chunkStart(k) + StepBuffer.chunkSize(k)).isEqualTo(64 << k);
    }
    StepBuffer steps = new StepBuffer(0, 1000);
    long empty = steps.estimatedBytes();
    assertThat(empty).as("no chunk before the first step").isLessThan(1_000);
    steps.start(StepType.SQL, 1, 0, 0, -1, ME);
    long oneChunk = steps.estimatedBytes();
    assertThat(oneChunk - empty).isBetween(64L * StepBuffer.SLOT_BYTES, 64L * 100);
    for (int i = 1; i < 1000; i++) {
      steps.end(steps.start(StepType.SQL, i, 0, i, -1, ME), 0, false, 0, i + 1);
    }
    assertThat(steps.size()).isEqualTo(1000);
    assertThat(steps.nameId(999)).isEqualTo(999);
    assertThat(steps.summarized()).isFalse();
    // 1,000 slots, not 1,024: the last chunk is cut at max-steps.
    assertThat(steps.estimatedBytes() - empty)
        .isLessThan(1000L * StepBuffer.SLOT_BYTES + 5 * 300)
        .isGreaterThanOrEqualTo(1000L * StepBuffer.SLOT_BYTES);
  }

  @Test
  @Tag("req:DSN-10-08")
  @DisplayName("summary mode beyond max-steps: count, total, max, errors per (type, name)")
  void summaryMode() {
    StepBuffer steps = new StepBuffer(0, 2);
    steps.end(steps.start(StepType.SQL, 1, 0, 0, -1, ME), 0, false, 0, 10);
    steps.end(steps.start(StepType.SQL, 1, 0, 0, -1, ME), 0, false, 0, 10);
    for (int i = 0; i < 5; i++) {
      int handle = steps.start(StepType.SQL, 7, 0, 100, -1, ME);
      assertThat(handle).isGreaterThanOrEqualTo(StepBuffer.SUMMARY_SEQ_BASE);
      steps.end(handle, 0, i == 4, 0, 100 + 10 * (i + 1));
    }
    int other = steps.start(StepType.HTTP, 8, 0, 0, -1, ME);
    steps.end(other, 0, false, 0, 3);
    assertThat(steps.size()).isEqualTo(2);
    assertThat(steps.totalSteps()).isEqualTo(8);
    assertThat(steps.summarized()).isTrue();
    assertThat(steps.summaryKeys()).isEqualTo(2);
    assertThat(steps.summaryType(0)).isEqualTo(StepType.SQL);
    assertThat(steps.summaryNameId(0)).isEqualTo(7);
    assertThat(steps.summaryCount(0)).isEqualTo(5);
    assertThat(steps.summaryTotalNanos(0)).isEqualTo(10 + 20 + 30 + 40 + 50);
    assertThat(steps.summaryMaxNanos(0)).isEqualTo(50);
    assertThat(steps.summaryErrors(0)).isEqualTo(1);
    assertThat(steps.summaryCount(1)).isEqualTo(1);
  }

  @Test
  @DisplayName("summary mode stays bounded: 256 keys, 64 open summary steps")
  void summaryBounds() {
    StepBuffer steps = new StepBuffer(0, 1);
    steps.start(StepType.SQL, 0, 0, 0, -1, ME);
    for (int name = 1; name <= StepBuffer.MAX_SUMMARY_KEYS + 10; name++) {
      steps.end(steps.start(StepType.SQL, name, 0, 0, -1, ME), 0, false, 0, 1);
    }
    assertThat(steps.summaryKeys()).isEqualTo(StepBuffer.MAX_SUMMARY_KEYS);
    assertThat(steps.summaryOverflow()).isEqualTo(10);
    assertThat(steps.totalSteps()).isEqualTo(1 + StepBuffer.MAX_SUMMARY_KEYS + 10);

    StepBuffer open = new StepBuffer(0, 1);
    open.start(StepType.SQL, 0, 0, 0, -1, ME);
    for (int i = 0; i < StepBuffer.MAX_PENDING_SUMMARY; i++) {
      assertThat(open.start(StepType.SQL, 5, 0, 0, -1, ME)).isGreaterThanOrEqualTo(0);
    }
    assertThat(open.start(StepType.SQL, 5, 0, 0, -1, ME)).isEqualTo(-1); // counted, no duration
    assertThat(open.summaryCount(0)).isEqualTo(1);
  }

  @Test
  @DisplayName("detail is cut at 4 KB, stacks at 64 frames; the size estimate grows with them")
  void detailAndStackBounds() {
    StepBuffer steps = new StepBuffer(0, 10);
    int seq = steps.start(StepType.SQL, 1, 0, 0, -1, ME);
    long before = steps.estimatedBytes();
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      big.append('x');
    }
    steps.detail(seq, big.toString());
    assertThat(steps.detail(seq)).hasSize(StepBuffer.DETAIL_MAX_CHARS);
    assertThat(steps.estimatedBytes()).isGreaterThan(before + 2 * StepBuffer.DETAIL_MAX_CHARS - 1);
    long[] frames = new long[200];
    steps.stack(seq, frames);
    assertThat(steps.stack(seq)).hasSize(StepBuffer.MAX_STACK_FRAMES);
  }

  @Test
  @DisplayName("step threads: names kept for 16 threads, later threads read as _other_")
  void threadBound() throws Exception {
    final StepBuffer steps = new StepBuffer(0, 100);
    for (int t = 0; t < StepBuffer.MAX_THREADS + 4; t++) {
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  steps.start(StepType.ASYNC, 1, 0, 0, -1, Thread.currentThread());
                }
              },
              "worker-" + t);
      thread.start();
      thread.join();
    }
    List<String> names = new ArrayList<String>();
    for (int seq = 0; seq < steps.size(); seq++) {
      names.add(steps.threadName(seq));
    }
    assertThat(names).hasSize(StepBuffer.MAX_THREADS + 4);
    assertThat(names.subList(0, StepBuffer.MAX_THREADS)).doesNotContain(ThreadNames.OTHER);
    assertThat(names.subList(StepBuffer.MAX_THREADS, names.size())).containsOnly(ThreadNames.OTHER);
    assertThat(new ArrayList<String>(names.subList(0, 2))).containsExactly("worker-0", "worker-1");
  }

  @Test
  @DisplayName("seal: no new steps, details or stacks; closing still applies; estimate is fixed")
  void seal() {
    StepBuffer steps = new StepBuffer(0, 10);
    int open = steps.start(StepType.SQL, 1, 0, 0, -1, ME);
    assertThat(steps.seal()).isTrue();
    assertThat(steps.seal()).isFalse();
    long fixed = steps.estimatedBytes();
    assertThat(steps.start(StepType.SQL, 2, 0, 0, -1, ME)).isEqualTo(-1);
    steps.detail(open, "late detail");
    steps.stack(open, new long[] {1, 2});
    assertThat(steps.detail(open)).isNull();
    assertThat(steps.stack(open)).isNull();
    steps.end(open, 0, false, 3, 50);
    assertThat(steps.elapsedNanos(open)).isEqualTo(50);
    assertThat(steps.rows(open)).isEqualTo(3);
    assertThat(steps.estimatedBytes()).isEqualTo(fixed);
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.totalSteps()).isEqualTo(1);
  }

  @Test
  @Tag("req:N-05")
  @DisplayName("lock-free: 8 threads record 120 steps each concurrently, every step kept once")
  void concurrentWriters() throws Exception {
    final StepBuffer steps = new StepBuffer(0, 1000);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(8);
    for (int t = 0; t < 8; t++) {
      final int base = t * 1000;
      new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < 120; i++) {
                      int seq =
                          steps.start(StepType.SQL, base + i, 0, i, -1, Thread.currentThread());
                      steps.detail(seq, "d" + i);
                      steps.end(seq, 0, false, 1, i + 5);
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
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(steps.size()).isEqualTo(960);
    java.util.Set<Long> names = new java.util.HashSet<Long>();
    for (int seq = 0; seq < steps.size(); seq++) {
      assertThat(steps.isClosed(seq)).isTrue();
      assertThat(steps.elapsedNanos(seq)).isEqualTo(5);
      names.add(steps.nameId(seq));
    }
    assertThat(names).hasSize(960);
  }
}
