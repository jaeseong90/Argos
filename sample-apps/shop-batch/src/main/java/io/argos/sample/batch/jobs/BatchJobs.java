/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import io.argos.sample.common.catalog.BatchCatalog;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Starts Spring Batch jobs by name ({@code POST /batch/run/{job}}) and the batch name XSS seed job,
 * synchronously (Spring Boot's JobLauncher runs the job in the calling thread), and summarizes the
 * execution. Every run gets a unique {@code run.id} parameter, so a job can be started any number
 * of times.
 */
@Service
public class BatchJobs {

  private final JobLauncher launcher;
  private final JobRepository jobRepository;
  private final PlatformTransactionManager transactionManager;
  private final JdbcTemplate jdbc;
  private final Map<String, Job> jobs = new TreeMap<>();

  public BatchJobs(
      JobLauncher launcher,
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      List<Job> jobs) {
    this.launcher = launcher;
    this.jobRepository = jobRepository;
    this.transactionManager = transactionManager;
    this.jdbc = jdbc;
    for (Job job : jobs) {
      this.jobs.put(job.getName(), job);
    }
  }

  /** Names of the jobs that {@link #run} accepts. */
  public List<String> names() {
    return List.copyOf(jobs.keySet());
  }

  /** Runs job {@code name} to its end; empty when there is no such job. */
  public Optional<Map<String, Object>> run(String name) throws JobExecutionException {
    Job job = jobs.get(name);
    if (job == null) {
      return Optional.empty();
    }
    JobExecution execution = launcher.run(job, uniqueParameters().toJobParameters());
    Map<String, Object> summary = summary(execution);
    if (name.equals(BatchCatalog.SETTLEMENT_JOB)) {
      // The chunk step's own counts at the top level: its commit count follows from the commit
      // interval the step really ran with (items / interval full chunks plus the commit of the
      // final empty chunk), so the smoke can check the interval without trusting a constant.
      for (StepExecution step : execution.getStepExecutions()) {
        if (step.getStepName().equals(BatchCatalog.CHUNK_STEP)) {
          summary.put("chunkStepReadCount", step.getReadCount());
          summary.put("chunkStepCommitCount", step.getCommitCount());
        }
      }
    }
    return Optional.of(summary);
  }

  /**
   * Runs a one-step job whose name is {@code payload} (18 §4.3: XSS seed in a batch job name); the
   * Tasklet binds the payload into a query as well.
   */
  public Map<String, Object> runSeedJob(String payload) throws JobExecutionException {
    Job job =
        new JobBuilder(payload, jobRepository)
            .start(
                new StepBuilder("seedStep", jobRepository)
                    .tasklet(
                        (contribution, chunkContext) -> {
                          jdbc.queryForObject(
                              "SELECT COUNT(*) FROM orders WHERE status = ?", Long.class, payload);
                          return RepeatStatus.FINISHED;
                        },
                        transactionManager)
                    .build())
            .build();
    JobParameters parameters = uniqueParameters().addString("seed", payload).toJobParameters();
    return summary(launcher.run(job, parameters));
  }

  private static JobParametersBuilder uniqueParameters() {
    return new JobParametersBuilder().addString("run.id", UUID.randomUUID().toString());
  }

  private Map<String, Object> summary(JobExecution execution) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("job", execution.getJobInstance().getJobName());
    result.put("executionId", execution.getId());
    result.put("status", execution.getStatus().name());
    result.put("exitCode", execution.getExitStatus().getExitCode());
    result.put("startTime", text(execution.getStartTime()));
    result.put("endTime", text(execution.getEndTime()));
    if (execution.getStartTime() != null && execution.getEndTime() != null) {
      result.put(
          "durationMs",
          Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis());
    }
    long read = 0;
    long write = 0;
    long commits = 0;
    List<Map<String, Object>> steps = new ArrayList<>();
    for (StepExecution step : execution.getStepExecutions()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("name", step.getStepName());
      s.put("status", step.getStatus().name());
      s.put("readCount", step.getReadCount());
      s.put("writeCount", step.getWriteCount());
      s.put("filterCount", step.getFilterCount());
      s.put("commitCount", step.getCommitCount());
      s.put("rollbackCount", step.getRollbackCount());
      steps.add(s);
      read += step.getReadCount();
      write += step.getWriteCount();
      commits += step.getCommitCount();
    }
    result.put("readCount", read);
    result.put("writeCount", write);
    result.put("commitCount", commits);
    result.put("steps", steps);
    // The JobRepository wrote these rows; reading them through the app's own DataSource proves that
    // the batch metadata and the business data share one database.
    result.put(
        "stepsInSharedDataSource",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?",
            Long.class,
            execution.getId()));
    List<String> failures = new ArrayList<>();
    for (Throwable failure : execution.getAllFailureExceptions()) {
      failures.add(failure.getClass().getName() + ": " + failure.getMessage());
    }
    if (!failures.isEmpty()) {
      result.put("failures", failures);
    }
    return result;
  }

  /** Whether an execution summary reports success. */
  public static boolean completed(Map<String, Object> summary) {
    return BatchStatus.COMPLETED.name().equals(summary.get("status"));
  }

  private static String text(LocalDateTime time) {
    return time == null ? null : time.toString();
  }
}
