/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.quartz;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.springframework.stereotype.Component;

/**
 * Global Quartz job listener: counts finished executions per job name so that POST
 * /batch/quartz/{job} can wait for the execution it fired. Bounded: one entry per Quartz job.
 */
@Component
public class QuartzRunTracker implements JobListener {

  private final Object lock = new Object();
  private final Map<String, Long> executions = new HashMap<>();
  private final Map<String, String> lastErrors = new HashMap<>();
  private final Map<String, Long> lastRunMs = new HashMap<>();
  private final Map<String, Object> lastResults = new HashMap<>();

  @Override
  public String getName() {
    return "shop-batch-run-tracker";
  }

  @Override
  public void jobToBeExecuted(JobExecutionContext context) {}

  @Override
  public void jobExecutionVetoed(JobExecutionContext context) {}

  @Override
  public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
    String job = context.getJobDetail().getKey().getName();
    synchronized (lock) {
      executions.merge(job, 1L, Long::sum);
      lastRunMs.put(job, context.getJobRunTime());
      if (failure == null) {
        lastErrors.remove(job);
      } else {
        lastErrors.put(job, String.valueOf(failure.getMessage()));
      }
      if (context.getResult() == null) {
        lastResults.remove(job);
      } else {
        lastResults.put(job, context.getResult());
      }
      lock.notifyAll();
    }
  }

  /** Finished executions of {@code job} since startup. */
  public long executions(String job) {
    synchronized (lock) {
      return executions.getOrDefault(job, 0L);
    }
  }

  /**
   * Waits up to {@code timeoutMs} until {@code job} has finished more than {@code before} times.
   */
  public boolean awaitMoreThan(String job, long before, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    synchronized (lock) {
      while (executions.getOrDefault(job, 0L) <= before) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0) {
          return false;
        }
        lock.wait(remainingMs);
      }
      return true;
    }
  }

  /** Executions, last run time, last result and last error of {@code job}. */
  public Map<String, Object> describe(String job) {
    synchronized (lock) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("executions", executions.getOrDefault(job, 0L));
      result.put("lastRunMs", lastRunMs.get(job));
      result.put("lastResult", lastResults.get(job));
      result.put("lastError", lastErrors.get(job));
      return result;
    }
  }
}
