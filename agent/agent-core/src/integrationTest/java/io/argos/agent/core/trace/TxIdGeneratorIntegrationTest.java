/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ACC-11 (18 §10, D-11, 10 §9.5, ADR-0010 C-91) at full size: 10,000,000 {@code tx_id}s from 8
 * threads without a collision, the time prefix restored within 1 ms of the moment each sampled id
 * was made, the same through {@link TracerImpl#begin} for every transaction ({@code tx_id >> 23} =
 * {@code start_ts} within 1 ms, also after a wait for the next millisecond), and two generators
 * with different node ids running at the same time without a collision. Real clock, real waits
 * ({@code LockSupport.parkNanos}): one JVM makes at most 2,048 ids per millisecond, and every wait
 * lasts one park (one timer tick, about 15.6 ms, on Windows), so a test takes from seconds (Linux)
 * to about 80 s (10 million ids on Windows).
 */
@Tag("slow")
@Tag("req:D-11")
class TxIdGeneratorIntegrationTest {
  private static final int THREADS = 8;
  private static final int TOTAL = 10_000_000;
  private static final int SAMPLE_EVERY = 1_000;

  /** Ids of one thread and (before, id, after) wall times of every 1,000th id. */
  private static final class Run {
    final long[] ids;
    final long[] samples;

    Run(int count) {
      ids = new long[count];
      samples = new long[3 * (count / SAMPLE_EVERY + 1)];
    }
  }

