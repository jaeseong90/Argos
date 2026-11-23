/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The threads of the {@link ConfigChangeService} ({@code argos-config}, one thread): the worker
 * that stores agents' results, session ends and Hello deliveries in order (queue bounded at {@value
 * #QUEUE} tasks: a configuration push to every instance of a large app answers at once; a full
 * queue drops the task, counted, and the push is then {@code offline} after the result timeout),
 * and the timer that queues the result timeout check every second.
 */
public final class ConfigChangeLifecycle implements AutoCloseable {

  /** Tasks the worker queues at most. */
  static final int QUEUE = 4096;

  /** Seconds between result timeout checks. */
  static final long TICK_SECONDS = 1;

  private static final Logger LOG = LoggerFactory.getLogger(ConfigChangeLifecycle.class);

  private final ThreadPoolExecutor worker;
  private final ScheduledExecutorService timer;
  private final AtomicReference<ConfigChangeService> service = new AtomicReference<>();

  public ConfigChangeLifecycle() {
    this.worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            r -> {
              Thread t = new Thread(r, "argos-config");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    this.timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-config-timer");
              t.setDaemon(true);
              return t;
            });
  }

  /** The worker the service runs its tasks on. */
  public ThreadPoolExecutor worker() {
    return worker;
  }

  /** Resumes the changes still applying and starts the result timeout of {@code changes}. */
  public void start(ConfigChangeService changes) {
    if (!service.compareAndSet(null, changes)) {
      return;
    }
    submit(changes::resume);
    var unused =
        timer.scheduleWithFixedDelay(
            () -> submit(changes::tick), TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
  }

  private void submit(Runnable task) {
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } catch (RuntimeException e) {
              LOG.warn("A configuration change task failed: {}", e.getClass().getName());
            }
          });
    } catch (RejectedExecutionException e) {
      // Busy: the next period runs it.
    }
  }

  @Override
  public void close() {
    timer.shutdownNow();
    worker.shutdownNow();
  }
}
