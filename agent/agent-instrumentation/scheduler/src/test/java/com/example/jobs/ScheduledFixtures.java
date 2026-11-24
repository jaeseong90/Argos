/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Entry points of the {@code @Scheduled} tests with JDK-only signatures (the test classes must not
 * name a Spring type, see {@code SchedulerTestSupport}).
 */
public final class ScheduledFixtures {
  private ScheduledFixtures() {}

  /**
   * Starts a Spring context with {@link ScheduledJobsConfig}, waits for its one run on Spring's
   * scheduler thread and closes the context; returns {@code {target class, thread name}}.
   */
  public static String[] runOnceInSpringContext() throws InterruptedException {
    ScheduledJobsConfig.ran = new CountDownLatch(1);
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ScheduledJobsConfig.class);
    try {
      if (!ScheduledJobsConfig.ran.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("@Scheduled method did not run");
      }
    } finally {
      context.close();
    }
    Thread thread = ScheduledJobsConfig.thread;
    if (thread != null) {
      thread.join(TimeUnit.SECONDS.toMillis(5));
    }
    return new String[] {ScheduledJobsConfig.targetClass, thread == null ? null : thread.getName()};
  }

  /** Runs {@code PlainJobs#<method>} through a {@code ScheduledMethodRunnable} on this thread. */
  public static void runOnThisThread(String method) throws NoSuchMethodException {
    new ScheduledMethodRunnable(new PlainJobs(), method).run();
  }

  /** The exception the last {@code PlainJobs#fail} threw. */
  public static IllegalStateException lastFailure() {
    return PlainJobs.lastFailure;
  }
}