  private static List<Run> generate(TxIdGenerator[] generators, int threads, int perThread)
      throws InterruptedException {
    final List<Run> runs = new ArrayList<Run>();
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<Thread>();
    for (int t = 0; t < threads; t++) {
      final Run run = new Run(perThread);
      final TxIdGenerator generator = generators[t % generators.length];
      runs.add(run);
      Thread worker =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  int sample = 0;
                  for (int i = 0; i < run.ids.length; i++) {
                    if (i % SAMPLE_EVERY == 0) {
                      long before = System.currentTimeMillis();
                      long id = generator.next();
                      long after = System.currentTimeMillis();
                      run.ids[i] = id;
                      run.samples[sample++] = before;
                      run.samples[sample++] = id;
                      run.samples[sample++] = after;
                    } else {
                      run.ids[i] = generator.next();
                    }
                  }
                }
              },
              "tx-id-" + t);
      workers.add(worker);
      worker.start();
    }
    start.countDown();
    for (Thread worker : workers) {
      worker.join(TimeUnit.MINUTES.toMillis(5));
    }
    return runs;
  }

  private static long duplicates(List<Run> runs) {
    int total = 0;
    for (Run run : runs) {
      total += run.ids.length;
    }
    long[] all = new long[total];
    int offset = 0;
    for (Run run : runs) {
      System.arraycopy(run.ids, 0, all, offset, run.ids.length);
      offset += run.ids.length;
    }
    Arrays.sort(all);
    long duplicates = 0;
    for (int i = 1; i < all.length; i++) {
      if (all[i] == all[i - 1]) {
        duplicates++;
      }
    }
    return duplicates;
  }

  @Test
  @DisplayName(
      "T-ACC-11 10,000,000 tx_ids from 8 threads: 0 collisions, tx_id >> 23 restores the creation"
          + " time within 1 ms")
  void tenMillionIds() throws Exception {
    TxIdGenerator generator = new TxIdGenerator();
    long began = System.nanoTime();
    List<Run> runs = generate(new TxIdGenerator[] {generator}, THREADS, TOTAL / THREADS);
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    long node = generator.nodeId();
    int samples = 0;
    int outside = 0;
    int otherNode = 0;
    for (Run run : runs) {
      for (int s = 0; s + 2 < run.samples.length; s += 3) {
        if (run.samples[s + 1] == 0) {
          continue;
        }
        long restored = TxIdGenerator.epochMillisOf(run.samples[s + 1]);
        samples++;
        if (restored < run.samples[s] - 1 || restored > run.samples[s + 2] + 1) {
          outside++;
        }
      }
      for (long id : run.ids) {
        if (TxIdGenerator.nodeOf(id) != node) {
          otherNode++;
        }
      }
    }
    long duplicates = duplicates(runs);
    System.out.println(
        "[T-ACC-11] "
            + TOTAL
            + " ids, 8 threads, "
            + millis
            + " ms, waits for the next ms "
            + generator.waits()
            + ", samples "
            + samples
            + " (reference values, shared machine)");
    assertThat(duplicates).as("collisions").isZero();
    assertThat(samples).isEqualTo(TOTAL / SAMPLE_EVERY);
    assertThat(outside).as("samples whose time prefix is off by more than 1 ms").isZero();
    assertThat(otherNode).isZero();
  }

  /** What one thread of {@link #beginPath()} saw. */
  private static final class BeginRun {
    long transactions;
    long outside;
    long maxDeviation;
    long firstOutsideTxId;
    long firstOutsideStart;
    long outsideClock;
    long firstClockBefore;
    long firstClockStart;
    long firstClockAfter;
    Throwable failure;
  }

  @Test
  @DisplayName(
      "T-ACC-11 begin path: 8 threads x 400,000 TracerImpl.begin/end, tx_id >> 23 = start_ts and"
          + " start_ts within [before-1, after+1] of the wall clock around begin, including"
          + " transactions whose id waited for the next ms")
  void beginPath() throws Exception {
    final int perThread = 400_000;
    final TxIdGenerator generator = new TxIdGenerator();
    // No sink and no queues: the ended transactions are dropped (the exporter is not under test).
    final TracerImpl tracer = TracerImpl.builder().txIds(generator).build();
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<Thread>();
    final List<BeginRun> runs = new ArrayList<BeginRun>();
    for (int t = 0; t < THREADS; t++) {
      final BeginRun run = new BeginRun();
      runs.add(run);
      Thread worker =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                      long before = System.currentTimeMillis();
                      TraceContext ctx =
                          tracer.begin(Kind.HTTP, "GET /t-acc-11", System.nanoTime());
                      long after = System.currentTimeMillis();
                      // Structure: start_ts is the time prefix of tx_id (one clock read, ADR-0010
                      // C-121), so the two are equal. The wall clock is checked below.
                      long deviation =
                          Math.abs(TxIdGenerator.epochMillisOf(ctx.txId) - ctx.startEpochMs);
                      if (deviation != 0) {
                        if (run.outside == 0) {
                          run.firstOutsideTxId = ctx.txId;
                          run.firstOutsideStart = ctx.startEpochMs;
                        }
                        run.outside++;
                      }
                      if (deviation > run.maxDeviation) {
                        run.maxDeviation = deviation;
                      }
                      // Wall clock: TxIdGenerator.next() reads its state first and the clock
                      // after, so unless the clock steps back start_ts lies in [before, after];
                      // the millisecond after the wait of 10 §9.5 is at most after as well.
                      if (ctx.startEpochMs < before - 1 || ctx.startEpochMs > after + 1) {
                        if (run.outsideClock == 0) {
                          run.firstClockBefore = before;
                          run.firstClockStart = ctx.startEpochMs;
                          run.firstClockAfter = after;
                        }
                        run.outsideClock++;
                      }
                      tracer.end(ctx, 200, null, null, null, System.nanoTime());
                      run.transactions++;
                    }
                  } catch (Throwable e) {
                    run.failure = e;
                  }
                }
              },
              "t-acc-11-begin-" + t);
      workers.add(worker);
      worker.start();
    }
    long began = System.nanoTime();
    start.countDown();
    for (Thread worker : workers) {
      worker.join(TimeUnit.MINUTES.toMillis(8));
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    long transactions = 0;
    long outside = 0;
    long outsideClock = 0;
    long maxDeviation = 0;
    String firstOutside = "none";
    String firstOutsideClock = "none";
    for (BeginRun run : runs) {
      assertThat(run.failure).isNull();
      transactions += run.transactions;
      outside += run.outside;
      outsideClock += run.outsideClock;
      maxDeviation = Math.max(maxDeviation, run.maxDeviation);
      if (run.outsideClock > 0 && "none".equals(firstOutsideClock)) {
        firstOutsideClock =
            "before "
                + run.firstClockBefore
                + ", start_ts "
                + run.firstClockStart
                + ", after "
                + run.firstClockAfter;
      }
      if (run.outside > 0 && "none".equals(firstOutside)) {
        firstOutside =
            "tx_id ms "
                + TxIdGenerator.epochMillisOf(run.firstOutsideTxId)
                + ", start_ts "
                + run.firstOutsideStart;
      }
    }
    System.out.println(
        "[T-ACC-11 begin path] "
            + transactions
            + " transactions, 8 threads, "
            + millis
            + " ms, waits for the next ms "
            + generator.waits()
            + ", max |tx_id ms - start_ts| "
            + maxDeviation
            + " ms, start_ts outside [before-1, after+1] "
            + outsideClock
            + " (reference values, shared machine)");
    assertThat(transactions).isEqualTo((long) THREADS * perThread);
    assertThat(generator.waits())
        .as("the ids used up milliseconds, so begin went through the wait of 10 §9.5")
        .isPositive();
    assertThat(outside)
        .as(
            "transactions whose tx_id >> 23 differs from start_ts (structure check), first: "
                + firstOutside)
        .isZero();
    assertThat(outsideClock)
        .as(
            "transactions whose start_ts lies outside [before-1, after+1] of the wall clock read"
                + " around begin, first: "
                + firstOutsideClock)
        .isZero();
  }

  @Test
  @DisplayName(
      "T-ACC-11 two instances (node ids 1 and 2) generating at the same time: 0 collisions")
  void twoInstances() throws Exception {
    TxIdGenerator first = new TxIdGenerator(1);
    TxIdGenerator second = new TxIdGenerator(2);
    List<Run> runs = generate(new TxIdGenerator[] {first, second}, THREADS, 4_000_000 / THREADS);
    assertThat(duplicates(runs)).as("collisions between the instances").isZero();
    int nodeOne = 0;
    for (Run run : runs) {
      for (long id : run.ids) {
        if (TxIdGenerator.nodeOf(id) == 1) {
          nodeOne++;
        }
      }
    }
    assertThat(nodeOne).isEqualTo(2_000_000);
  }
}
