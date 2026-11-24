/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import java.util.concurrent.atomic.AtomicReference;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** An application's Quartz job; fails with a {@code JobExecutionException} when asked to. */
public class ReportJob implements Job {
  /** The exception the last failing run threw (identity check). */
  static final AtomicReference<JobExecutionException> lastFailure =
      new AtomicReference<JobExecutionException>();

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    try {
      if (Boolean.TRUE.equals(context.getJobDetail().getJobDataMap().get("fail"))) {
        JobExecutionException failure = new JobExecutionException("report job failed");
        lastFailure.set(failure);
        throw failure;
      }
    } finally {
      QuartzFixtures.finished();
    }
  }
}
