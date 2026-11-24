/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** A Quartz job that runs a Spring Batch step (10 §6.8: the step is a METHOD step of the job). */
public class BatchLaunchingJob implements Job {
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    try {
      BatchFixtures.runChunkStep("settlementJob", "prepareStep", 10, 5, false, -1);
    } catch (Exception e) {
      throw new JobExecutionException(e);
    } finally {
      QuartzFixtures.finished();
    }
  }
}
