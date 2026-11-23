/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

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
 * The threads of the {@link CommandRouter} (12 §10 {@code argos-command}, one thread): the worker
 * that runs sends, results, expiry and retention in order (queue bounded at {@value #QUEUE} tasks;
 * a full queue drops the task, which the router counts or retries on the next tick), and the timer
 * that queues the expiry check every second and the retention every hour.
 */
public final class CommandRouterLifecycle implements AutoCloseable {

  /** Tasks the worker queues at most (result chunks are at most 256 KiB each). */
  static final int QUEUE = 64;

  /** Seconds between expiry checks. */
  static final long TICK_SECONDS = 1;

  /** Minutes between retention runs (the first one after ten minutes). */
  static final long RETENTION_MINUTES = 60;

  private static final Logger LOG = LoggerFactory.getLogger(CommandRouterLifecycle.class);

  private final ThreadPoolExecutor worker;
  private final ScheduledExecutorService timer;
  private final AtomicReference<CommandRouter> router = new AtomicReference<>();

  public CommandRouterLifecycle() {
    this.worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            r -> {
              Thread t = new Thread(r, "argos-command");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    this.timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-command-timer");
              t.setDaemon(true);
              return t;
            });
  }

  /** The worker the router runs its tasks on. */
  public ThreadPoolExecutor worker() {
    return worker;
  }

  /** Starts the periodic tasks of {@code commands}. */
  public void start(CommandRouter commands) {
    if (!router.compareAndSet(null, commands)) {
      return;
    }
    var unused =
        timer.scheduleWithFixedDelay(
            () -> submit(commands::tick), TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    var unusedRetention =
        timer.scheduleWithFixedDelay(
            () -> submit(commands::retention), 10, RETENTION_MINUTES, TimeUnit.MINUTES);
  }

  private void submit(Runnable task) {
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } catch (RuntimeException e) {
              LOG.warn("A command task failed: {}", e.getClass().getSimpleName());
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
