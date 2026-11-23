/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The transaction's SQL counters from several threads at once (WO-15): with executor propagation,
 * the async branches of one request run their SQL on different threads, and every execution must
 * count in {@code sql_count} (a plain {@code ++} lost updates in the T-AG-052 agent suite).
 */
@Tag("req:F-A07")
@Tag("req:D-12")
class TraceContextCountersTest {
  static final int THREADS = 8;
  static final int PER_THREAD = 50_000;

  @Test
  @DisplayName(
      "T-AG-052k sql_count, sql_time and dbc_wait lose no update when 8 threads add at once")
  void concurrentSqlCounters() throws InterruptedException {
    final TraceContext context =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < THREADS; i++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int n = 0; n < PER_THREAD; n++) {
                  context.addSqlExecution(3L);
                  context.addSqlNanos(2L);
                  context.addDbcWait(1L);
                }
              });
      thread.start();
      threads.add(thread);
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    long total = (long) THREADS * PER_THREAD;
    assertThat(context.sqlCount).isEqualTo(total);
    assertThat(context.sqlNanos).isEqualTo(5L * total);
    assertThat(context.dbcNanos).isEqualTo(total);
  }

  @Test
  @DisplayName("T-AG-052k negative or zero times add nothing; the execution still counts")
  void negativeTimes() {
    TraceContext context =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    context.addSqlExecution(-5L);
    context.addSqlNanos(0L);
    context.addDbcWait(-1L);
    assertThat(context.sqlCount).isEqualTo(1);
    assertThat(context.sqlNanos).isZero();
    assertThat(context.dbcNanos).isZero();
  }
}
