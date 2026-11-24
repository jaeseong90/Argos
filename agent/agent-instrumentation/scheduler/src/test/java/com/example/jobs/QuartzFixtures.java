/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Entry points of the Quartz tests with JDK-only signatures: a real scheduler (RAM job store, one
 * worker thread) fires the job once.
 */
public final class QuartzFixtures {
  private static final AtomicInteger SCHEDULERS = new AtomicInteger();
  private static volatile CountDownLatch finished = new CountDownLatch(1);

  private QuartzFixtures() {}

  static void finished() {
    finished.countDown();
  }

  /**
   * Fires job {@code group.name} of class {@code report}, {@code delegating} or {@code
   * batch-launching} once on a real scheduler and waits until the scheduler shut down after it.
   */
  public static void fireOnce(String kind, String group, String name, boolean fail)
      throws SchedulerException, InterruptedException {
    Properties properties = new Properties();
    properties.setProperty(
        "org.quartz.scheduler.instanceName", "argos-test-" + SCHEDULERS.incrementAndGet());
    properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
    finished = new CountDownLatch(1);
    Scheduler scheduler = new StdSchedulerFactory(properties).getScheduler();
    try {
      JobDetail job =
          JobBuilder.newJob(jobClass(kind))
              .withIdentity(name, group)
              .usingJobData("fail", Boolean.valueOf(fail))
              .build();
      scheduler.scheduleJob(job, TriggerBuilder.newTrigger().startNow().build());
      scheduler.start();
      if (!finished.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Quartz job did not run");
      }
    } finally {
      // Waits for the running job, so its transaction has ended when this returns.
      scheduler.shutdown(true);
    }
  }

  /** Runs {@code ReportJob.execute} on this thread with a context for job {@code group.name}. */
  public static void executeOnThisThread(String group, String name) throws JobExecutionException {
    final JobDetail detail = JobBuilder.newJob(ReportJob.class).withIdentity(name, group).build();
    JobExecutionContext context =
        (JobExecutionContext)
            Proxy.newProxyInstance(
                QuartzFixtures.class.getClassLoader(),
                new Class<?>[] {JobExecutionContext.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args) {
                    return method.getName().equals("getJobDetail") ? detail : null;
                  }
                });
    new ReportJob().execute(context);
  }

  /** The exception the last failing {@code ReportJob} threw. */
  public static Exception lastFailure() {
    return ReportJob.lastFailure.get();
  }

  private static Class<? extends Job> jobClass(String kind) {
    if (kind.equals("report")) {
      return ReportJob.class;
    }
    if (kind.equals("delegating")) {
      return DelegatingJob.class;
    }
    if (kind.equals("batch-launching")) {
      return BatchLaunchingJob.class;
    }
    throw new IllegalArgumentException(kind);
  }
}
