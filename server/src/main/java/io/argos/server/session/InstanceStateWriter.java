/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.InstanceStateStore.StateRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes instance state rows and events to PostgreSQL on its own thread ({@code
 * argos-instance-state-writer}), so that the threads that decide a transition (session streams,
 * data calls, the monitor) never wait on the database (12 §3.9: a PostgreSQL outage never blocks
 * the collector).
 *
 * <p>Bounded: state rows are coalesced per instance (the newest wins; an instance has at most one
 * pending row, and instances are bounded by the Hello checks); events wait in a queue of at most
 * {@value #MAX_EVENTS}, the oldest dropped and counted beyond it ({@code
 * argos_instance_state_events_dropped_total}). A failed write keeps its rows and events and is
 * retried by the next flush (the monitor flushes every 10 s); event ids are generated before the
 * first attempt and the store ignores an id it already has, so a retry after a lost answer never
 * inserts an event twice.
 *
 * <p>Without a store (a collector test context without PostgreSQL) every write is discarded.
 */
public final class InstanceStateWriter implements AutoCloseable {

  /** Most events waiting for PostgreSQL. */
  public static final int MAX_EVENTS = 10_000;

  /** Most events of one insert batch. */
  static final int EVENTS_PER_BATCH = 1_000;

  private static final Logger LOG = LoggerFactory.getLogger(InstanceStateWriter.class);

  private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final InstanceStateStore store;
  private final Map<Integer, StateRow> rows = new LinkedHashMap<>();
  private final Deque<EventRow> events = new ArrayDeque<>();
  private final Map<Integer, Instant> archives = new LinkedHashMap<>();
  private final Object lock = new Object();
  private final Object flushLock = new Object();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicLong lastWarnNanos = new AtomicLong(Long.MIN_VALUE);
  private final ExecutorService executor;
  private final Counter dropped;
  private final Counter failures;
  private volatile boolean closed;

  /**
   * A writer to {@code store}.
   *
   * @param store the PostgreSQL store, or null to discard every write
   * @param meters the meter registry
   */
  public InstanceStateWriter(InstanceStateStore store, MeterRegistry meters) {
    this.store = store;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "argos-instance-state-writer");
              t.setDaemon(true);
              return t;
            });
    this.dropped =
        Counter.builder("argos.instance.state.events.dropped")
            .description("Instance state events dropped because PostgreSQL was behind")
            .register(meters);
    this.failures =
        Counter.builder("argos.instance.state.write.failures")
            .description("Failed PostgreSQL writes of instance states and events (retried)")
            .register(meters);
  }

  /** Whether a store is attached. */
  public boolean enabled() {
    return store != null;
  }

  /** Queues the state columns of an instance (coalesced with a pending row of it). */
  public void enqueue(StateRow row) {
    if (store == null || closed) {
      return;
    }
    synchronized (lock) {
      rows.merge(row.instanceId(), row, InstanceStateWriter::merge);
    }
    schedule();
  }

  /**
   * Queues the archive of an instance ({@link InstanceStateStore#archiveInstances}); a pending
   * state row of it is discarded. Bounded like the state rows (one entry per instance).
   */
  public void archive(int instanceId, Instant at) {
    if (store == null || closed) {
      return;
    }
    synchronized (lock) {
      rows.remove(instanceId);
      archives.put(instanceId, at);
    }
    schedule();
  }

  /** Queues an event. */
  public void enqueue(EventRow event) {
    if (store == null || closed) {
      return;
    }
    synchronized (lock) {
      if (events.size() >= MAX_EVENTS) {
        events.pollFirst();
        dropped.increment();
      }
      events.addLast(event);
    }
    schedule();
  }

  /** Rows and events waiting. */
  public int pending() {
    synchronized (lock) {
      return rows.size() + events.size() + archives.size();
    }
  }

  /** Schedules a flush on the writer thread (coalesced). */
  public void schedule() {
    if (store == null || closed || !scheduled.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            scheduled.set(false);
            flush();
          });
    } catch (RuntimeException e) {
      scheduled.set(false);
    }
  }

  /**
   * Writes what is pending, on the caller's thread (the writer thread, a test, or the shutdown).
   *
   * @return whether everything pending was written
   */
  public boolean flush() {
    if (store == null) {
      return true;
    }
    synchronized (flushLock) {
      List<EventRow> batch;
      List<StateRow> states;
      Map<Integer, Instant> archived;
      synchronized (lock) {
        batch = new ArrayList<>(events);
        events.clear();
        states = new ArrayList<>(rows.values());
        rows.clear();
        archived = new LinkedHashMap<>(archives);
        archives.clear();
      }
      boolean statesWritten = false;
      int written = 0;
      try {
        while (written < batch.size()) {
          int end = Math.min(batch.size(), written + EVENTS_PER_BATCH);
          store.writeEvents(batch.subList(written, end));
          written = end;
        }
        if (!states.isEmpty()) {
          store.writeStates(states);
        }
        statesWritten = true;
        if (!archived.isEmpty()) {
          store.archiveInstances(archived);
        }
        return true;
      } catch (RuntimeException e) {
        failures.increment();
        requeue(batch.subList(written, batch.size()), statesWritten ? List.of() : states, archived);
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_NANOS) {
          lastWarnNanos.set(now);
          LOG.warn(
              "Instance states could not be written to PostgreSQL; kept in memory and retried"
                  + " (counted as argos_instance_state_write_failures_total): {}",
              e.getMessage());
        }
        return false;
      }
    }
  }

  /**
   * Flushes on the writer thread and waits up to {@code timeout} (the shutdown and tests).
   *
   * @return whether everything pending was written in time
   */
  public boolean flushAndWait(Duration timeout) {
    if (store == null) {
      return true;
    }
    try {
      Future<Boolean> f = executor.submit(this::flush);
      return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
      return false;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void close() {
    closed = true;
    executor.shutdownNow();
  }

  private void requeue(
      List<EventRow> unwritten, List<StateRow> states, Map<Integer, Instant> archived) {
    synchronized (lock) {
      archived.forEach(archives::putIfAbsent);
      for (int i = unwritten.size() - 1; i >= 0; i--) {
        if (events.size() >= MAX_EVENTS) {
          dropped.increment();
          continue;
        }
        events.addFirst(unwritten.get(i));
      }
      for (StateRow r : states) {
        if (archives.containsKey(r.instanceId())) {
          continue;
        }
        // a newer row queued meanwhile wins; it keeps the older one's offset and time when unset
        rows.merge(r.instanceId(), r, (newer, older) -> merge(older, newer));
      }
    }
  }

  /** {@code newer}, with the offset and time of {@code older} where {@code newer} has none. */
  static StateRow merge(StateRow older, StateRow newer) {
    return new StateRow(
        newer.instanceId(),
        newer.status(),
        newer.warnFlags(),
        newer.clockOffsetMs() != null ? newer.clockOffsetMs() : older.clockOffsetMs(),
        newer.lastSeenAt() != null ? newer.lastSeenAt() : older.lastSeenAt());
  }
}
