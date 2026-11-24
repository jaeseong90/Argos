/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DetachedRunsTest {

  @Test
  void runsOnItsOwnPlainThreadAndReportsTheSummary() throws Exception {
    DetachedRuns runs = new DetachedRuns();
    AtomicReference<Thread> worker = new AtomicReference<>();
    long id =
        runs.start(
            "settlementJob",
            () -> {
              worker.set(Thread.currentThread());
              return Map.of("status", "COMPLETED", "readCount", 20000);
            });

    Map<String, Object> state = runs.describe(Long.toString(id), 10_000).orElseThrow();
    assertThat(state)
        .containsEntry("runId", id)
        .containsEntry("job", "settlementJob")
        .containsEntry("detached", true)
        .containsEntry("state", "DONE")
        .containsEntry("status", "COMPLETED")
        .containsEntry("readCount", 20000);
    assertThat(worker.get()).isNotSameAs(Thread.currentThread());
    assertThat(worker.get().getClass()).isEqualTo(Thread.class);
    assertThat(worker.get().getName()).isEqualTo(DetachedRuns.THREAD_PREFIX + id);
    assertThat(runs.describe("last", 0).orElseThrow()).containsEntry("runId", id);
  }

  @Test
  void reportsRunningAndFailedRuns() throws Exception {
    DetachedRuns runs = new DetachedRuns();
    CountDownLatch release = new CountDownLatch(1);
    long slow =
        runs.start(
            "settlementJob",
            () -> {
              release.await(10, TimeUnit.SECONDS);
              return Map.of("status", "COMPLETED");
            });
    assertThat(runs.describe(Long.toString(slow), 0).orElseThrow())
        .containsEntry("state", "RUNNING");
    release.countDown();
    assertThat(runs.describe(Long.toString(slow), 10_000).orElseThrow())
        .containsEntry("state", "DONE");

    long failing =
        runs.start(
            "settlementJob",
            () -> {
              throw new IllegalStateException("broken");
            });
    assertThat(runs.describe(Long.toString(failing), 10_000).orElseThrow())
        .containsEntry("state", "FAILED")
        .containsEntry("failure", "java.lang.IllegalStateException: broken");
  }

  @Test
  void keepsTheNewestRunsOnly() throws Exception {
    DetachedRuns runs = new DetachedRuns();
    long first = runs.start("settlementJob", Map::of);
    long last = first;
    for (int i = 0; i < DetachedRuns.MAX_RUNS; i++) {
      last = runs.start("settlementJob", Map::of);
    }
    assertThat(runs.describe(Long.toString(first), 0)).isEmpty();
    assertThat(runs.describe(Long.toString(last), 10_000)).isPresent();
    assertThat(runs.describe("999999", 0)).isEmpty();
    assertThat(runs.describe("not-a-number", 0)).isEmpty();
  }
}
