/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.config.ArgosProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The audit path of agent events (ADR-0010 C-158 (1); {@code AGENT_AUTH_FAILED}, {@code
 * AGENT_AUTH_BLOCKED}, {@code AGENT_SESSION_REJECTED}): the caller is a gRPC thread of the
 * collector, which must never wait for PostgreSQL.
 *
 * <ul>
 *   <li>{@link #submit} never blocks: it decides the rate limit and puts the record on a bounded
 *       queue ({@value #QUEUE_CAPACITY}); a full queue drops it, counted in {@code
 *       argos_audit_dropped_total{reason="queue_full"}}. The worker {@code argos-audit-agent}
 *       writes the queue through {@link DefaultAuditService} (PostgreSQL, the other sinks, the
 *       fallback file), so a PostgreSQL outage slows only the worker.
 *   <li>Rate limit: per key (the event and the client address or token prefix) at most {@code
 *       argos.audit.agent-events-per-minute} (1) records in a minute window; the others are
 *       counted, and the count goes into {@code after.suppressed} of the next record of the key —
 *       the first one of a later window, or a summary record the worker writes when a window with
 *       suppressed events has passed. At most {@value #MAX_KEYS} keys are tracked; beyond that the
 *       events of an event share one overflow key ({@code *}).
 * </ul>
 */
@Component
public final class AsyncAuditDispatcher implements DisposableBean {

  /** Micrometer name of the drop counter ({@code argos_audit_dropped_total{reason}}). */
  public static final String DROPPED_METER = "argos.audit.dropped";

  /** Records waiting for the worker. */
  static final int QUEUE_CAPACITY = 1_000;

  /** Keys tracked by the rate limit. */
  static final int MAX_KEYS = 10_000;

  /** The overflow key. */
  static final String OVERFLOW_KEY = "*";

  private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(1);
  private static final long SWEEP_MS = TimeUnit.SECONDS.toMillis(5);
  private static final Logger LOG = LoggerFactory.getLogger(AsyncAuditDispatcher.class);

  /** One audit event waiting for the worker. */
  private record Pending(
      Instant ts,
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> after) {}

  /** The rate-limit state of one key. */
  private static final class Window {
    long start;
    int recorded;
    long suppressed;
    Pending last;
  }

  private final DefaultAuditService audit;
  private final int perMinute;
  private final Clock clock;
  private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final Counter queueFull;
  private final Thread worker;
  private volatile boolean closed;

  @Autowired
  public AsyncAuditDispatcher(
      DefaultAuditService audit,
      ObjectProvider<ArgosProperties> properties,
      ObjectProvider<MeterRegistry> registry) {
    this(
        audit,
        properties.getIfAvailable() == null
            ? 1
            : properties.getIfAvailable().audit().agentEventsPerMinute(),
        registry.getIfAvailable(SimpleMeterRegistry::new),
        Clock.systemUTC());
  }

  /** A dispatcher with explicit settings (tests). */
  public AsyncAuditDispatcher(
      DefaultAuditService audit, int perMinute, MeterRegistry registry, Clock clock) {
    this.audit = Objects.requireNonNull(audit, "audit");
    this.perMinute = Math.max(1, perMinute);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.queueFull =
        Counter.builder(DROPPED_METER)
            .description("Audit records dropped before they reached a sink")
            .tag("reason", "queue_full")
            .register(registry);
    this.worker = new Thread(this::run, "argos-audit-agent");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  /**
   * Records an agent event without blocking.
   *
   * @param event the event
   * @param key what the rate limit counts by: the client address or the token prefix
   * @param actor who (an agent token prefix, or the client address)
   * @param target what (the token, the address, the application)
   * @param result outcome and reason code
   * @param after extra fields (no secrets, no token), or null
   */
  public void submit(
      AuditEvent event,
      String key,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> after) {
    if (closed) {
      return;
    }
    try {
      Instant ts = clock.instant().truncatedTo(ChronoUnit.MICROS);
      long now = ts.toEpochMilli();
      Pending p = new Pending(ts, event, actor, target, result, after);
      String k = event.name() + "|" + (key == null ? "" : key);
      if (!windows.containsKey(k) && windows.size() >= MAX_KEYS) {
        k = event.name() + "|" + OVERFLOW_KEY;
      }
      Pending[] emit = {null};
      windows.compute(
          k,
          (name, w) -> {
            Window window = w == null ? new Window() : w;
            if (w == null || now - window.start >= WINDOW_MS) {
              long carried = window.suppressed;
              window.start = now;
              window.recorded = 0;
              window.suppressed = 0;
              window.last = null;
              emit[0] = carried > 0 ? withSuppressed(p, carried) : p;
              window.recorded = 1;
              return window;
            }
            if (window.recorded < perMinute) {
              window.recorded++;
              emit[0] = p;
            } else {
              window.suppressed++;
              window.last = p;
            }
            return window;
          });
      if (emit[0] != null) {
        enqueue(emit[0]);
      }
    } catch (RuntimeException e) {
      // Never into the caller's thread (gRPC).
      LOG.debug("Agent audit event not submitted", e);
    }
  }

  private void enqueue(Pending p) {
    if (!queue.offer(p)) {
      queueFull.increment();
    }
  }

  private static Pending withSuppressed(Pending p, long suppressed) {
    Map<String, Object> after = new LinkedHashMap<>();
    if (p.after() != null) {
      after.putAll(p.after());
    }
    after.put("suppressed", suppressed);
    return new Pending(p.ts(), p.event(), p.actor(), p.target(), p.result(), after);
  }

  /** Writes the summary records of windows that ended with suppressed events; forgets idle keys. */
  void sweep() {
    long now = clock.millis();
    for (String key : windows.keySet()) {
      Pending[] emit = {null};
      windows.computeIfPresent(
          key,
          (k, w) -> {
            if (now - w.start < WINDOW_MS) {
              return w;
            }
            if (w.suppressed > 0 && w.last != null) {
              emit[0] = withSuppressed(w.last, w.suppressed);
            }
            return null;
          });
      if (emit[0] != null) {
        enqueue(emit[0]);
      }
    }
  }

  /** Records waiting for the worker (tests). */
  int pending() {
    return queue.size();
  }

  private void run() {
    long nextSweep = System.currentTimeMillis() + SWEEP_MS;
    while (!closed || !queue.isEmpty()) {
      try {
        Pending p = queue.poll(SWEEP_MS, TimeUnit.MILLISECONDS);
        if (p != null) {
          write(p);
        }
        if (!closed && System.currentTimeMillis() >= nextSweep) {
          sweep();
          nextSweep = System.currentTimeMillis() + SWEEP_MS;
        }
      } catch (InterruptedException e) {
        if (queue.isEmpty()) {
          return;
        }
      } catch (RuntimeException e) {
        LOG.debug("Agent audit worker error", e);
      }
    }
  }

  private void write(Pending p) {
    try {
      audit.write(
          DefaultAuditService.toRecord(
              p.ts(), p.event(), p.actor(), p.target(), p.result(), null, p.after(), null, null));
    } catch (RuntimeException e) {
      LOG.debug("Agent audit event not written", e);
    }
  }

  @Override
  public void destroy() {
    closed = true;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!queue.isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    worker.interrupt();
  }
}
