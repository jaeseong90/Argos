/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ThreadNames (10 §8, C-12) and VirtualThreads (F-A30) on the Java 8 test JVM. */
@Tag("req:F-A30")
class ThreadNamesTest {

  @Test
  @DisplayName(
      "T-AG-054 normalize: trailing -digits (tomcat-handler-1234), UUIDs and hex runs >= 8 are"
          + " removed")
  void normalize() {
    assertThat(ThreadNames.normalize("tomcat-handler-1234")).isEqualTo("tomcat-handler-");
    assertThat(ThreadNames.normalize("http-nio-8080-exec-12")).isEqualTo("http-nio-8080-exec-");
    assertThat(ThreadNames.normalize("pool-3-thread-7")).isEqualTo("pool-3-thread-");
    assertThat(ThreadNames.normalize("ForkJoinPool.commonPool-worker-3"))
        .isEqualTo("ForkJoinPool.commonPool-worker-");
    assertThat(ThreadNames.normalize("job-123e4567-e89b-12d3-a456-426614174000-run"))
        .isEqualTo("job--run");
    assertThat(ThreadNames.normalize("worker-deadbeefcafe")).isEqualTo("worker-");
    assertThat(ThreadNames.normalize("worker-0x1f2e3d4c5b")).isEqualTo("worker-0x1f2e3d4c5b");
    assertThat(ThreadNames.normalize("batch 20261103 step")).isEqualTo("batch  step");
    assertThat(ThreadNames.normalize("main")).isEqualTo("main");
    assertThat(ThreadNames.normalize("12345")).isEqualTo("12345");
    assertThat(ThreadNames.normalize("")).isEmpty();
    assertThat(ThreadNames.normalize(null)).isEmpty();
    // Letters around a hex run keep it (not a separate token).
    assertThat(ThreadNames.normalize("Consumerabcdef12x")).isEqualTo("Consumerabcdef12x");
    StringBuilder longName = new StringBuilder("worker-");
    for (int i = 0; i < 1000; i++) {
      longName.append('n');
    }
    assertThat(ThreadNames.normalize(longName.toString())).hasSize(ThreadNames.MAX_NAME_CHARS);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-054 the name dictionary is capped at 1,000: later names become _other_ and are"
          + " counted")
  void cap() {
    ThreadNames names = new ThreadNames(ThreadNames.DEFAULT_MAX);
    for (int i = 0; i < ThreadNames.DEFAULT_MAX; i++) {
      assertThat(names.admit("pool-" + i + "-thread-")).isEqualTo("pool-" + i + "-thread-");
    }
    assertThat(names.admit("pool-0-thread-")).isEqualTo("pool-0-thread-");
    assertThat(names.admit("one-more")).isEqualTo(ThreadNames.OTHER);
    assertThat(names.admit("two-more")).isEqualTo(ThreadNames.OTHER);
    assertThat(names.size()).isEqualTo(ThreadNames.DEFAULT_MAX);
    assertThat(names.overflowCount()).isEqualTo(2);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-054 the cap holds under concurrent first uses (8 threads, 2,000 distinct names each):"
          + " exactly 1,000 names kept, every other new name counted once per call")
  void capUnderConcurrency() throws Exception {
    final ThreadNames names = new ThreadNames(ThreadNames.DEFAULT_MAX);
    final int threads = 8;
    final int perThread = 2_000;
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicLong others = new AtomicLong();
    Thread[] workers = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      final int id = t;
      workers[t] =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < perThread; i++) {
                  if (ThreadNames.OTHER.equals(names.admit("w" + id + "-n" + i + "x"))) {
                    others.incrementAndGet();
                  }
                }
              });
      workers[t].start();
    }
    start.countDown();
    for (Thread worker : workers) {
      worker.join(30_000);
      assertThat(worker.isAlive()).isFalse();
    }
    assertThat(names.size()).isEqualTo(ThreadNames.DEFAULT_MAX);
    assertThat(names.overflowCount()).isEqualTo(others.get());
    assertThat(others.get()).isEqualTo((long) threads * perThread - ThreadNames.DEFAULT_MAX);
  }

  @Test
  @DisplayName("VirtualThreads: no isVirtual before JDK 21, so every thread is a platform thread")
  void virtualThreadsOnJava8() {
    boolean java21 =
        !System.getProperty("java.specification.version").startsWith("1.")
            && Integer.parseInt(System.getProperty("java.specification.version")) >= 21;
    assertThat(VirtualThreads.supported()).isEqualTo(java21);
    assertThat(VirtualThreads.isVirtual(Thread.currentThread())).isFalse();
    assertThat(VirtualThreads.isVirtual(null)).isFalse();
  }
}
