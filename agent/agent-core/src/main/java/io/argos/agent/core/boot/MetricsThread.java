/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.metric.ActiveSnapshotter;
import io.argos.agent.core.metric.MetricCollector;
import io.argos.agent.core.stats.StatsAggregator;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.transport.PreparedBatch;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.StatBatch;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The {@code argos-metrics} thread (10 §11, §14 thread budget: metrics, statistics flush, active
 * snapshot). One tick per wall-clock second ({@value #TICK_OFFSET_MS} ms after the full second):
 *
 * <ul>
 *   <li>on the {@code metrics.interval-seconds} grid (5 s): {@link MetricCollector#collect} of the
 *       grid point into the {@code metric} queue ({@code PreparedBatch.metrics});
 *   <li>every tick: {@link StatsAggregator#drain} into the {@code stats} queue (a window leaves
 *       about one second after its end, 10 §9.3);
 *   <li>every {@code ServerHello.active_snapshot_interval_s}, else {@code
 *       active.snapshot-interval-seconds} (5 s): the {@link ActiveTask} (C-225 (1)).
 * </ul>
 *
 * The collector is built on this thread at its first tick, never on the premain thread (P7 only
 * starts the thread, ADR-0009 C-79). {@link #stop} (shutdown hook, before the sender's final flush)
 * ends the thread and puts the open statistics window into the queue once ({@link
 * StatsAggregator#drainAll()}), so that a JVM stopped right after its last requests still reports
 * them (10 §10.2). A failing task is logged once and skipped; the thread never dies of it.
 */
final class MetricsThread {
  /** Offset of the tick within the wall-clock second. */
  static final long TICK_OFFSET_MS = 200;

  /** How long {@link #stop} waits for a running tick. */
  static final long STOP_JOIN_MILLIS = 300;

  /** The active snapshot of {@code argos-metrics} (10 §11 {@code ActiveSnapshotter}). */
  interface ActiveTask {
    /** One snapshot at wall-clock {@code nowMs}; must not block, may throw (logged once). */
    void snapshot(long nowMs);
  }

  private final AgentConfig config;
  private final TraceRuntime runtime;
  private final SelfDiagnosticsCollector diagnostics;
  private final boolean activeSnapshots;
  private final Object tickLock = new Object();
  private volatile boolean running;
  private volatile Thread thread;

  // Tick state (under tickLock).
  private MetricCollector collector;
  private boolean collectorFailed;
  private long nextMetricMs = Long.MIN_VALUE;
  private long nextActiveMs = Long.MIN_VALUE;
  private boolean metricFailed;
  private boolean statsFailed;
  private boolean activeFailed;
  private ActiveTask active;
  private boolean activeUnavailable;

  /**
   * @param config {@code metrics.interval-seconds}, {@code metrics.max-series}, {@code
   *     active.snapshot-interval-seconds}
   * @param runtime queues, tracer (statistics, pool waits) and the server's active period
   * @param diagnostics the self-diagnostics counters for the agent series (may be null)
   * @param active the active snapshot task (null: none)
   */
  MetricsThread(
      AgentConfig config,
      TraceRuntime runtime,
      SelfDiagnosticsCollector diagnostics,
      ActiveTask active) {
    this(config, runtime, diagnostics, active, false);
  }

  private MetricsThread(
      AgentConfig config,
      TraceRuntime runtime,
      SelfDiagnosticsCollector diagnostics,
      ActiveTask active,
      boolean activeSnapshots) {
    this.config = config;
    this.runtime = runtime;
    this.diagnostics = diagnostics;
    this.active = active;
    this.activeSnapshots = activeSnapshots;
  }

  /**
   * The agent's thread: its active task is the {@link ActiveSnapshotter} of {@code runtime}, built
   * on this thread at the first snapshot, so that the premain thread loads none of its classes
   * (ADR-0009 C-79).
   */
  static MetricsThread withActiveSnapshots(
      AgentConfig config, TraceRuntime runtime, SelfDiagnosticsCollector diagnostics) {
    return new MetricsThread(config, runtime, diagnostics, null, true);
  }

  synchronized void start() {
    if (thread != null) {
      return;
    }
    running = true;
    Thread worker =
        ArgosThread.factory("argos-metrics")
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
      long now = System.currentTimeMillis();
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(untilNextTick(now)));
      if (!running) {
        return;
      }
      tick(System.currentTimeMillis());
    }
  }

  /**
   * Milliseconds from {@code nowMs} to the next tick ({@value #TICK_OFFSET_MS} ms past a second).
   */
  static long untilNextTick(long nowMs) {
    long inSecond = Math.floorMod(nowMs, 1000L);
    long wait = TICK_OFFSET_MS - inSecond;
    return wait > 0 ? wait : wait + 1000L;
  }

  /**
   * One tick at wall-clock {@code nowMs}: metrics when due, statistics, active snapshot when due.
   */
  void tick(long nowMs) {
    synchronized (tickLock) {
      metrics(nowMs);
      stats(nowMs);
      active(nowMs);
    }
  }

  private void metrics(long nowMs) {
    long gridMs = Math.max(1, config.metricsIntervalSeconds()) * 1000L;
    long gridTs = Math.floorDiv(nowMs, gridMs) * gridMs;
    if (nextMetricMs != Long.MIN_VALUE && nowMs < nextMetricMs) {
      return;
    }
    nextMetricMs = gridTs + gridMs;
    try {
      MetricCollector c = collector();
      if (c == null) {
        return;
      }
      MetricBatch batch = c.collect(gridTs);
      if (batch != null && batch.getSamplesCount() > 0) {
        runtime.queues().metric().offer(PreparedBatch.metrics(batch));
      }
    } catch (Throwable t) {
      if (!metricFailed) {
        metricFailed = true;
        AgentLogger.warn("metric collection failed: {}", t.getClass());
      }
    }
  }

  /** The collector, built at the first collection (this thread). */
  private MetricCollector collector() {
    if (collector != null || collectorFailed) {
      return collector;
    }
    try {
      TracerImpl tracer = runtime.tracerImpl();
      MetricCollector created =
          MetricCollector.create(config, runtime, tracer == null ? null : tracer.poolWaits());
      if (diagnostics != null) {
        created.selfDiagnostics(diagnostics);
      }
      StatsAggregator stats = runtime.stats();
      if (stats != null) {
        created.addCounterSource(stats);
      }
      collector = created;
    } catch (Throwable t) {
      collectorFailed = true;
      AgentLogger.warn("metric collector unavailable: {}", t.getClass());
    }
    return collector;
  }

  private void stats(long nowMs) {
    try {
      StatsAggregator stats = runtime.stats();
      if (stats != null) {
        offer(stats.drain(nowMs));
      }
    } catch (Throwable t) {
      if (!statsFailed) {
        statsFailed = true;
        AgentLogger.warn("statistics flush failed: {}", t.getClass());
      }
    }
  }

  private void offer(List<StatBatch> batches) {
    for (StatBatch batch : batches) {
      runtime.queues().stats().offer(PreparedBatch.stats(batch));
    }
  }

  /** The active task; the default snapshotter is built at its first use (this thread). */
  private ActiveTask activeTask() {
    if (active != null || !activeSnapshots || activeUnavailable) {
      return active;
    }
    try {
      final ActiveSnapshotter snapshotter = ActiveSnapshotter.create(runtime);
      active =
          new ActiveTask() {
            @Override
            public void snapshot(long nowMs) {
              snapshotter.snapshot(nowMs);
            }
          };
    } catch (Throwable t) {
      activeUnavailable = true;
      AgentLogger.warn("active snapshots unavailable: {}", t.getClass());
    }
    return active;
  }

  private void active(long nowMs) {
    ActiveTask task = activeTask();
    if (task == null) {
      return;
    }
    int server = runtime.serverActiveSnapshotIntervalSeconds();
    long periodMs =
        (server > 0 ? server : Math.max(1, config.activeSnapshotIntervalSeconds())) * 1000L;
    if (nextActiveMs != Long.MIN_VALUE && nowMs < nextActiveMs) {
      return;
    }
    nextActiveMs = Math.floorDiv(nowMs, periodMs) * periodMs + periodMs;
    try {
      task.snapshot(nowMs);
    } catch (Throwable t) {
      if (!activeFailed) {
        activeFailed = true;
        AgentLogger.warn("active snapshot failed: {}", t.getClass());
      }
    }
  }

  /**
   * Stops the thread (waits at most {@value #STOP_JOIN_MILLIS} ms for a running tick) and puts
   * every statistics window, the open one included, into the {@code stats} queue once (shutdown
   * hook, before the sender's final flush).
   */
  void stop() {
    running = false;
    Thread worker = thread;
    if (worker != null) {
      LockSupport.unpark(worker);
      try {
        worker.join(STOP_JOIN_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    flushStats();
  }

  /** Every statistics window into the queue now (the shutdown flush). */
  void flushStats() {
    synchronized (tickLock) {
      try {
        StatsAggregator stats = runtime.stats();
        if (stats != null) {
          offer(stats.drainAll());
        }
      } catch (Throwable t) {
        AgentLogger.warn("statistics flush at shutdown failed: {}", t.getClass());
      }
    }
  }
}
