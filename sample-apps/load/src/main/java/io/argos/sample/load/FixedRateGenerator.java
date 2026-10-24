/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-model load at a fixed arrival rate (like k6 {@code constant-arrival-rate}): request {@code
 * n} is due at {@code start + n / rate} whatever the target's latency, so a slow target sees the
 * same arrivals and the queueing shows up as latency instead of a lower rate. One dispatcher thread
 * sends due requests without waiting for answers. When it wakes up late (coarse OS timers, GC) it
 * sends every request that became due, so the average rate stays exact. A bounded number of
 * requests may be in flight; a due request beyond that bound is dropped and counted (k6: {@code
 * dropped_iterations}), never queued.
 *
 * <p>An optional warm-up (like bench.js {@code WARMUP}) runs first at the same rate and mix; its
 * requests are counted in separate statistics, so the measured window starts with a warm target. A
 * request belongs to the phase in which it was due.
 */
final class FixedRateGenerator {

  /** Longest single sleep, so that stop() and interval reports are handled promptly. */
  private static final long MAX_SLEEP_NANOS = 50_000_000L;

  /** Poll interval while waiting for in-flight requests at the end. */
  private static final long DRAIN_POLL_NANOS = 5_000_000L;

  /** Longest drain after stop() (docker stop sends SIGKILL 10 s after SIGTERM). */
  private static final long STOP_DRAIN_NANOS = 5_000_000_000L;

  /**
   * Called every report interval with the time since the start, the counters of the current phase
   * and whether that phase is the warm-up.
   */
  interface Reporter {
    void report(long elapsedNanos, LoadStats.Snapshot snapshot, int inFlight, boolean warmup);
  }

  /**
   * The measured window (after the warm-up), the requests due in it, and what was still in flight
   * at the end.
   */
  record Result(long windowNanos, long dueRequests, int inFlightAtEnd) {}

  private final double rate;
  private final Duration duration;
  private final long warmupNanos;
  private final int maxInFlight;
  private final Duration requestTimeout;
  private final Duration drainTimeout;
  private final long reportIntervalNanos;
  private final Mix mix;
  private final Transport transport;
  private final TimeSource time;
  private final LoadStats warmupStats;
  private final LoadStats stats;
  private final Reporter reporter;
  private final AtomicInteger inFlight = new AtomicInteger();
  private volatile boolean stopped;
  private volatile Thread dispatcher;

  /** Without separate warm-up statistics (they are discarded). */
  FixedRateGenerator(
      LoadConfig config, Transport transport, TimeSource time, LoadStats stats, Reporter reporter) {
    this(config, transport, time, new LoadStats(config.mix()), stats, reporter);
  }

  FixedRateGenerator(
      LoadConfig config,
      Transport transport,
      TimeSource time,
      LoadStats warmupStats,
      LoadStats stats,
      Reporter reporter) {
    this.rate = config.rate();
    this.duration = config.duration();
    this.warmupNanos = config.warmup().toNanos();
    this.maxInFlight = config.maxInFlight();
    this.requestTimeout = config.requestTimeout();
    this.drainTimeout = config.requestTimeout().plusSeconds(1);
    this.reportIntervalNanos = config.reportInterval().toNanos();
    this.mix = config.mix();
    this.transport = transport;
    this.time = time;
    this.warmupStats = warmupStats;
    this.stats = stats;
    this.reporter = reporter;
  }

  /** Runs the warm-up, then until the duration has passed or {@link #stop()}; then drains. */
  Result run() {
    dispatcher = Thread.currentThread();
    WeightedSchedule schedule = mix.schedule();
    double nanosPerRequest = 1e9 / rate;
    long start = time.nanoTime();
    long measureStart = start + warmupNanos;
    long end = duration == null ? Long.MAX_VALUE : measureStart + duration.toNanos();
    long nextReport = reportIntervalNanos > 0 ? start + reportIntervalNanos : Long.MAX_VALUE;
    long sequence = 0;
    long firstMeasured = -1;
    long windowEnd;
    while (true) {
      long now = time.nanoTime();
      if (stopped) {
        windowEnd = Math.min(now, end);
        break;
      }
      if (now >= nextReport) {
        // A report at (or just after) the end of the warm-up closes the warm-up; the first
        // measured report then covers a whole interval.
        boolean warmup = warmupNanos > 0 && now < measureStart + reportIntervalNanos / 2;
        LoadStats current = warmup ? warmupStats : stats;
        reporter.report(now - start, current.snapshot(), inFlight.get(), warmup);
        // After a long pause (suspended host, GC) report once, not once per missed interval.
        while (nextReport <= now) {
          nextReport += reportIntervalNanos;
        }
        continue;
      }
      long due = start + (long) (sequence * nanosPerRequest);
      if (due >= end) {
        windowEnd = end;
        break;
      }
      if (due > now) {
        time.sleepNanos(Math.min(Math.min(due, nextReport) - now, MAX_SLEEP_NANOS));
        continue;
      }
      boolean measured = due >= measureStart;
      if (measured && firstMeasured < 0) {
        firstMeasured = sequence;
      }
      dispatch(schedule, sequence, due, measured ? stats : warmupStats);
      sequence++;
    }
    drain();
    long window = Math.max(0, windowEnd - measureStart);
    long dueMeasured = firstMeasured < 0 ? 0 : sequence - firstMeasured;
    return new Result(window, dueMeasured, inFlight.get());
  }

  /** Stops scheduling; requests in flight still get their answer (up to the request timeout). */
  void stop() {
    stopped = true;
    Thread thread = dispatcher;
    if (thread != null) {
      LockSupport.unpark(thread);
    }
  }

  int inFlight() {
    return inFlight.get();
  }

  private void dispatch(WeightedSchedule schedule, long sequence, long due, LoadStats target) {
    int route = schedule.routeIndex(sequence);
    target.scheduled(route);
    if (inFlight.get() >= maxInFlight) {
      target.dropped(route);
      return;
    }
    inFlight.incrementAndGet();
    long sentAt = time.nanoTime();
    target.sent(route, sentAt - due);
    String path = mix.routes().get(route).path().apply(schedule.ordinal(sequence));
    CompletableFuture<Integer> answer;
    try {
      answer = transport.get(path, requestTimeout);
    } catch (RuntimeException e) {
      finish(target, route, sentAt, null, e);
      return;
    }
    var unused =
        answer.whenComplete((status, failure) -> finish(target, route, sentAt, status, failure));
  }

  private void finish(LoadStats target, int route, long sentAt, Integer status, Throwable failure) {
    try {
      if (failure != null || status == null) {
        target.failed(route, Outcome.ofFailure(failure));
      } else {
        target.completed(route, status, time.nanoTime() - sentAt);
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private void drain() {
    long limit = drainTimeout.toNanos();
    long deadline = time.nanoTime() + (stopped ? Math.min(limit, STOP_DRAIN_NANOS) : limit);
    while (inFlight.get() > 0 && time.nanoTime() < deadline) {
      time.sleepNanos(DRAIN_POLL_NANOS);
    }
  }
}
