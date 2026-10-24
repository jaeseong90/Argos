/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.quartz;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

class QuartzRunTrackerTest {

  /** A finished execution of {@code job} as Quartz hands it to a job listener. */
  private static JobExecutionContext finished(String job, Object result) {
    JobDetail detail = JobBuilder.newJob(ReportCleanupJob.class).withIdentity(job, "g").build();
    return (JobExecutionContext)
        Proxy.newProxyInstance(
            QuartzRunTrackerTest.class.getClassLoader(),
            new Class<?>[] {JobExecutionContext.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getJobDetail" -> detail;
                  case "getJobRunTime" -> 7L;
                  case "getResult" -> result;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  @Test
  void awaitSeesAnExecutionThatFinishesLater() throws Exception {
    QuartzRunTracker tracker = new QuartzRunTracker();
    long before = tracker.executions("report");
    Thread quartz =
        new Thread(
            () -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              tracker.jobWasExecuted(finished("report", Map.of("orders", 1000)), null);
            });
    quartz.start();

    boolean done = tracker.awaitMoreThan("report", before, 10_000);
    quartz.join();

    assertThat(done).isTrue();
    assertThat(tracker.describe("report"))
        .containsEntry("executions", 1L)
        .containsEntry("lastRunMs", 7L)
        .containsEntry("lastResult", Map.of("orders", 1000))
        .containsEntry("lastError", null);
  }

  @Test
  void awaitTimesOutWithoutAnExecution() throws Exception {
    QuartzRunTracker tracker = new QuartzRunTracker();

    assertThat(tracker.awaitMoreThan("cleanup", 0, 100)).isFalse();
  }

  @Test
  void aFailureIsKeptUntilTheNextSuccess() {
    QuartzRunTracker tracker = new QuartzRunTracker();
    tracker.jobWasExecuted(finished("cleanup", null), new JobExecutionException("db down"));

    assertThat(tracker.describe("cleanup")).containsEntry("lastError", "db down");

    tracker.jobWasExecuted(finished("cleanup", Map.of("deleted", 0)), null);

    assertThat(tracker.describe("cleanup"))
        .containsEntry("executions", 2L)
        .containsEntry("lastError", null);
  }
}
