/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Transaction;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The {@code argos-sender} thread (10 §10.2, §14): creates the exporter off the premain thread,
 * then drains {@link TransactionQueue} in batches ({@code sender.batch-size}, 500) at least every
 * {@code sender.flush-interval-ms} (100 ms). At shutdown, what is left is exported for at most the
 * given time (10 §10.2 "종료 훅").
 */
public final class ExportWorker {
  private final TransactionQueue queue;
  private final ExporterContext context;
  private final File logDirectory;
  private final int batchSize;
  private final long flushIntervalNanos;
  private final AtomicLong exported = new AtomicLong();
  private final AtomicLong failedBatches = new AtomicLong();

  private volatile Exporter exporter;
  private volatile boolean running;
  private volatile Thread thread;

  public ExportWorker(
      TransactionQueue queue,
      ExporterContext context,
      File logDirectory,
      int batchSize,
      long flushIntervalMillis) {
    this.queue = queue;
    this.context = context;
    this.logDirectory = logDirectory;
    this.batchSize = Math.max(1, batchSize);
    this.flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, flushIntervalMillis));
  }

  /** Starts the sender thread (P7: asynchronous, premain does not wait). */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    running = true;
    Thread worker =
        ArgosThread.factory("argos-sender")
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
    Exporter created = Exporters.create(context, logDirectory);
    exporter = created;
    AgentLogger.info("exporter: {}", created.describe());
    List<Transaction> batch = new ArrayList<Transaction>(batchSize);
    while (running) {
      try {
        int moved = queue.drainTo(batch, batchSize);
        if (moved > 0) {
          export(created, batch);
          batch.clear();
          if (moved == batchSize) {
            continue;
          }
        }
        created.flush();
      } catch (Throwable t) {
        AgentLogger.warn("exporter flush failed: {}", t.toString());
      }
      LockSupport.parkNanos(flushIntervalNanos);
    }
  }

  private void export(Exporter target, List<Transaction> batch) {
    try {
      target.export(batch);
      exported.addAndGet(batch.size());
    } catch (Throwable t) {
      if (failedBatches.incrementAndGet() <= 10) {
        AgentLogger.warn(
            "exporter {} failed; {} transaction(s) dropped: {}",
            target.describe(),
            batch.size(),
            t.toString());
      }
    }
  }

  /**
   * Stops the thread and exports what is still queued until {@code timeoutMillis} has passed, then
   * flushes and closes the exporter (shutdown hook).
   */
  public void stop(long timeoutMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    running = false;
    Thread worker = thread;
    if (worker != null) {
      LockSupport.unpark(worker);
      try {
        worker.join(Math.max(1, timeoutMillis));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    Exporter target = exporter;
    if (target == null) {
      return;
    }
    List<Transaction> batch = new ArrayList<Transaction>(batchSize);
    while (System.nanoTime() < deadline && queue.drainTo(batch, batchSize) > 0) {
      export(target, batch);
      batch.clear();
    }
    try {
      target.flush();
    } catch (Throwable t) {
      AgentLogger.warn("exporter flush at shutdown failed: {}", t.toString());
    }
    target.close();
  }

  /** Transactions handed to the exporter successfully. */
  public long exported() {
    return exported.get();
  }

  /** The exporter once created (null before the sender thread ran). */
  public Exporter exporter() {
    return exporter;
  }
}
