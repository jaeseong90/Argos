/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduledStatsTest {

  @Test
  void awaitReturnsOnceEveryKindHasRun() throws Exception {
    ScheduledStats stats = new ScheduledStats();
    Thread runner =
        new Thread(
            () -> {
              stats.fixedRateRan();
              stats.cronRan();
              stats.longStarted();
            });
    runner.start();
    Map<String, Object> counters = stats.await(10_000);
    runner.join();

    assertThat(counters)
        .containsEntry("fixedRate", 1L)
        .containsEntry("cron", 1L)
        .containsEntry("longRunningStarted", 1L)
        .containsEntry("longRunningActive", true)
        .containsEntry("lastRunVirtual", false);
  }

  @Test
  void awaitGivesUpAfterTheTimeoutWithTheCurrentCounters() throws Exception {
    ScheduledStats stats = new ScheduledStats();
    stats.fixedRateRan();

    long start = System.nanoTime();
    Map<String, Object> counters = stats.await(200);

    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(150_000_000L);
    assertThat(counters).containsEntry("fixedRate", 1L).containsEntry("cron", 0L);
  }

  @Test
  void longRunningCompletionIsRecorded() {
    ScheduledStats stats = new ScheduledStats();
    stats.longStarted();
    stats.longFinished(40, true);
    stats.longStarted();
    stats.longFinished(3, false);

    assertThat(stats.snapshot())
        .containsEntry("longRunningStarted", 2L)
        .containsEntry("longRunningCompleted", 1L)
        .containsEntry("longRunningActive", false)
        .containsEntry("lastLongRunningSlices", 3L);
  }
}
