/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.web;

import io.argos.sample.batch.jobs.BatchJobs;
import io.argos.sample.batch.quartz.QuartzRunTracker;
import io.argos.sample.batch.scheduled.ScheduledStats;
import io.argos.sample.common.catalog.BatchCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triggers and state of the batch entry points (18 §4.2 last row): POST /batch/run/{job}
 * (Spring Batch, synchronous), POST /batch/quartz/{job} (fires a Quartz job now and waits for it),
 * GET /batch/scheduled (counters of the {@code @Scheduled} methods), GET /batch/jobs.
 */
@RestController
@RequestMapping("/batch")
public class BatchController {

  private static final long QUARTZ_WAIT_MS = 30_000;

  private final BatchJobs batchJobs;
  private final Scheduler scheduler;
  private final QuartzRunTracker quartzRuns;
  private final ScheduledStats scheduledStats;
  private final JdbcTemplate jdbc;

  public BatchController(
      BatchJobs batchJobs,
      Scheduler scheduler,
      QuartzRunTracker quartzRuns,
      ScheduledStats scheduledStats,
      JdbcTemplate jdbc) {
    this.batchJobs = batchJobs;
    this.scheduler = scheduler;
    this.quartzRuns = quartzRuns;
    this.scheduledStats = scheduledStats;
    this.jdbc = jdbc;
  }

  /** Runs a Spring Batch job to its end: 200 COMPLETED, 500 otherwise, 404 unknown job. */
  @PostMapping("/run/{job}")
  public ResponseEntity<Map<String, Object>> run(@PathVariable("job") String job)
      throws JobExecutionException {
    Optional<Map<String, Object>> summary = batchJobs.run(job);
    if (summary.isEmpty()) {
      return notFound("Unknown Spring Batch job", batchJobs.names());
    }
    return ResponseEntity.status(
            BatchJobs.completed(summary.get()) ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
        .body(summary.get());
  }

  /**
   * Fires a Quartz job now and waits up to 30 s for it to finish. The trigger is stored outside a
   * transaction (auto-commit), so the scheduler thread sees it as soon as it is signalled.
   */
  @PostMapping("/quartz/{job}")
  public ResponseEntity<Map<String, Object>> fireQuartz(@PathVariable("job") String job)
      throws SchedulerException, InterruptedException {
    JobKey key = new JobKey(job, BatchCatalog.QUARTZ_GROUP);
    if (!scheduler.checkExists(key)) {
      return notFound("Unknown Quartz job", quartzJobNames());
    }
    long before = quartzRuns.executions(job);
    long start = System.nanoTime();
    scheduler.triggerJob(key);
    boolean completed = quartzRuns.awaitMoreThan(job, before, QUARTZ_WAIT_MS);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("job", job);
    result.put("fired", true);
    result.put("completed", completed);
    result.put("waitedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    result.putAll(quartzRuns.describe(job));
    return ResponseEntity.status(completed ? HttpStatus.OK : HttpStatus.GATEWAY_TIMEOUT)
        .body(result);
  }

  /** Counters of the scheduled methods; {@code awaitMs} waits until each one has run once. */
  @GetMapping("/scheduled")
  public Map<String, Object> scheduled(
      @RequestParam(name = "awaitMs", defaultValue = "0") long awaitMs)
      throws InterruptedException {
    if (awaitMs < 0 || awaitMs > BatchCatalog.MAX_AWAIT_MS) {
      throw new IllegalArgumentException(
          "awaitMs must be between 0 and " + BatchCatalog.MAX_AWAIT_MS);
    }
    return awaitMs == 0 ? scheduledStats.snapshot() : scheduledStats.await(awaitMs);
  }

  /** Spring Batch jobs, Quartz jobs with their next fire times, scheduled methods. */
  @GetMapping("/jobs")
  public Map<String, Object> jobs() throws SchedulerException {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("springBatch", batchJobs.names());
    List<Map<String, Object>> quartz = new ArrayList<>();
    for (JobKey key :
        scheduler.getJobKeys(GroupMatcher.jobGroupEquals(BatchCatalog.QUARTZ_GROUP))) {
      JobDetail detail = scheduler.getJobDetail(key);
      Map<String, Object> job = new LinkedHashMap<>();
      job.put("name", key.getName());
      job.put("class", detail.getJobClass().getSimpleName());
      job.put("description", detail.getDescription());
      List<String> next = new ArrayList<>();
      for (Trigger trigger : scheduler.getTriggersOfJob(key)) {
        next.add(
            trigger.getNextFireTime() == null
                ? null
                : trigger.getNextFireTime().toInstant().toString());
      }
      job.put("nextFireTimes", next);
      job.putAll(quartzRuns.describe(key.getName()));
      quartz.add(job);
    }
    result.put("quartz", quartz);
    result.put(
        "scheduled",
        List.of(
            "ScheduledJobs#refreshOrderStats (fixed rate)",
            "ScheduledJobs#rollupTopCustomers (cron)",
            "ScheduledJobs#settlementScan (long running)"));
    // Quartz wrote these rows through its JDBC job store; reading them through the app's own
    // DataSource proves that the job store and the business data share one database.
    result.put(
        "quartzJobsInSharedDataSource",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_JOB_DETAILS WHERE SCHED_NAME = ? AND JOB_GROUP = ?",
            Long.class,
            scheduler.getSchedulerName(),
            BatchCatalog.QUARTZ_GROUP));
    return result;
  }

  private List<String> quartzJobNames() throws SchedulerException {
    List<String> names = new ArrayList<>();
    for (JobKey key :
        scheduler.getJobKeys(GroupMatcher.jobGroupEquals(BatchCatalog.QUARTZ_GROUP))) {
      names.add(key.getName());
    }
    return names;
  }

  private static ResponseEntity<Map<String, Object>> notFound(String error, List<String> known) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("known", known);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }
}
