/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * A job that delegates to another {@code Job.execute} with the same context (the shape of wrapper
 * jobs and of overrides that call {@code super.execute}): still one transaction.
 */
public class DelegatingJob implements Job {
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    new ReportJob().execute(context);
  }
}
