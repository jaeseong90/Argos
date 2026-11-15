/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.TraceClock;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code transaction.max-duration-ms} on the watch tick (10 §6.7, §13.1, F-A34). */
@Tag("req:F-A34")
class MaxDurationEnforcerTest {
  /** A clock the test moves. */
  static final class ManualClock implements TraceClock {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    void advance(long delta) {
      nanos.addAndGet(delta);
    }

    @Override
    public long nanoTime() {
      return nanos.get();
    }

    @Override
    public long currentTimeMillis() {
      return 1_800_000_000_000L + TimeUnit.NANOSECONDS.toMillis(nanos.get());
    }
  }

  @Test
  @DisplayName(
      "T-AG-016b max-duration: a transaction older than the limit is ended with timed_out, the"
          + " leaked flag and its status (599 without one), removed from the registry; younger"
          + " ones stay; a later end by the instrumentation is ignored")
  void endsTransactionsOverTheLimit() {
    ManualClock clock = new ManualClock();
    AgentCounters counters = new AgentCounters();
    ActiveRegistry registry = new ActiveRegistry(16);
    final List<Transaction> ended = new ArrayList<Transaction>();
    TracerImpl tracer =
        TracerImpl.builder()
            .clock(clock)
            .counters(counters)
            .activeRegistry(registry)
            .sink(
                new TracerImpl.Sink() {
                  @Override
                  public void accept(Transaction tx) {
                    ended.add(tx);
                  }
                })
            .build();
    MaxDurationEnforcer enforcer = new MaxDurationEnforcer(registry, tracer, clock, 3_600_000L);

    TraceContext stream = tracer.begin(Kind.HTTP, "GET /sse", clock.nanoTime());
    stream.status = 200;
    tracer.activate(null);
    TraceContext hung = tracer.begin(Kind.HTTP, "GET /hung", clock.nanoTime());
    tracer.activate(null);
    clock.advance(TimeUnit.MINUTES.toNanos(30));
    TraceContext young = tracer.begin(Kind.HTTP, "GET /young", clock.nanoTime());
    tracer.activate(null);
    assertThat(registry.size()).isEqualTo(3);

    clock.advance(TimeUnit.MINUTES.toNanos(30)); // the first two are exactly at the limit
    enforcer.run();
    assertThat(ended).isEmpty();

    clock.advance(TimeUnit.SECONDS.toNanos(1));
    enforcer.run();
    assertThat(ended).hasSize(2);
    assertThat(stream.timedOut).isTrue();
    assertThat(stream.status).isEqualTo(200);
    assertThat(stream.error).isFalse();
    assertThat(stream.hasFlags(TraceContext.FLAG_LEAKED)).isTrue();
    assertThat(hung.timedOut).isTrue();
    assertThat(hung.status).isEqualTo(TracerImpl.LEAKED_STATUS);
    assertThat(hung.error).isTrue();
    assertThat(young.isEnded()).isFalse();
    assertThat(young.timedOut).isFalse();
    assertThat(registry.size()).isEqualTo(1);
    assertThat(counters.transactionsLeaked()).isEqualTo(2);
    assertThat(ended.get(0).elapsedNanos()).isGreaterThan(TimeUnit.HOURS.toNanos(1));

    // The instrumentation's own end comes later: ignored (one end per transaction).
    tracer.end(stream, 200, null, null, null, clock.nanoTime());
    assertThat(ended).hasSize(2);

    // A shorter limit (configuration change) applies on the next tick.
    enforcer.maxDurationMs(1000);
    clock.advance(TimeUnit.SECONDS.toNanos(2));
    enforcer.run();
    assertThat(young.isEnded()).isTrue();
    assertThat(registry.size()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-016b max-duration after another thread's end: no timed_out, no leaked flag, no"
          + " leaked_total; endStreaming after it sets no streaming flag either")
  void lostEndChangesNothing() throws Exception {
    ManualClock clock = new ManualClock();
    AgentCounters counters = new AgentCounters();
    ActiveRegistry registry = new ActiveRegistry(16);
    final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
    final TracerImpl tracer = tracer(clock, counters, registry, ended);
    MaxDurationEnforcer enforcer = new MaxDurationEnforcer(registry, tracer, clock, 1L);

    final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /sse", clock.nanoTime());
    tracer.activate(null);
    Thread other =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                tracer.end(ctx, 200, null, null, null, System.nanoTime());
              }
            },
            "instrumentation-end");
    other.start();
    other.join(10_000);
    clock.advance(TimeUnit.SECONDS.toNanos(5));

    assertThat(enforcer.end(ctx, clock.nanoTime())).isFalse();
    tracer.endStreaming(ctx, 200, null, clock.nanoTime());
    assertThat(ended).hasSize(1);
    assertThat(ctx.status).isEqualTo(200);
    assertThat(ctx.timedOut).isFalse();
    assertThat(ctx.streaming).isFalse();
    assertThat(ctx.hasFlags(TraceContext.FLAG_LEAKED)).isFalse();
    assertThat(counters.transactionsLeaked()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-016b max-duration racing the instrumentation's end (300 rounds): one end per"
          + " transaction; timed_out, the leaked flag and leaked_total (exactly +1) only when the"
          + " enforcer won")
  void raceAppliesFlagsToTheWinnerOnly() throws Exception {
    ManualClock clock = new ManualClock();
    final AgentCounters counters = new AgentCounters();
    ActiveRegistry registry = new ActiveRegistry(16);
    final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
    final TracerImpl tracer = tracer(clock, counters, registry, ended);
    final MaxDurationEnforcer enforcer = new MaxDurationEnforcer(registry, tracer, clock, 1L);
    int enforcerWins = 0;
    for (int round = 0; round < 300; round++) {
      final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /race", clock.nanoTime());
      ctx.status = 200;
      tracer.activate(null);
      final long now = clock.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      final CountDownLatch start = new CountDownLatch(1);
      final AtomicBoolean won = new AtomicBoolean();
      final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
      long leakedBefore = counters.transactionsLeaked();
      Thread watch =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  won.set(enforcer.end(ctx, now));
                }
              },
              "argos-watch-test");
      Thread instrumentation =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  try {
                    tracer.end(ctx, 201, null, null, null, now);
                  } catch (Throwable t) {
                    failure.set(t);
                  }
                }
              },
              "instrumentation-end");
      watch.start();
      instrumentation.start();
      start.countDown();
      watch.join(10_000);
      instrumentation.join(10_000);
      assertThat(failure.get()).isNull();
      assertThat(ended).as("round " + round).hasSize(round + 1);
      boolean enforcerWon = won.get();
      if (enforcerWon) {
        enforcerWins++;
      }
      assertThat(ctx.timedOut).as("round " + round).isEqualTo(enforcerWon);
      assertThat(ctx.hasFlags(TraceContext.FLAG_LEAKED))
          .as("round " + round)
          .isEqualTo(enforcerWon);
      assertThat(ctx.status).as("round " + round).isEqualTo(enforcerWon ? 200 : 201);
      assertThat(counters.transactionsLeaked() - leakedBefore)
          .as("round " + round)
          .isEqualTo(enforcerWon ? 1 : 0);
    }
    assertThat(counters.transactionsLeaked()).isEqualTo(enforcerWins);
    assertThat(registry.size()).isZero();
  }

  private static TracerImpl tracer(
      TraceClock clock,
      AgentCounters counters,
      ActiveRegistry registry,
      final List<Transaction> ended) {
    return TracerImpl.builder()
        .clock(clock)
        .counters(counters)
        .activeRegistry(registry)
        .sink(
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                ended.add(tx);
              }
            })
        .build();
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
