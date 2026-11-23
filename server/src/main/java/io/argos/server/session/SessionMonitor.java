/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.api.InstanceState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The instance state monitor (12 §4.2): one thread ({@code argos-session-monitor}) evaluates every
 * instance every {@value #PERIOD_SECONDS} s ({@link InstanceStateTracker#tick}) and flushes the
 * state writer; it also writes the server's own lifecycle events (the {@code api.events.Outages}
 * server-down contract: {@code events} {@code type = 'start'} when the server has started, {@code
 * type = 'stop'} on a graceful stop, both without application and instance) and the meters {@code
 * argos_instances{state}} and {@code argos_clock_offset_ms{instance}} (12 §10, ADR-0010 C-158 (4)).
 *
 * <p>At its start it takes the instances PostgreSQL still has up, warning or stale (the previous
 * run stopped without marking them) as last seen at the start (WO-22 assumption), so that an agent
 * that does not come back is STALE after 60 s and DOWN after 180 s.
 *
 * <p>Lifecycle phase {@value #PHASE}: it starts before and stops after the gRPC collector ({@code
 * CollectorLifecycle.PHASE}), so the {@code stop} event follows the sessions' close.
 */
public final class SessionMonitor implements SmartLifecycle, AutoCloseable {

  /** Seconds between two evaluations (12 §4.2). */
  public static final int PERIOD_SECONDS = 10;

  /** Lifecycle phase: below the collector's ({@code DEFAULT_PHASE − 512}). */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 768;

  /** How often {@code argos_instances{state="DOWN"}} re-reads the instance count. */
  static final long COUNT_REFRESH_MS = 60_000;

  /** How long the shutdown waits for the last PostgreSQL writes. */
  static final Duration STOP_FLUSH_TIMEOUT = Duration.ofSeconds(5);

  private static final Logger LOG = LoggerFactory.getLogger(SessionMonitor.class);

  private final InstanceStateTracker tracker;
  private final InstanceStateWriter writer;
  private final InstanceStateStore store;
  private final Clock clock;
  private final MultiGauge offsets;
  private final AtomicInteger activeInstances = new AtomicInteger(-1);
  private volatile long lastCountMs = Long.MIN_VALUE / 2;
  private ScheduledExecutorService executor;
  private volatile boolean running;

  /**
   * The monitor.
   *
   * @param tracker the instance states
   * @param writer the PostgreSQL writer of the tracker
   * @param store the PostgreSQL store, or null (no restore, no server events, no instance count)
   * @param clock the server clock
   * @param meters the meter registry
   */
  public SessionMonitor(
      InstanceStateTracker tracker,
      InstanceStateWriter writer,
      InstanceStateStore store,
      Clock clock,
      MeterRegistry meters) {
    this.tracker = tracker;
    this.writer = writer;
    this.store = store;
    this.clock = clock;
    for (InstanceState s : InstanceState.values()) {
      Gauge.builder("argos.instances", this, m -> m.count(s))
          .description("Instances per state (12 §4.2)")
          .tag("state", s.name())
          .register(meters);
    }
    this.offsets =
        MultiGauge.builder("argos.clock.offset.ms")
            .description("Clock offset of live instances, server minus agent (11 §9, largest 50)")
            .register(meters);
  }

  /** {@code argos_instances{state}}: tracked counts; DOWN = instances − the others when known. */
  double count(InstanceState s) {
    Map<InstanceState, Integer> counts = tracker.counts();
    if (s != InstanceState.DOWN) {
      return counts.get(s);
    }
    int total = activeInstances.get();
    if (total < 0) {
      return counts.get(InstanceState.DOWN);
    }
    int notDown =
        counts.get(InstanceState.UP)
            + counts.get(InstanceState.WARN)
            + counts.get(InstanceState.STALE);
    return Math.max(0, total - notDown);
  }

  /** One evaluation, the meters and a flush (the scheduled task; tests call it directly). */
  public synchronized void tick() {
    try {
      tracker.tick();
      List<MultiGauge.Row<?>> rows = new ArrayList<>();
      for (Map.Entry<Integer, Long> e : tracker.topOffsets().entrySet()) {
        rows.add(
            MultiGauge.Row.of(Tags.of("instance", Integer.toString(e.getKey())), e.getValue()));
      }
      offsets.register(rows, true);
      long now = clock.millis();
      if (store != null && now - lastCountMs >= COUNT_REFRESH_MS) {
        lastCountMs = now;
        refreshCount();
      }
      writer.schedule();
    } catch (RuntimeException e) {
      LOG.error("The instance state evaluation failed", e);
    }
  }

  private void refreshCount() {
    try {
      activeInstances.set(store.countActiveInstances());
    } catch (RuntimeException e) {
      LOG.debug("The instance count is unavailable: {}", e.getMessage());
    }
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    long startMs = clock.millis();
    if (store != null) {
      try {
        tracker.restore(store.openInstances(), startMs);
      } catch (RuntimeException e) {
        LOG.warn(
            "The instances left up by the previous run could not be read; they stay as stored"
                + " until their agents reconnect: {}",
            e.getMessage());
      }
      refreshCount();
      writer.enqueue(serverEvent("start", "Server started", startMs));
    }
    AtomicInteger next = new AtomicInteger(1);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-session-monitor-" + next.getAndIncrement());
              t.setDaemon(true);
              return t;
            });
    var unused =
        executor.scheduleWithFixedDelay(
            this::tick, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    if (store != null) {
      writer.enqueue(serverEvent("stop", "Server stopped", clock.millis()));
      if (!writer.flushAndWait(STOP_FLUSH_TIMEOUT)) {
        LOG.warn(
            "The server stop event and the last instance states were not written to PostgreSQL"
                + " ({} pending)",
            writer.pending());
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  @Override
  public void close() {
    stop();
    writer.close();
  }

  private static EventRow serverEvent(String type, String title, long atMs) {
    return new EventRow(
        UUID.randomUUID(), null, null, null, type, title, null, Instant.ofEpochMilli(atMs));
  }
}
