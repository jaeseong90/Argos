/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of one generator run, per route and in total. Every structure has a fixed size (one set
 * of counters per route, status codes 0-599, fixed histograms), so a demo generator running for
 * weeks keeps constant memory.
 */
final class LoadStats {

  private static final int STATUS_SLOTS = 600;

  private final Mix mix;
  private final RouteCounters[] routes;
  private final LatencyHistogram latency = new LatencyHistogram();
  private final LatencyHistogram lag = new LatencyHistogram();

  LoadStats(Mix mix) {
    this.mix = mix;
    this.routes = new RouteCounters[mix.routes().size()];
    for (int i = 0; i < routes.length; i++) {
      routes[i] = new RouteCounters();
    }
  }

  /** A request became due (sent or dropped). */
  void scheduled(int route) {
    routes[route].scheduled.increment();
  }

  /**
   * A due request was not sent because the in-flight limit was reached (k6: dropped_iterations).
   */
  void dropped(int route) {
    routes[route].dropped.increment();
  }

  /** A request was handed to the transport {@code lagNanos} after its intended start time. */
  void sent(int route, long lagNanos) {
    routes[route].sent.increment();
    lag.recordNanos(lagNanos);
  }

  /** An answer arrived. */
  void completed(int route, int status, long latencyNanos) {
    RouteCounters counters = routes[route];
    counters.completed.increment();
    if (status >= 0 && status < STATUS_SLOTS) {
      counters.statuses.incrementAndGet(status);
    } else {
      counters.statuses.incrementAndGet(0);
    }
    if (status >= 500) {
      counters.status5xx.increment();
    }
    if (Outcome.ofStatus(status, mix.routes().get(route).expectedStatus()) == Outcome.EXPECTED) {
      counters.expected.increment();
    } else {
      counters.unexpectedStatus.increment();
    }
    counters.latency.recordNanos(latencyNanos);
    latency.recordNanos(latencyNanos);
  }

  /** No answer: timeout, connect failure or another transport error. */
  void failed(int route, Outcome outcome) {
    RouteCounters counters = routes[route];
    switch (outcome) {
      case TIMEOUT -> counters.timeouts.increment();
      case CONNECT_FAIL -> counters.connectFails.increment();
      default -> counters.ioErrors.increment();
    }
  }

  Snapshot snapshot() {
    List<RouteSnapshot> list = new ArrayList<>(routes.length);
    for (int i = 0; i < routes.length; i++) {
      RouteCounters c = routes[i];
      Map<Integer, Long> statuses = new LinkedHashMap<>();
      for (int status = 0; status < STATUS_SLOTS; status++) {
        long n = c.statuses.get(status);
        if (n > 0) {
          statuses.put(status, n);
        }
      }
      list.add(
          new RouteSnapshot(
              mix.routes().get(i),
              c.scheduled.sum(),
              c.dropped.sum(),
              c.sent.sum(),
              c.completed.sum(),
              c.expected.sum(),
              c.unexpectedStatus.sum(),
              c.timeouts.sum(),
              c.connectFails.sum(),
              c.ioErrors.sum(),
              c.status5xx.sum(),
              Collections.unmodifiableMap(statuses),
              c.latency.snapshot()));
    }
    return new Snapshot(Collections.unmodifiableList(list), latency.snapshot(), lag.snapshot());
  }

  private static final class RouteCounters {
    final LongAdder scheduled = new LongAdder();
    final LongAdder dropped = new LongAdder();
    final LongAdder sent = new LongAdder();
    final LongAdder completed = new LongAdder();
    final LongAdder expected = new LongAdder();
    final LongAdder unexpectedStatus = new LongAdder();
    final LongAdder timeouts = new LongAdder();
    final LongAdder connectFails = new LongAdder();
    final LongAdder ioErrors = new LongAdder();
    final LongAdder status5xx = new LongAdder();
    final AtomicLongArray statuses = new AtomicLongArray(STATUS_SLOTS);
    final LatencyHistogram latency = new LatencyHistogram();
  }

  /** Counters of one route at one moment. */
  record RouteSnapshot(
      Mix.Route route,
      long scheduled,
      long dropped,
      long sent,
      long completed,
      long expected,
      long unexpectedStatus,
      long timeouts,
      long connectFails,
      long ioErrors,
      long status5xx,
      Map<Integer, Long> statuses,
      LatencyHistogram.Snapshot latency) {

    /** Requests that ended without the expected outcome (wrong status or no answer). */
    long unexpected() {
      return unexpectedStatus + failures();
    }

    long failures() {
      return timeouts + connectFails + ioErrors;
    }
  }

  /** Every route plus the overall latency (answered requests) and schedule lag. */
  record Snapshot(
      List<RouteSnapshot> routes,
      LatencyHistogram.Snapshot latency,
      LatencyHistogram.Snapshot lag) {

    long scheduled() {
      return routes.stream().mapToLong(RouteSnapshot::scheduled).sum();
    }

    long dropped() {
      return routes.stream().mapToLong(RouteSnapshot::dropped).sum();
    }

    long sent() {
      return routes.stream().mapToLong(RouteSnapshot::sent).sum();
    }

    long completed() {
      return routes.stream().mapToLong(RouteSnapshot::completed).sum();
    }

    long expected() {
      return routes.stream().mapToLong(RouteSnapshot::expected).sum();
    }

    long unexpected() {
      return routes.stream().mapToLong(RouteSnapshot::unexpected).sum();
    }

    long failures() {
      return routes.stream().mapToLong(RouteSnapshot::failures).sum();
    }

    long status5xx() {
      return routes.stream().mapToLong(RouteSnapshot::status5xx).sum();
    }

    /** Requests that ended (answered or failed). */
    long finished() {
      return completed() + failures();
    }
  }
}
