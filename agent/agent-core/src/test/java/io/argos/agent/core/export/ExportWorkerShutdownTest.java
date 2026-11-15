/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.Queues;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shutdown hook's part of a {@link QueueExporter} (10 §10.2 "종료 훅") with a scaled bye and flush
 * budget: the sender's state has one owner thread, so the hook thread sends the queued batches only
 * after {@code argos-sender} has left the exporter's loop; {@link ExportWorker#stop} waits at most
 * {@link ExportWorker#QUEUE_EXPORTER_JOIN_MILLIS} for the loop (never a whole slow round) and hands
 * the rest of the stop time to the exporter, so a stop whose exporter takes its whole bye and flush
 * time stays within bye + stop time. The same properties with the real values (bye 1 s, flush 1 s)
 * are {@code ExportWorkerShutdownIntegrationTest} in src/integrationTest (ADR-0010 C-146 (2):
 * wall-clock bounds with real values are not unit tests).
 */
@Tag("req:DSN-02-11")
class ExportWorkerShutdownTest {
  /** The stop time this test gives {@link ExportWorker#stop} (scaled hook flush time). */
  private static final long STOP_MILLIS = 450;

  /** The scaled bye wait of the fake exporter (the gRPC exporter's is 1 s, 10 §10.2). */
  private static final long BYE_MILLIS = 150;

  /**
   * The slow sender round: far longer than the join time, so waiting for it is visible, and long
   * enough that the loop is certainly still in its first round when the join ends, even after a
   * scheduling stall of seconds between the loop's start and the stop (a full parallel build on a
   * shared laptop stalled the test thread for more than the 1.3 s a 1.5 s round left, and the loop
   * returned inside the join). The test releases the round when it ends ({@code released}).
   */
  private static final long SLOW_ROUND_MILLIS = 5_000;

  /**
   * Scheduling slack of the timing bounds: thread hand-over, park overshoot (Windows timer
   * granularity) and a GC pause on a shared development machine. It stays well below what the
   * bounds exist to catch: waiting for the slow round instead of the join costs {@value
   * #SLOW_ROUND_MILLIS} - {@code QUEUE_EXPORTER_JOIN_MILLIS} (200) = 4,800 ms more, and a stop
   * whose flush did not get the stop time less the join (but the whole stop time on top of a long
   * join) up to {@value #STOP_MILLIS} ms more.
   */
  private static final long SLACK_MILLIS = 300;

  /** How long the test waits for the released loop thread to end. */
  private static final long RELEASE_JOIN_MILLIS = 1_000;

  @TempDir File dir;

  /**
   * A queue exporter whose loop takes {@code roundMillis} per round, like a slow sender step, and
   * whose {@link #shutdown} takes the longest time {@code GrpcExporter.shutdown} may take: the bye
   * wait ({@code byeMillis}) and, when the flush is allowed, the whole flush time it is given.
   */
  private static final class SlowLoopExporter implements QueueExporter {
    final long roundMillis;
    final long byeMillis;
    final CountDownLatch running = new CountDownLatch(1);
    volatile Boolean flush;
    volatile long flushMillis = -1;
    volatile long shutdownEnteredNanos;
    volatile Thread shutdownThread;
    volatile Thread loopThread;
    volatile boolean loopActive;
    volatile boolean overlapped;

    /** Test only: ends the current round and the loop, so that no loop thread outlives a test. */
    volatile boolean released;

    SlowLoopExporter(long roundMillis, long byeMillis) {
      this.roundMillis = roundMillis;
      this.byeMillis = byeMillis;
    }

    @Override
    public void run(Queues queues, BooleanSupplier stillRunning) {
      loopThread = Thread.currentThread();
      loopActive = true;
      try {
        running.countDown();
        while (!released && stillRunning.getAsBoolean()) {
          long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(roundMillis);
          // One long round, like a slow step: the stop's unpark ends one short park, not the round.
          while (System.nanoTime() < end && !released) {
            LockSupport.parkNanos(1_000_000L);
          }
        }
      } finally {
        loopActive = false;
      }
    }

    @Override
    public void wake() {}

    @Override
    public void shutdown(long millis, boolean flushAllowed) {
      shutdownEnteredNanos = System.nanoTime();
      shutdownThread = Thread.currentThread();
      flush = flushAllowed;
      flushMillis = millis;
      if (flushAllowed && loopActive) {
        overlapped = true;
      }
      // Like GrpcExporter.shutdown at its longest: the bye wait, then the flush time it was given.
      parkFor(byeMillis);
      if (flushAllowed) {
        parkFor(millis);
      }
    }

    /** Parks until {@code millis} have passed (deadline based: unparks and early wake-ups loop). */
    private static void parkFor(long millis) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
      for (long left = deadline - System.nanoTime();
          left > 0;
          left = deadline - System.nanoTime()) {
        LockSupport.parkNanos(left);
      }
    }

    /** Releases the loop and waits until its thread has ended. */
    void release() throws InterruptedException {
      released = true;
      Thread loop = loopThread;
      if (loop != null) {
        loop.join(RELEASE_JOIN_MILLIS);
        assertThat(loop.isAlive()).as("the released loop thread has ended").isFalse();
      }
    }

    @Override
    public long deliveredTransactions() {
      return 0;
    }

    @Override
    public void export(List<Transaction> batch) {}

    @Override
    public void exportProfiles(List<ProfileRecord> batch) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public String describe() {
      return "slow loop";
    }
  }

  private ExportWorker worker(final SlowLoopExporter exporter) {
    Queues queues = Queues.create(100, 1L << 30, 1L << 30, 0, new AgentCounters(), null);
    return new ExportWorker(
        queues,
        new ExporterContext(
            AgentConfig.of(Collections.<String, String>emptyMap()),
            new Dictionary(),
            new AgentInfo("1", "c", "1")),
        dir,
        500,
        100,
        null,
        new ExportWorker.ExporterSource() {
          @Override
          public Exporter create(ExporterContext context, File logDirectory) {
            return exporter;
          }
        });
  }

  @Test
  @DisplayName(
      "10 §10.2 (scaled bye and flush budget): a sender loop that does not return within the join"
          + " time keeps its state: the hook thread does not flush, enters the exporter's shutdown"
          + " after the 200 ms join (not after the loop's 5 s round), and the stop, with a shutdown"
          + " that takes its whole bye wait (150 ms), stays within bye + stop time (450 ms)")
  void slowLoopIsNotFlushedFromTheHook() throws Exception {
    SlowLoopExporter exporter = new SlowLoopExporter(SLOW_ROUND_MILLIS, BYE_MILLIS);
    ExportWorker worker = worker(exporter);
    try {
      worker.start();
      assertThat(exporter.running.await(10, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      worker.stop(STOP_MILLIS);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertThat(exporter.flush).as("the loop was still running: no flush from the hook").isFalse();
      assertThat(exporter.shutdownThread).isSameAs(Thread.currentThread());
      assertThat(exporter.shutdownThread).isNotSameAs(exporter.loopThread);
      assertThat(exporter.overlapped).isFalse();
      assertThat(TimeUnit.NANOSECONDS.toMillis(exporter.shutdownEnteredNanos - start))
          .as("the exporter's shutdown starts after the join time, not after the slow round")
          .isLessThanOrEqualTo(ExportWorker.QUEUE_EXPORTER_JOIN_MILLIS + SLACK_MILLIS);
      assertThat(elapsed)
          .as("join + the exporter's shutdown (bye) within bye + stop time")
          .isLessThanOrEqualTo(BYE_MILLIS + STOP_MILLIS + SLACK_MILLIS);
      assertThat(exporter.flushMillis)
          .as("the join time is taken from the stop time")
          .isLessThanOrEqualTo(STOP_MILLIS - ExportWorker.QUEUE_EXPORTER_JOIN_MILLIS + 50);
    } finally {
      exporter.release();
    }
  }

  @Test
  @DisplayName(
      "10 §10.2 (scaled bye and flush budget): a sender loop that returns in time hands over: the"
          + " hook thread flushes with the stop time less the join time, and the stop, with a"
          + " shutdown that takes its whole bye wait (150 ms) and flush time, stays within bye +"
          + " stop time (450 ms)")
  void returnedLoopIsFlushedFromTheHook() throws Exception {
    SlowLoopExporter exporter = new SlowLoopExporter(10, BYE_MILLIS);
    ExportWorker worker = worker(exporter);
    try {
      worker.start();
      assertThat(exporter.running.await(10, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      worker.stop(STOP_MILLIS);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertThat(exporter.flush).isTrue();
      assertThat(exporter.overlapped).as("the loop had returned before the flush").isFalse();
      assertThat(exporter.flushMillis).isBetween(0L, STOP_MILLIS);
      assertThat(elapsed)
          .as("join + the exporter's shutdown (bye + the flush time left) within bye + stop time")
          .isLessThanOrEqualTo(BYE_MILLIS + STOP_MILLIS + SLACK_MILLIS);
    } finally {
      exporter.release();
    }
  }
}
