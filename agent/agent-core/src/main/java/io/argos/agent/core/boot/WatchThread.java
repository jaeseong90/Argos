/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.log.AgentLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The {@code argos-watch} thread (10 §14 thread budget): runs a fixed set of periodic house-keeping
 * tasks on a one-second tick. WO-02 tasks: writing the transformed-class list, expunging collected
 * keys of the fallback context stores. WO-12 adds {@code transaction.max-duration-ms} ({@code
 * core.watch.MaxDurationEnforcer}, registered by {@code AgentStarter}); slow stacks and the
 * overhead monitor (10 §13.1) join this thread with WO-19.
 */
final class WatchThread {
  private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final List<Runnable> tasks = new ArrayList<Runnable>();
  private final List<Long> periods = new ArrayList<Long>();
  private final List<long[]> lastRun = new ArrayList<long[]>();
  private volatile boolean running;
  private volatile Thread thread;

  /** Registers a task run every {@code periodSeconds} (before {@link #start}). */
  synchronized void every(long periodSeconds, Runnable task) {
    tasks.add(task);
    periods.add(TimeUnit.SECONDS.toNanos(Math.max(1, periodSeconds)));
    lastRun.add(new long[] {System.nanoTime()});
  }

  synchronized void start() {
    if (thread != null) {
      return;
    }
    running = true;
    Thread worker =
        ArgosThread.factory("argos-watch")
            .newThread(
                new Runnable() {
                  @Override
                  public void run() {
                    loop();
                  }
                });
    thread = worker;
    worker.start();
  }

  private void loop() {
    while (running) {
      LockSupport.parkNanos(TICK_NANOS);
      runDue(false);
    }
  }

  /** Runs every due task ({@code all}: every task regardless of its period). */
  synchronized void runDue(boolean all) {
    long now = System.nanoTime();
    for (int i = 0; i < tasks.size(); i++) {
      long[] last = lastRun.get(i);
      if (all || now - last[0] >= periods.get(i)) {
        last[0] = now;
        try {
          tasks.get(i).run();
        } catch (Throwable t) {
          AgentLogger.warnOnce("watch-task-" + i, "periodic agent task failed: {}", t.toString());
        }
      }
    }
  }

  /** Stops the thread and runs every task once more (shutdown hook). */
  void stop() {
    running = false;
    Thread worker = thread;
    if (worker != null) {
      LockSupport.unpark(worker);
    }
    runDue(true);
  }
}
