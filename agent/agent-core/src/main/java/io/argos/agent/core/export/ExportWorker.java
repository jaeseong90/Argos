/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.agent.core.transport.Queues;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * The {@code argos-sender} thread (10 §10.2, §14) until the gRPC sender of WO-11 replaces it: first
 * runs the work premain left to an agent thread ({@link TraceRuntime#afterPremain()}) and creates
 * the exporter, then drains the queues of 10 §10.1 in batches ({@code sender.batch-size}, 500) at
 * least every {@code sender.flush-interval-ms} (100 ms): transaction records to {@link
 * Exporter#export}, profiles to {@link Exporter#exportProfiles}. The statistics, metric and active
 * queues have no producer before WO-16 and no exporter before WO-11; whatever they hold is drained
 * and discarded so that they never fill. At shutdown, what is left is exported for at most the
 * given time (10 §10.2 "종료 훅").
 */
public final class ExportWorker {
  private final Queues queues;
  private final ExporterContext context;
  private final File logDirectory;
  private final int batchSize;
  private final long flushIntervalNanos;
  private final Runnable beforeExport;
  private final AtomicLong exported = new AtomicLong();
  private final AtomicLong exportedProfiles = new AtomicLong();
  private final AtomicLong failedBatches = new AtomicLong();

  private volatile Exporter exporter;
  private volatile boolean running;
  private volatile Thread thread;

  /**
   * The worker of the agent: drains {@code runtime}'s queues and runs {@link
   * TraceRuntime#afterPremain()} first on its own thread.
   */
  public ExportWorker(
      final TraceRuntime runtime,
      ExporterContext context,
      File logDirectory,
      int batchSize,
      long flushIntervalMillis) {
    this(
        runtime.queues(),
        context,
        logDirectory,
        batchSize,
        flushIntervalMillis,
        new Runnable() {
          @Override
          public void run() {
            runtime.afterPremain();
          }
        });
  }

  /**
   * A worker that drains {@code queues}; {@code beforeExport} (may be null) runs once on the worker
   * thread before the exporter is created.
   */
  public ExportWorker(
      Queues queues,
      ExporterContext context,
      File logDirectory,
      int batchSize,
      long flushIntervalMillis,
      Runnable beforeExport) {
    this.queues = queues;
    this.context = context;
    this.logDirectory = logDirectory;
    this.batchSize = Math.max(1, batchSize);
    this.flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, flushIntervalMillis));
    this.beforeExport = beforeExport;
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
    if (beforeExport != null) {
      try {
        beforeExport.run();
      } catch (Throwable t) {
        AgentLogger.warn("agent start work after premain failed: {}", t.toString());
      }
    }
    Exporter created = Exporters.create(context, logDirectory);
    exporter = created;
    AgentLogger.info("exporter: {}", created.describe());
    List<Transaction> transactions = new ArrayList<Transaction>(batchSize);
    List<ProfileRecord> profiles = new ArrayList<ProfileRecord>(batchSize);
    while (running) {
      try {
        int moved = exportOnce(created, transactions, profiles);
        if (moved >= batchSize) {
          continue;
        }
        created.flush();
      } catch (Throwable t) {
        AgentLogger.warn("exporter flush failed: {}", t.toString());
      }
      LockSupport.parkNanos(flushIntervalNanos);
    }
  }

  /** One round over the queues; returns the larger of the transaction and profile counts. */
  private int exportOnce(
      Exporter target, List<Transaction> transactions, List<ProfileRecord> profiles) {
    int records = drainTransactions(queues.transaction(), transactions, batchSize);
    if (records > 0) {
      exportTransactions(target, transactions);
      transactions.clear();
    }
    int sampled = drainProfiles(queues.profile(), profiles, batchSize);
    if (sampled > 0) {
      exportProfiles(target, profiles);
      profiles.clear();
    }
    discard(queues.stats());
    discard(queues.metric());
    discard(queues.active());
    return Math.max(records, sampled);
  }

  /** Moves up to {@code max} transaction records of {@code queue} into {@code batch}. */
  private static int drainTransactions(
      AgentQueue<QueueItem> queue, final List<Transaction> batch, int max) {
    final int before = batch.size();
    queue.drain(
        max,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            if (item instanceof Transaction) {
              batch.add((Transaction) item);
            }
          }
        });
    return batch.size() - before;
  }

  private static int drainProfiles(
      AgentQueue<QueueItem> queue, final List<ProfileRecord> batch, int max) {
    final int before = batch.size();
    queue.drain(
        max,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            if (item instanceof ProfileRecord) {
              batch.add((ProfileRecord) item);
            }
          }
        });
    return batch.size() - before;
  }

  private void discard(AgentQueue<QueueItem> queue) {
    queue.drain(batchSize, Discard.INSTANCE);
  }

  private void exportTransactions(Exporter target, List<Transaction> batch) {
    try {
      target.export(batch);
      exported.addAndGet(batch.size());
    } catch (Throwable t) {
      failed(target, batch.size(), "transaction(s)", t);
    }
  }

  private void exportProfiles(Exporter target, List<ProfileRecord> batch) {
    try {
      target.exportProfiles(batch);
      exportedProfiles.addAndGet(batch.size());
    } catch (Throwable t) {
      failed(target, batch.size(), "profile(s)", t);
    }
  }

  private void failed(Exporter target, int size, String what, Throwable t) {
    if (failedBatches.incrementAndGet() <= 10) {
      AgentLogger.warn(
          "exporter {} failed; {} {} dropped: {}", target.describe(), size, what, t.toString());
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
    List<Transaction> transactions = new ArrayList<Transaction>(batchSize);
    List<ProfileRecord> profiles = new ArrayList<ProfileRecord>(batchSize);
    while (System.nanoTime() < deadline && exportOnce(target, transactions, profiles) > 0) {
      transactions.clear();
      profiles.clear();
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

  /** Profiles handed to the exporter successfully. */
  public long exportedProfiles() {
    return exportedProfiles.get();
  }

  /** The exporter once created (null before the sender thread ran). */
  public Exporter exporter() {
    return exporter;
  }

  /** Drops what a queue without exporter holds. */
  private static final class Discard implements Consumer<QueueItem> {
    static final Discard INSTANCE = new Discard();

    @Override
    public void accept(QueueItem item) {}
  }
}
