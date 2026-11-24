/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * An application's {@code @Scheduled} method on a {@code @Configuration} class: Spring runs it on a
 * CGLIB subclass ({@code $$EnhancerBySpringCGLIB$$} in 5.3, {@code $$SpringCGLIB$$} in 6+), so the
 * service name must name this class (WO-18 assumption A-1). Runs once at start (one-hour delay
 * before the second run).
 */
@Configuration
@EnableScheduling
public class ScheduledJobsConfig {
  static volatile CountDownLatch ran = new CountDownLatch(1);
  static volatile String targetClass;
  static volatile Thread thread;

  /** A bean method: Spring 6.2+ enhances (subclasses) only configuration classes that have one. */
  @Bean
  public AtomicInteger statsRuns() {
    return new AtomicInteger();
  }

  @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
  public void refreshStats() {
    targetClass = getClass().getName();
    thread = Thread.currentThread();
    ran.countDown();
  }
}
