/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.generator.GeneratorConfig.StatWindow;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.model.TransactionFactory;
import io.argos.storage.generator.rows.Table;
import java.time.LocalDate;
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
      "Timestamp guard: only the +60 s instances are clamped, and their tx_id time is ahead")
  void timestampGuard() {
    assertThat(f.clamped).isPositive();
    for (Transaction tx : f.capturedTx.values()) {
      long idTime = (tx.txId >>> 23) + RunRange.TX_EPOCH_MS;
      assertThat(idTime).isEqualTo(tx.tsAgentMs);
      if (tx.instance.skewed) {
        // Clamped to the receive time, unless a late batch arrived after the agent timestamp.
        assertThat(tx.clamped || tx.receivedMs > tx.tsAgentMs).isTrue();
        if (tx.clamped) {
          assertThat(tx.tsMs).isEqualTo(tx.receivedMs);
          assertThat(idTime - tx.tsMs).isGreaterThan(5_000L);
        }
      } else {
        assertThat(tx.clamped).isFalse();
      }
    }
  }

  @Test
  @DisplayName("Metrics: every series of every up instance on each 5 s grid point")
  void metricGrid() {
    int series = f.config.metricSeries;
    assertThat(f.metricSamplesPerGridPoint.values())
        .allSatisfy(v -> assertThat(v).isEqualTo(series));
    // 48 instances (without the 2 skewed ones), 280 s of metrics, 5 s grid, minus restarts.
    long points = f.metricSamplesPerGridPoint.size();
    assertThat(points).isBetween(48L * 56 - 48 * 8, 48L * 56 + 48);
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
      assertThat(i.versionIdAt(f.range.start())).isEqualTo(i.app.versionIds[0]);
      long after = i.restartEnd + 1;
      long expected =
          i.indexInApp == t.config.instancesPerApp - 1 ? i.app.versionIds[2] : i.app.versionIds[1];
      assertThat(i.versionIdAt(after)).isEqualTo(expected);
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
}
