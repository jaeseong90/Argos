/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.trace.TestVirtualThreads;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reference cost of the slow-stack sampler's virtual-thread fallback (WO-19 acceptance "10 U-13 VT
 * getStackTrace 비용"; the U-13 of 10 §18 is another item, see the WO-19 report): {@code
 * Thread.getStackTrace()} of a parked virtual thread one by one, next to the batched {@code
 * ThreadMXBean.getThreadInfo(ids, 64)} of as many parked platform threads, both about 100 frames
 * deep. Prints one {@code [wo19-u13]} line; the numbers are references of the machine that ran the
 * test (a shared laptop: INVALID as a measurement), the assertion only checks that a stack came
 * back. Runs in {@code testJdk21} and {@code testJdk25}.
 */
@Tag("req:F-A19")
class VirtualThreadStackCostTest {
  static final int THREADS = 10;
  static final int DEPTH = 100;
  static final int ROUNDS = 50;

  @Test
  @DisplayName(
      "U-13 (WO-19) reference: getStackTrace of a parked virtual thread vs one batched"
          + " getThreadInfo of platform threads, 10 threads ~100 frames deep")
  void virtualThreadStackCost() throws Exception {
    assumeTrue(VirtualThreads.supported(), "virtual threads need JDK 21+ (testJdk21, testJdk25)");
    final CountDownLatch release = new CountDownLatch(1);
    List<Thread> virtual = new ArrayList<Thread>();
    List<Thread> platform = new ArrayList<Thread>();
    Runnable park =
        new Runnable() {
          @Override
          public void run() {
            SlowStackVirtualThreadTest.deep(DEPTH, release);
          }
        };
    try {
      for (int i = 0; i < THREADS; i++) {
        virtual.add(TestVirtualThreads.start("u13-vt-" + i, park));
        Thread thread = new Thread(park, "u13-pt-" + i);
        thread.setDaemon(true);
        thread.start();
        platform.add(thread);
      }
      waitParked(virtual);
      waitParked(platform);
      long[] ids = new long[THREADS];
      for (int i = 0; i < THREADS; i++) {
        ids[i] = platform.get(i).getId();
      }
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      long[] vtNanos = new long[ROUNDS];
      long[] ptNanos = new long[ROUNDS];
      int vtFrames = 0;
      int ptFrames = 0;
      for (int round = 0; round < ROUNDS; round++) {
        long start = System.nanoTime();
        for (Thread thread : virtual) {
          vtFrames = thread.getStackTrace().length;
        }
        vtNanos[round] = System.nanoTime() - start;
        start = System.nanoTime();
        ThreadInfo[] infos = bean.getThreadInfo(ids, 64);
        ptNanos[round] = System.nanoTime() - start;
        ptFrames = infos[0] == null ? 0 : infos[0].getStackTrace().length;
      }
      System.out.println(
          String.format(
              Locale.ROOT,
              "[wo19-u13] jdk=%s vt_getStackTrace_us_per_thread_median=%.1f (frames %d)"
                  + " platform_getThreadInfo_batch_of_%d_us_median=%.1f (frames %d)"
                  + " cycle_of_10_vt_us=%.1f budget_ms=%d (reference values, INVALID on a shared"
                  + " machine)",
              System.getProperty("java.specification.version"),
              median(vtNanos) / 1000.0 / THREADS,
              vtFrames,
              THREADS,
              median(ptNanos) / 1000.0,
              ptFrames,
              median(vtNanos) / 1000.0,
              SlowStackWatcher.BUDGET_MILLIS));
      assertThat(vtFrames).as("a parked virtual thread's stack").isGreaterThan(DEPTH);
      assertThat(ptFrames).isEqualTo(64);
    } finally {
      release.countDown();
      for (Thread thread : virtual) {
        thread.join(10_000);
      }
      for (Thread thread : platform) {
        thread.join(10_000);
      }
    }
  }

  private static void waitParked(List<Thread> threads) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    for (Thread thread : threads) {
      while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
    }
  }

  private static double median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }
}
