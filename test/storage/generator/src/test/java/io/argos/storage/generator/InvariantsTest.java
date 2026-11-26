/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.generator.GeneratorConfig.StatWindow;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.model.TransactionFactory;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.generator.sim.Simulation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the generated data that the storage tests rely on: D-12 by construction, the
 * manifest equals what was emitted, midnight crossing, timestamp guard, grids, profile steps
 * consistent with the transaction, version switch with a rolling restart.
 */
class InvariantsTest {

  private static SimulationFixture f;

  @BeforeAll
  static void simulate() throws InterruptedException {
    f = new SimulationFixture(SimulationFixture.defaultConfig());
  }

  @Test
  @DisplayName("No invariant violation was seen while the rows streamed out")
  void noViolations() {
    assertThat(f.violations).isEmpty();
    assertThat(f.receivedOutOfOrder).isZero();
  }

  @Test
  @DisplayName("D-12: sum of sql_count / sql_time / ext_count equals the statistics totals")
  void d12Totals() {
    assertThat(f.statSqlCount).isEqualTo(f.sqlCount).isPositive();
    assertThat(f.statSqlMs).isEqualTo(f.sqlTimeMs);
    assertThat(f.statExtCount).isEqualTo(f.extCount).isPositive();
    assertThat(f.manifest.xlogSqlCount()).isEqualTo(f.sqlCount);
    assertThat(f.manifest.statSqlCount()).isEqualTo(f.statSqlCount);
    assertThat(f.manifest.statExtCount()).isEqualTo(f.statExtCount);
  }

  @Test
  @DisplayName("Manifest counts equal the rows handed to the loader, per table")
  void manifestCounts() {
    assertThat(f.manifest.rows(Table.XLOG)).isEqualTo(f.xlogRows);
    assertThat(f.manifest.rows(Table.PROFILE_STEP)).isEqualTo(f.profileRows);
    assertThat(f.manifest.rows(Table.METRIC)).isEqualTo(f.metricRows);
    assertThat(f.manifest.rows(Table.SQL_STAT_RAW)).isEqualTo(f.sqlStatRows);
    assertThat(f.manifest.rows(Table.EXT_STAT_RAW)).isEqualTo(f.extStatRows);
    assertThat(f.manifest.xlogLateRows()).isEqualTo(f.late);
  }

  @Test
  @DisplayName("Midnight: xlog rows on both UTC days; per-day D-12 differs only by crossing work")
  void midnight() {
    Map<LocalDate, Long> rows = f.manifest.xlogRowsByDay();
    assertThat(rows).hasSize(2);
    assertThat(rows.values()).allSatisfy(v -> assertThat(v).isGreaterThan(10_000L));
    Map<LocalDate, Long> xlog = f.manifest.xlogSqlCountByDay();
    Map<LocalDate, Long> stat = f.manifest.statSqlCountByDay();
    long xlogSum = xlog.values().stream().mapToLong(Long::longValue).sum();
    long statSum = stat.values().stream().mapToLong(Long::longValue).sum();
    assertThat(statSum).isEqualTo(xlogSum);
    // stat-window=end (agent): executions after midnight of transactions started before it are
    // counted on the next day; the per-day difference is small but not zero.
    LocalDate first = rows.keySet().iterator().next();
    long shifted = xlog.get(first) - stat.get(first);
    assertThat(shifted).isPositive().isLessThan(xlogSum / 100);
  }

  @Test
  @DisplayName("stat-window=start makes the per-day D-12 sums exact (no late batch, no clamp)")
  void statWindowStart() throws InterruptedException {
    SimulationFixture exact =
        new SimulationFixture(
            SimulationFixture.defaultConfig()
                .with("stat-window", "start")
                .with("tps", "50")
                .with("late-rate", "0")
                .with("skewed-instances", "0"));
    assertThat(exact.config.statWindow).isEqualTo(StatWindow.START);
    assertThat(exact.manifest.xlogSqlCountByDay()).hasSize(2);
    assertThat(exact.manifest.statSqlCountByDay()).isEqualTo(exact.manifest.xlogSqlCountByDay());
    assertThat(exact.manifest.statExtCountByDay()).isEqualTo(exact.manifest.xlogExtCountByDay());
  }

  @Test
  @DisplayName(
      "Timestamp guard (ADR-0010 C-129): the +60 s instances are corrected by 60 s instead of"
          + " clamped, the others are untouched, tx_id keeps the agent time")
  void timestampGuard() {
    // C-129 (4): the guard runs on the corrected value; no instance is still 5 s ahead after it.
    assertThat(f.clamped).isZero();
    long corrected = 0;
    for (Transaction tx : f.capturedTx.values()) {
      long idTime = (tx.txId >>> 23) + RunRange.TX_EPOCH_MS;
      assertThat(idTime).isEqualTo(tx.tsAgentMs); // C-129 (6): tx_id is never changed
      assertThat(tx.clamped).isFalse();
      if (tx.instance.skewed) {
        // Skew 60 s +- 2 s: the lead rounded to 10 s.
        assertThat(tx.correctionMs)
            .isEqualTo(Simulation.correctionMs(tx.instance.skewMs))
            .isEqualTo(60_000L);
        assertThat(tx.tsMs).isEqualTo(tx.tsAgentMs - tx.correctionMs);
        assertThat(Math.abs(tx.tsMs - tx.startTrueMs)).isLessThanOrEqualTo(2_000L);
        corrected++;
      } else {
        assertThat(tx.correctionMs).isZero();
        assertThat(tx.tsMs).isEqualTo(tx.tsAgentMs);
      }
    }
    assertThat(corrected).isPositive();
    assertThat(f.manifest.correctedTransactions()).isPositive();
  }

