/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;

/**
 * Spring Batch step names and outcomes (10 §6.8), read through the public getters of {@code
 * StepExecution} that Spring Batch 4.3 to 5.2 share. {@code AbstractStep.execute} records a failure
 * on the step execution (status FAILED, failure exceptions) instead of throwing it, so the exit
 * reads the outcome from there.
 */
public final class BatchStepHelper {
  /** Error class of a FAILED step execution without a failure exception. */
  static final String FAILED_STATUS_CLASS = "org.springframework.batch.core.BatchStatus";

  private BatchStepHelper() {}

  /** {@code BATCH <jobName>.<stepName>} ({@code BATCH <stepName>} without a job), or null. */
  public static String serviceName(Object stepExecution) {
    if (stepExecution == null) {
      return null;
    }
    StepExecution step = (StepExecution) stepExecution;
    String stepName = step.getStepName();
    if (stepName == null) {
      return null;
    }
    String jobName = null;
    JobExecution job = step.getJobExecution();
    if (job != null) {
      JobInstance instance = job.getJobInstance();
      if (instance != null) {
        jobName = instance.getJobName();
      }
    }
    return jobName == null
        ? SchedulerHelper.PREFIX + stepName
        : SchedulerHelper.PREFIX + jobName + "." + stepName;
  }

  /** The first failure exception of {@code stepExecution}, or null. */
  public static Throwable failure(Object stepExecution) {
    if (stepExecution == null) {
      return null;
    }
    List<Throwable> failures = ((StepExecution) stepExecution).getFailureExceptions();
    if (failures == null || failures.isEmpty()) {
      return null;
    }
    return failures.get(0);
  }

  /** True when the step execution ended FAILED. */
  public static boolean failed(Object stepExecution) {
    return stepExecution != null
        && ((StepExecution) stepExecution).getStatus() == BatchStatus.FAILED;
  }
}
