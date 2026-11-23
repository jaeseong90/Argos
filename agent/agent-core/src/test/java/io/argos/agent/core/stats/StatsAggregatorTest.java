/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.proto.LatencyBuckets;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The full statistics aggregator (10 §9.3, F-A22, D-12; WO-16). */
@Tag("req:F-A22")
@Tag("req:D-12")
class StatsAggregatorTest {
  private static final long MS = 1_000_000L;

  private final AtomicLong now = new AtomicLong(1_700_000_003_000L);

  private StatsAggregator aggregator(int maxSql, int maxExt) {
    return new StatsAggregator(10, maxSql, maxExt, now::get);
  }

  @Test
  @DisplayName(
      "T-AG-061 windows are aligned to the wall clock (floor(epoch / 10 s) * 10 s, window_len_s"
          + " 10) and an empty window is never sent")
  void alignment() {
    StatsAggregator stats = aggregator(5000, 1000);
    assertThat(stats.drain(now.get())).isEmpty();
    assertThat(stats.drainAll()).as("nothing recorded: no batch").isEmpty();
    now.set(1_700_000_012_345L);
    stats.recordSql(Kind.HTTP, 11, 22, 3 * MS, false);
    List<StatBatch> batches = stats.drainAll();
    assertThat(batches).hasSize(1);
    assertThat(batches.get(0).getWindowStartMs()).isEqualTo(1_700_000_010_000L);
    assertThat(batches.get(0).getWindowStartMs() % 10_000).isZero();
    assertThat(batches.get(0).getWindowLenS()).isEqualTo(10);
    assertThat(batches.get(0).getCountersList())
        .as("PM question 1: counters go in metrics")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-061 a window is sent 1 s after its end; records of the next window stay for their own"
          + " flush")
  void flushAfterTheWindowEnds() {
    now.set(1_700_000_001_000L);
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordSql(Kind.HTTP, 1, 2, MS, false);
    stats.recordSql(Kind.HTTP, 1, 2, MS, true);
    assertThat(stats.drain(1_700_000_009_900L)).isEmpty();
    now.set(1_700_000_010_100L);
    stats.recordSql(Kind.HTTP, 1, 2, MS, false); // next window
    assertThat(stats.drain(1_700_000_010_300L)).as("within the grace time").isEmpty();
    assertThat(stats.drain(1_700_000_010_900L)).as("within the grace time").isEmpty();
    List<StatBatch> first = stats.drain(1_700_000_011_200L);
    assertThat(first).hasSize(1);
    assertThat(first.get(0).getWindowStartMs()).isEqualTo(1_700_000_000_000L);
    SqlStat row = first.get(0).getSql(0);
    assertThat(row.getCount()).isEqualTo(2);
    assertThat(row.getErrorCount()).isEqualTo(1);
    assertThat(stats.drain(1_700_000_012_200L)).isEmpty();
    assertThat(stats.drain(1_700_000_020_600L)).as("window 10 s within its grace").isEmpty();
    List<StatBatch> second = stats.drain(1_700_000_021_200L);
    assertThat(second).hasSize(1);
    assertThat(second.get(0).getWindowStartMs()).isEqualTo(1_700_000_010_000L);
    assertThat(second.get(0).getSql(0).getCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-AG-061 a record that comes after its window was detached is counted in the current"
          + " window: the totals stay exact")
  void lateRecordKeepsTheTotal() {
    now.set(1_700_000_001_000L);
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordSql(Kind.HTTP, 1, 2, MS, false);
    List<StatBatch> all = new ArrayList<StatBatch>(stats.drain(1_700_000_011_000L));
    assertThat(sqlCount(all)).as("window 0 sent").isEqualTo(1);
    now.set(1_700_000_005_000L); // a clock read from window 0, recorded late
    stats.recordSql(Kind.HTTP, 1, 2, MS, false);
    all.addAll(stats.drain(1_700_000_012_000L));
    all.addAll(stats.drainAll());
    assertThat(sqlCount(all)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-AG-061 concurrent recording from 8 threads across window changes: the sum over every"
          + " window equals the calls and every row's buckets add up to its count")
  void concurrentRecording() throws Exception {
    final StatsAggregator stats = aggregator(5000, 1000);
    final int threads = 8;
    final int perThread = 50_000;
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final AtomicBoolean flushing = new AtomicBoolean(true);
    final List<StatBatch> sent = new ArrayList<StatBatch>();
    for (int t = 0; t < threads; t++) {
      final int id = t;
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    if ((i & 1) == 0) {
                      stats.recordSql(Kind.HTTP, 100 + (i % 3), 7, (i % 50) * MS, i % 97 == 0);
                    } else {
                      stats.recordExt(Kind.HTTP, 900 + (id % 2), (i % 40) * MS, i % 5);
                    }
                    if (i % 5_000 == 0) {
                      now.addAndGet(1_000L); // the wall clock moves across windows meanwhile
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
      worker.start();
    }
    Thread flusher =
        new Thread(
            () -> {
              while (flushing.get()) {
                List<StatBatch> batches = stats.drain(now.get());
                synchronized (sent) {
                  sent.addAll(batches);
                }
              }
            });
    flusher.start();
    start.countDown();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    flushing.set(false);
    flusher.join();
    sent.addAll(stats.drainAll());
    long calls = (long) threads * perThread;
    assertThat(sqlCount(sent) + extCount(sent))
        .as("windows let go by the bound of 8 meanwhile: %d", stats.windowsDropped())
        .isEqualTo(calls);
    assertThat(sqlCount(sent)).isEqualTo(calls / 2);
    assertThat(sent.size()).as("several windows").isGreaterThan(1);
    for (StatBatch batch : sent) {
      for (SqlStat row : batch.getSqlList()) {
        assertThat(row.getBucketsList()).hasSize(LatencyBuckets.COUNT);
        assertThat(sum(row.getBucketsList())).isEqualTo(row.getCount());
      }
      for (ExtStat row : batch.getExtList()) {
        assertThat(row.getBucketsList()).hasSize(LatencyBuckets.COUNT);
        assertThat(sum(row.getBucketsList())).isEqualTo(row.getCount());
      }
    }
    assertThat(stats.sqlOverflow()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-061 SQL keys are (sql_id, db_id, kind): the transaction kinds are separate rows (D-20)")
  void kindIsPartOfTheKey() {
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordSql(Kind.HTTP, 5, 6, MS, false);
    stats.recordSql(Kind.BATCH, 5, 6, MS, false);
    stats.recordSql(Kind.HTTP, 5, 7, MS, false);
    stats.recordExt(Kind.HTTP, 9, MS, FailureKind.NONE);
    stats.recordExt(Kind.BATCH, 9, MS, FailureKind.NONE);
    StatBatch batch = single(stats.drainAll());
    assertThat(batch.getSqlList()).hasSize(3);
    assertThat(batch.getSqlList())
        .anyMatch(r -> r.getKind() == TransactionKind.BATCH && r.getDbId() == 6);
    assertThat(batch.getExtList()).hasSize(2);
  }

  @Test
  @DisplayName(
      "T-AG-061 values: sum_us = sum_exec_us + sum_fetch_us, max_us, buckets by execute time,"
          + " fetch rows and time added without counting an execution (C-185 (2))")
  void sqlValues() {
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordSql(Kind.HTTP, 1, 2, 3 * MS, false); // bucket <= 5 ms
    stats.recordSql(Kind.HTTP, 1, 2, 40 * MS, true); // bucket <= 50 ms
    stats.recordSqlFetch(Kind.HTTP, 1, 2, 2 * MS, 100);
    stats.recordSqlFetch(Kind.HTTP, 1, 2, MS, 20);
    stats.recordSqlFetch(Kind.HTTP, 1, 2, 0, 0); // nothing
    SqlStat row = single(stats.drainAll()).getSql(0);
    assertThat(row.getCount()).isEqualTo(2);
    assertThat(row.getErrorCount()).isEqualTo(1);
    assertThat(row.getSumExecUs()).isEqualTo(43_000);
    assertThat(row.getSumFetchUs()).isEqualTo(3_000);
    assertThat(row.getSumUs()).isEqualTo(46_000);
    assertThat(row.getMaxUs()).isEqualTo(40_000);
    assertThat(row.getFetchRows()).isEqualTo(120);
    assertThat(row.hasSumExecUs()).isTrue();
    assertThat(row.hasSumFetchUs()).isTrue();
    assertThat(row.getBuckets(LatencyBuckets.indexOfMs(3))).isEqualTo(1);
    assertThat(row.getBuckets(LatencyBuckets.indexOfMs(40))).isEqualTo(1);
    assertThat(sum(row.getBucketsList())).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-AG-061 external calls: TIMEOUT and CONNECT_FAIL are counted exactly, 5xx and other"
          + " exceptions are errors")
  void extFailures() {
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordExt(Kind.HTTP, 9, 2 * MS, FailureKind.NONE);
    stats.recordExt(Kind.HTTP, 9, 1000 * MS, FailureKind.TIMEOUT);
    stats.recordExt(Kind.HTTP, 9, 1000 * MS, FailureKind.TIMEOUT);
    stats.recordExt(Kind.HTTP, 9, MS, FailureKind.CONNECT_FAIL);
    stats.recordExt(Kind.HTTP, 9, 3 * MS, FailureKind.HTTP_5XX);
    stats.recordExt(Kind.HTTP, 9, 4 * MS, FailureKind.OTHER);
    ExtStat row = single(stats.drainAll()).getExt(0);
    assertThat(row.getHostId()).isEqualTo(9);
    assertThat(row.getKind()).isEqualTo(TransactionKind.HTTP);
    assertThat(row.getCount()).isEqualTo(6);
    assertThat(row.getTimeoutCount()).isEqualTo(2);
    assertThat(row.getConnectFailCount()).isEqualTo(1);
    assertThat(row.getErrorCount()).isEqualTo(2);
    assertThat(row.getSumUs()).isEqualTo(2_010_000);
    assertThat(row.getMaxUs()).isEqualTo(1_000_000);
    assertThat(sum(row.getBucketsList())).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "T-AG-061 caps: keys beyond stats.max-keys (SQL) and 1,000 (EXT) go to the id-0 overflow"
          + " key of their kind and count argos_agent_stats_overflow_total{kind}")
  void capsAndOverflow() {
    StatsAggregator stats = aggregator(3, 2);
    for (long id = 1; id <= 5; id++) {
      stats.recordSql(Kind.HTTP, id, 1, MS, false);
      stats.recordExt(Kind.HTTP, 100 + id, MS, FailureKind.NONE);
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false); // an admitted key keeps counting
    StatBatch batch = single(stats.drainAll());
    assertThat(batch.getSqlList()).hasSize(4);
    SqlStat overflow = batch.getSqlList().stream().filter(r -> r.getSqlId() == 0).findFirst().get();
    assertThat(overflow.getCount()).isEqualTo(2);
    assertThat(overflow.getDbId()).isZero();
    assertThat(sqlCount(java.util.Collections.singletonList(batch))).isEqualTo(6);
    assertThat(batch.getExtList()).hasSize(3);
    assertThat(batch.getExtList().stream().filter(r -> r.getHostId() == 0).findFirst())
        .get()
        .extracting(ExtStat::getCount)
        .isEqualTo(3);
    assertThat(stats.counters())
        .containsEntry("argos_agent_stats_overflow_total{kind=\"sql\"}", 2L)
        .containsEntry("argos_agent_stats_overflow_total{kind=\"ext\"}", 3L);
    // A new window admits new keys again.
    stats.recordSql(Kind.HTTP, 42, 1, MS, false);
    assertThat(single(stats.drainAll()).getSql(0).getSqlId()).isEqualTo(42);
  }

  @Test
  @DisplayName(
      "T-AG-061 a window with more rows than one StatBatch may carry (5,000 SQL) is split into"
          + " batches of the same window")
  void largeWindowIsSplit() {
    StatsAggregator stats = aggregator(5000, 1000);
    for (long id = 1; id <= 5000; id++) {
      stats.recordSql(Kind.HTTP, id, 1, MS, false);
    }
    stats.recordSql(Kind.BATCH, 7, 1, MS, false); // over the cap: overflow row of BATCH
    List<StatBatch> batches = stats.drainAll();
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0).getSqlCount()).isEqualTo(5000);
    assertThat(batches.get(1).getSqlCount()).isEqualTo(1);
    assertThat(batches.get(1).getWindowStartMs()).isEqualTo(batches.get(0).getWindowStartMs());
    assertThat(sqlCount(batches)).isEqualTo(5001);
  }

  @Test
  @DisplayName(
      "T-AG-061 without a flush (argos-metrics not running: no drain within 30 s of the creation)"
          + " at most 8 windows stay linked: the windows kept are bounded (10 §14)")
  void windowsStayBoundedWithoutAFlush() {
    StatsAggregator one = aggregator(5000, 1000);
    one.recordSql(Kind.HTTP, 1, 1, MS, false);
    long cellBytes = one.estimatedBytes();
    assertThat(cellBytes).isPositive();

    AtomicLong nanos = new AtomicLong(0L);
    StatsAggregator stats = new StatsAggregator(10, 5000, 1000, now::get, nanos::get);
    nanos.set(StatsAggregator.FLUSH_ALIVE_NANOS);
    for (int w = 0; w < 40; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    // One cell per window: the estimate counts the windows still linked.
    assertThat(stats.estimatedBytes() / cellBytes).isEqualTo(StatsAggregator.MAX_ATTACHED_WINDOWS);
    // The newest windows are the ones kept and drained.
    List<StatBatch> batches = stats.drainAll();
    assertThat(batches).hasSize(StatsAggregator.MAX_ATTACHED_WINDOWS);
    assertThat(sqlCount(batches)).isEqualTo(StatsAggregator.MAX_ATTACHED_WINDOWS);
    // C-229 (7): every window let go by the bound is counted once; kept + dropped = all 41.
    assertThat(stats.windowsDropped()).isEqualTo(41 - StatsAggregator.MAX_ATTACHED_WINDOWS);
    assertThat(stats.counters())
        .containsEntry(
            StatsAggregator.WINDOWS_DROPPED_METRIC,
            (long) (41 - StatsAggregator.MAX_ATTACHED_WINDOWS));
  }

  @Test
  @DisplayName(
      "T-AG-061 a running flush that fell behind (not scheduled while 12 windows went by) loses no"
          + " window; while it runs at most 16 windows stay linked (ADR-0010 C-230 (2))")
  void runningFlushThatFellBehindLosesNothing() {
    AtomicLong nanos = new AtomicLong(5_000_000_000L);
    StatsAggregator one = aggregator(5000, 1000);
    one.recordSql(Kind.HTTP, 1, 1, MS, false);
    long cellBytes = one.estimatedBytes();

    StatsAggregator stats = new StatsAggregator(10, 5000, 1000, now::get, nanos::get);
    assertThat(stats.drain(now.get())).as("the flush runs").isEmpty();
    for (int w = 0; w < 12; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
      nanos.addAndGet(1_000_000L); // 12 windows of the test clock within 12 ms of real time
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    assertThat(stats.estimatedBytes() / cellBytes).as("all 13 windows linked").isEqualTo(13);
    List<StatBatch> sent = new ArrayList<StatBatch>(stats.drain(now.get() + 2_000L));
    sent.addAll(stats.drainAll());
    assertThat(sqlCount(sent)).isEqualTo(13);
    assertThat(stats.windowsDropped()).isZero();

    // Bounded while the flush runs: 41 windows without a drain keep the newest 16.
    for (int w = 0; w < 40; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    assertThat(stats.estimatedBytes() / cellBytes).isEqualTo(StatsAggregator.MAX_LINKED_WINDOWS);
    assertThat(sqlCount(stats.drainAll())).isEqualTo(StatsAggregator.MAX_LINKED_WINDOWS);
    assertThat(stats.windowsDropped()).isEqualTo(41 - StatsAggregator.MAX_LINKED_WINDOWS);
  }

  @Test
  @DisplayName(
      "T-AG-061 a flush whose first drain comes late (12 windows of the test clock before it, within"
          + " 30 s of real time) loses no window")
  void lateFirstDrainLosesNothing() {
    AtomicLong nanos = new AtomicLong(0L);
    StatsAggregator stats = new StatsAggregator(10, 5000, 1000, now::get, nanos::get);
    for (int w = 0; w < 12; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
      nanos.addAndGet(1_000_000L);
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    List<StatBatch> sent = new ArrayList<StatBatch>(stats.drain(now.get() + 2_000L));
    sent.addAll(stats.drainAll());
    assertThat(sqlCount(sent)).isEqualTo(13);
    assertThat(stats.windowsDropped()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-061 a flush whose last drain began 30 s ago (real time) no longer counts as running: the"
          + " bound of 8 linked windows applies again")
  void stoppedFlushBoundsWindowsAgain() {
    AtomicLong nanos = new AtomicLong(0L);
    StatsAggregator stats = new StatsAggregator(10, 5000, 1000, now::get, nanos::get);
    assertThat(stats.drain(now.get())).isEmpty();
    nanos.set(StatsAggregator.FLUSH_ALIVE_NANOS);
    for (int w = 0; w < 40; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
    }
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    assertThat(sqlCount(stats.drainAll())).isEqualTo(StatsAggregator.MAX_ATTACHED_WINDOWS);
    assertThat(stats.windowsDropped()).isEqualTo(41 - StatsAggregator.MAX_ATTACHED_WINDOWS);
  }

  @Test
  @DisplayName(
      "T-AG-061 with the flush running no window is dropped: argos_agent_stats_windows_dropped_total"
          + " stays 0 (C-229 (7))")
  void noWindowDroppedWithAFlush() {
    StatsAggregator stats = aggregator(5000, 1000);
    long sent = 0;
    for (int w = 0; w < 40; w++) {
      stats.recordSql(Kind.HTTP, 1, 1, MS, false);
      now.addAndGet(10_000L);
      sent += sqlCount(stats.drain(now.get() + 2_000L));
    }
    sent += sqlCount(stats.drainAll());
    assertThat(sent).isEqualTo(40);
    assertThat(stats.windowsDropped()).isZero();
    assertThat(stats.counters()).containsEntry(StatsAggregator.WINDOWS_DROPPED_METRIC, 0L);
  }

  @Test
  @DisplayName(
      "T-AG-061 argos_agent_stats_overflow_total{kind=sql} counts an execution over the cap once:"
          + " its fetch record adds rows and fetch time to the overflow key but no count (C-229 (6))")
  void overflowCountsAnExecutionOnce() {
    StatsAggregator stats = aggregator(1, 10);
    stats.recordSql(Kind.HTTP, 1, 1, MS, false);
    stats.recordSqlFetch(Kind.HTTP, 1, 1, 2 * MS, 10);
    // Over the cap: the execution and the fetch of its result set.
    stats.recordSql(Kind.HTTP, 2, 1, MS, false);
    stats.recordSqlFetch(Kind.HTTP, 2, 1, 3 * MS, 7);
    stats.recordSql(Kind.HTTP, 3, 1, MS, false);
    stats.recordSqlFetch(Kind.HTTP, 3, 1, 0, 4);
    assertThat(stats.sqlOverflow()).isEqualTo(2);
    assertThat(stats.counters())
        .containsEntry("argos_agent_stats_overflow_total{kind=\"sql\"}", 2L);
    SqlStat overflow =
        single(stats.drainAll()).getSqlList().stream()
            .filter(r -> r.getSqlId() == 0)
            .findFirst()
            .get();
    assertThat(overflow.getCount()).isEqualTo(2);
    assertThat(overflow.getFetchRows()).isEqualTo(11);
  }

  @Test
  @DisplayName("T-AG-061 unknown transaction kinds are kept as TRANSACTION_KIND_UNSPECIFIED")
  void unknownKind() {
    StatsAggregator stats = aggregator(5000, 1000);
    stats.recordSql(77, 1, 1, MS, false);
    assertThat(single(stats.drainAll()).getSql(0).getKind())
        .isEqualTo(TransactionKind.TRANSACTION_KIND_UNSPECIFIED);
  }

  static long sqlCount(List<StatBatch> batches) {
    long total = 0;
    for (StatBatch batch : batches) {
      for (SqlStat row : batch.getSqlList()) {
        total += row.getCount();
      }
    }
    return total;
  }

  static long extCount(List<StatBatch> batches) {
    long total = 0;
    for (StatBatch batch : batches) {
      for (ExtStat row : batch.getExtList()) {
        total += row.getCount();
      }
    }
    return total;
  }

  private static long sum(List<Integer> values) {
    long total = 0;
    for (int v : values) {
      total += Integer.toUnsignedLong(v);
    }
    return total;
  }

  private static StatBatch single(List<StatBatch> batches) {
    assertThat(batches).hasSize(1);
    return batches.get(0);
  }
}
