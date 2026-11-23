/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tracer's statistics and active-state hooks (10 §9.3, §11, D-12, C-185 (2), C-225 (1); WO-16):
 * {@code recordSqlStat}, {@code recordSqlFetch}, {@code recordExtStat}/{@code httpEnd}, the DBC
 * pool waits and {@code Transaction.activeState()}.
 */
@Tag("req:F-A22")
@Tag("req:D-12")
class TracerStatsTest {
  private static final long MS = 1_000_000L;

  /** A fixed wall clock: every record lands in one window. */
  private final StatsAggregator stats =
      new StatsAggregator(10, 5000, 1000, () -> 1_700_000_001_000L);

  private final TracerImpl tracer = TracerImpl.builder().stats(stats).maxSteps(2).build();
  private TraceContext open;

  @AfterEach
  void endOpen() {
    if (open != null && !open.isEnded()) {
      tracer.end(open, 200, null, null, null, System.nanoTime());
    }
  }

  private TraceContext begin(int kind) {
    open = tracer.begin(kind, "svc", System.nanoTime());
    return open;
  }

  private static Transaction tx(TraceContext ctx) {
    return (Transaction) ctx.coreRef;
  }

  @Test
  @DisplayName(
      "T-AG-061 SQL statistics carry the kind of the transaction bound to the thread; nothing is"
          + " recorded outside a transaction (D-12)")
  void sqlByKind() {
    tracer.recordSqlStat(1, 2, MS, false, 0);
    TraceContext http = begin(Kind.HTTP);
    tracer.recordSqlStat(1, 2, MS, false, 0);
    tracer.recordSqlStat(1, 2, 2 * MS, true, 0);
    tracer.recordSqlFetch(http, 1, 2, 3 * MS, 42);
    tracer.end(http, 200, null, null, null, System.nanoTime());
    tracer.recordSqlStat(1, 2, MS, false, 0); // after the end: outside a transaction
    TraceContext batch = begin(Kind.BATCH);
    tracer.recordSqlStat(1, 2, MS, false, 0);
    tracer.end(batch, 0, null, null, null, System.nanoTime());
    tracer.recordSqlFetch(null, 1, 2, MS, 1);

    StatBatch window = single(stats.drainAll());
    SqlStat httpRow = row(window, TransactionKind.HTTP);
    assertThat(httpRow.getCount()).isEqualTo(2);
    assertThat(httpRow.getErrorCount()).isEqualTo(1);
    assertThat(httpRow.getFetchRows()).isEqualTo(42);
    assertThat(httpRow.getSumFetchUs()).isEqualTo(3_000);
    assertThat(row(window, TransactionKind.BATCH).getCount()).isEqualTo(1);
    assertThat(window.getSqlCount()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-AG-061 httpEnd records the call with its FailureKind under the context's kind, also when"
          + " the call completes after the transaction ended")
  void extFromHttpEnd() {
    TraceContext ctx = begin(Kind.HTTP);
    long host = tracer.hostId("http", "127.0.0.1", 8080);
    long t0 = System.nanoTime();
    int seq = tracer.httpStart(ctx, host, "GET", "127.0.0.1:8080", "/a", null, t0);
    tracer.httpEnd(ctx, seq, host, 503, null, t0, t0 + 2 * MS);
    int timeout = tracer.httpStart(ctx, host, "GET", "127.0.0.1:8080", "/b", null, t0);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    tracer.httpEnd(
        ctx, timeout, host, 0, new java.net.SocketTimeoutException("Read timed out"), t0, t0 + MS);
    tracer.httpEnd(null, -1, host, 200, null, t0, t0 + MS); // no context: nothing
    tracer.recordExtStat(host, MS, FailureKind.NONE); // no transaction on this thread: nothing

    ExtStat row = single(stats.drainAll()).getExt(0);
    assertThat(row.getHostId()).isEqualTo(host);
    assertThat(row.getKind()).isEqualTo(TransactionKind.HTTP);
    assertThat(row.getCount()).isEqualTo(2);
    assertThat(row.getErrorCount()).isEqualTo(1);
    assertThat(row.getTimeoutCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-E2E-active-31 active state: SQL -> IN_SQL(sql_id), HTTP -> IN_HTTP(host_id), connection"
          + " wait and DBC -> IN_DBC_WAIT(DB_TARGET), back to IN_METHOD when the step ends")
  void activeStates() {
    TraceContext ctx = begin(Kind.HTTP);
    Transaction tx = tx(ctx);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);
    long now = System.nanoTime();

    int sql = tracer.stepStart(ctx, StepType.SQL, 77L, 5L, now);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_SQL);
    assertThat(tx.activeNameId()).isEqualTo(77L);
    tracer.stepEnd(ctx, sql, 0, false, 0, now + MS);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);
    assertThat(tx.activeNameId()).isZero();

    long host = tracer.hostId("http", "example.org", 80);
    int http = tracer.httpStart(ctx, host, "GET", "example.org", "/", null, now);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_HTTP);
    assertThat(tx.activeNameId()).isEqualTo(host);
    tracer.httpEnd(ctx, http, host, 200, null, now, now + MS);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);

    tracer.connectionWait(ctx, true);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_DBC_WAIT);
    tracer.connectionWait(ctx, false);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);

    // maxSteps 2: this step is a summary handle, which has no type; its end still leaves IN_SQL.
    int summarized = tracer.stepStart(ctx, StepType.SQL, 78L, 5L, now);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_SQL);
    tracer.stepEnd(ctx, summarized, 0, false, 0, now + MS);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);
  }

  @Test
  @DisplayName(
      "T-AG-062 the end of a DBC step adds its time to the pool's wait counters"
          + " (jdbc_pool_wait_*, C-68) and leaves IN_DBC_WAIT")
  void dbcPoolWait() {
    TraceContext ctx = begin(Kind.HTTP);
    Transaction tx = tx(ctx);
    long pool = tracer.dict(DictKind.POOL, "HikariPool-1");
    long start = System.nanoTime();
    int dbc = tracer.stepStart(ctx, StepType.DBC, pool, 99L, start);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_DBC_WAIT);
    assertThat(tx.activeNameId()).as("state_name_id = DB_TARGET").isEqualTo(99L);
    tracer.stepEnd(ctx, dbc, 0, false, 0, start + 7 * MS);
    assertThat(tx.activeState()).isEqualTo(Transaction.ACTIVE_IN_METHOD);
    Map<String, long[]> waits = tracer.poolWaits().poolWaits();
    assertThat(waits).containsOnlyKeys("HikariPool-1");
    assertThat(waits.get("HikariPool-1")).containsExactly(1L, 7 * MS);
  }

  private static SqlStat row(StatBatch batch, TransactionKind kind) {
    for (SqlStat row : batch.getSqlList()) {
      if (row.getKind() == kind) {
        return row;
      }
    }
    throw new AssertionError("no row of " + kind + " in " + batch);
  }

  private static StatBatch single(List<StatBatch> batches) {
    assertThat(batches).hasSize(1);
    return batches.get(0);
  }
}