  @Test
  @DisplayName(
      "ADR-0010 C-129 (1)(2)(5): correction of a clock ahead, rounded to 10 s; none behind")
  void correctionRule() {
    assertThat(Simulation.correctionMs(5_000)).isZero();
    assertThat(Simulation.correctionMs(5_001)).isEqualTo(10_000);
    assertThat(Simulation.correctionMs(64_999)).isEqualTo(60_000);
    assertThat(Simulation.correctionMs(65_000)).isEqualTo(70_000);
    assertThat(Simulation.correctionMs(90_000_000)).isEqualTo(90_000_000);
    assertThat(Simulation.correctionMs(-60_000)).isZero();
    assertThat(Simulation.correctionMs(0)).isZero();
  }

  @Test
  @DisplayName("Metrics: every series of every up instance on each 5 s grid point")
  void metricGrid() {
    int series = f.config.metricSeries;
    assertThat(f.metricSamplesPerGridPoint.values())
        .allSatisfy(v -> assertThat(v).isEqualTo(series));
    // 50 instances (the 2 skewed ones included since ADR-0010 C-129 corrects instead of clamping),
    // 280 s of metrics, 5 s grid, minus restarts.
    long points = f.metricSamplesPerGridPoint.size();
    assertThat(points).isBetween(50L * 56 - 50 * 8, 50L * 56 + 50);
    assertThat(f.counterResets).isPositive(); // counters restart after the rolling restart
  }

  @Test
  @DisplayName(
      "Profile steps agree with the transaction (sql_count, contiguous seq, captured only)")
  void profileSteps() {
    assertThat(f.profileByTx).isNotEmpty();
    int checked = 0;
    for (Map.Entry<Long, long[]> e : f.profileByTx.entrySet()) {
      Transaction tx = f.capturedTx.get(e.getKey());
      assertThat(tx.profileCaptured).isTrue();
      assertThat(tx.startTrueMs).isGreaterThanOrEqualTo(f.range.profileFrom());
      long[] p = e.getValue();
      assertThat(p[3]).isEqualTo(p[0]); // seq 1..n
      if (p[2] == 0) {
        assertThat(p[1]).as("SQL repeat sum of tx %d", tx.txId).isEqualTo(tx.sqlCount);
        checked++;
      } else {
        assertThat(tx.flags & Transaction.FLAG_SUMMARIZED).isNotZero();
        assertThat(p[0]).isGreaterThan(1000);
      }
    }
    assertThat(checked).isGreaterThan(1000);
  }

  @Test
  @DisplayName("Versions: v1 before the switch, v2 and the v3 canary after the rolling restart")
  void versions() {
    Topology t = f.topology;
    for (Topology.Instance i : t.instances) {
      assertThat(i.appVersionIdAt(f.range.start())).isEqualTo(i.app.appVersionIds[0]);
      long after = i.restartEnd + 1;
      long expected =
          i.indexInApp == t.config.instancesPerApp - 1
              ? i.app.appVersionIds[2]
              : i.app.appVersionIds[1];
      assertThat(i.appVersionIdAt(after)).isEqualTo(expected);
      assertThat(i.upAt(i.restartAt)).isFalse();
      assertThat(i.restartEnd).isLessThan(f.range.end());
    }
    assertThat(f.versionsSeen).hasSize(3 * t.apps.size());
  }

  @Test
  @DisplayName("tx_id: Snowflake layout (ms << 23 | node << 11 | seq), unique")
  void txIdLayout() {
    long id = TransactionFactory.txId(RunRange.TX_EPOCH_MS + 1234, 4097, 2049);
    assertThat(id >>> 23).isEqualTo(1234);
    assertThat((id >>> 11) & 0xFFF).isEqualTo(1);
    assertThat(id & 0x7FF).isEqualTo(1);
    assertThat(f.txIds).hasSize((int) f.xlogRows);
  }

  @Test
  @DisplayName("Trace samples: only roots with a trace id (W3C, never all-zero; no batch root)")
  void traceSamplesHaveTraceIds() {
    Map<String, Object> samples = f.manifest.samples();
    List<?> traces = (List<?>) samples.get("traces");
    assertThat(traces).isNotEmpty();
    for (Object o : traces) {
      String traceId = String.valueOf(((Map<?, ?>) o).get("trace_id"));
      assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
    }
    // The case is exercised: batch roots were among the sampled transactions.
    assertThat(f.batch).isPositive();
    assertThat((List<?>) samples.get("transactions"))
        .anySatisfy(t -> assertThat(((Map<?, ?>) t).get("kind")).isEqualTo("BATCH"));
  }
}
