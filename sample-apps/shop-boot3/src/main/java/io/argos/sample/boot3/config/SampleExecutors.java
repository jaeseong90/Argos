/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.config;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.async.NamedThreadFactory;
import io.argos.sample.common.async.SeedThreadPoolExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * The app's own executors, held in one bean that is not an {@code Executor} itself, so that Spring
 * Boot keeps its {@code applicationTaskExecutor} (virtual threads with SAMPLE_VT=true) for
 * {@code @Async} and MVC async requests.
 */
public final class SampleExecutors implements AutoCloseable {

  private final ScheduledExecutorService scheduler;
  private final ExecutorService custom;

  public SampleExecutors(SampleConfig config) {
    ScheduledThreadPoolExecutor timer =
        new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("sample-scheduler-", true));
    timer.setRemoveOnCancelPolicy(true);
    this.scheduler = timer;
    this.custom = config.customExecutor() ? new SeedThreadPoolExecutor(4) : null;
  }

  /** Completes DeferredResult and SseEmitter work (GET /api/async/deferred, /api/sse). */
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  /** The SAMPLE_CUSTOM_EXECUTOR executor ({@code seed-ARGOS_SEED-N} threads), or null when off. */
  public ExecutorService custom() {
    return custom;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    if (custom != null) {
      custom.shutdownNow();
    }
  }
}
