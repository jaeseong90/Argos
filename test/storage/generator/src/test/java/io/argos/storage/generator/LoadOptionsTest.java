/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.rows.Table;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The options that do not belong to the 13 §15 distributions: the simulation tick ({@code tick-ms},
 * ingest lag resolution of the replay) and the N-06 load composition (300 instances, {@code
 * active-sqls}, {@code profile-step-rate}). Neither may break the manifest or D-12.
 */
class LoadOptionsTest {

  private static SimulationFixture seconds;
  private static SimulationFixture fine;

  @BeforeAll
  static void simulate() throws InterruptedException {
    seconds = new SimulationFixture(SimulationFixture.defaultConfig());
    fine = new SimulationFixture(SimulationFixture.defaultConfig().with("tick-ms", "100"));
  }

  @Test
  @DisplayName("tick-ms: rows reach the writer within one tick of their receive time")
  void releaseLagBoundedByTick() {
    assertThat(seconds.config.tickMs).isEqualTo(1000);
    assertThat(seconds.minReleaseLagMs).isPositive();
    assertThat(seconds.maxReleaseLagMs).isLessThanOrEqualTo(1000).isGreaterThan(900);
    assertThat(fine.config.tickMs).isEqualTo(100);
    assertThat(fine.minReleaseLagMs).isPositive();
    assertThat(fine.maxReleaseLagMs).isLessThanOrEqualTo(100).isGreaterThan(90);
    assertThat(fine.summary).containsEntry("tickMs", 100L);
  }

  @Test
  @DisplayName(
      "tick-ms: manifest = rows received and D-12 exact at 100 ms; same volumes as 1 s ticks")
  void tickKeepsManifestAndInvariants() {
    // Invariants checked while the rows streamed out (receive order, late flag, clamp, grids,
    // profile sequence, bucket sums, counter deltas) and tx_id uniqueness.
    assertThat(fine.violations).isEmpty();
    assertThat(fine.receivedOutOfOrder).isZero();
    assertThat(fine.txIds).hasSize((int) fine.xlogRows);
    // Manifest = what the loader received, per table.
    assertThat(fine.manifest.rows(Table.XLOG)).isEqualTo(fine.xlogRows);
    assertThat(fine.manifest.rows(Table.PROFILE_STEP)).isEqualTo(fine.profileRows);
    assertThat(fine.manifest.rows(Table.METRIC)).isEqualTo(fine.metricRows);
    assertThat(fine.manifest.rows(Table.SQL_STAT_RAW)).isEqualTo(fine.sqlStatRows);
    assertThat(fine.manifest.rows(Table.EXT_STAT_RAW)).isEqualTo(fine.extStatRows);
    assertThat(fine.manifest.xlogLateRows()).isEqualTo(fine.late).isPositive();
    // D-12 by construction: totals, and per-day sums over both UTC days.
    assertThat(fine.statSqlCount).isEqualTo(fine.sqlCount).isPositive();
    assertThat(fine.statSqlMs).isEqualTo(fine.sqlTimeMs);
    assertThat(fine.statExtCount).isEqualTo(fine.extCount).isPositive();
    assertThat(fine.manifest.statSqlCount()).isEqualTo(fine.manifest.xlogSqlCount());
    assertThat(fine.manifest.statExtCount()).isEqualTo(fine.manifest.xlogExtCount());
    assertThat(fine.manifest.xlogRowsByDay()).hasSize(2);
    // The HTTP and batch arrivals of an instance share its random streams, so another tick
    // interleaves them differently: same distributions and volumes, not the same rows. The metric
    // grid does not depend on the tick.
    assertThat(fine.manifest.rows(Table.METRIC)).isEqualTo(seconds.manifest.rows(Table.METRIC));
    assertThat((double) fine.xlogRows)
        .isCloseTo((double) seconds.xlogRows, within(seconds.xlogRows * 0.005));
    for (Table table : List.of(Table.PROFILE_STEP, Table.SQL_STAT_RAW, Table.EXT_STAT_RAW)) {
      double expected = seconds.manifest.rows(table);
      assertThat((double) fine.manifest.rows(table))
          .as(table.name())
          .isCloseTo(expected, within(expected * 0.03));
    }
    // SQL executions have a heavy tail (N+1 loops, batch chunks of up to 4,500 executions).
    assertThat((double) fine.sqlCount)
        .isCloseTo((double) seconds.sqlCount, within(seconds.sqlCount * 0.05));
  }

  @Test
  @DisplayName(
      "N-06 load: 300 instances, uniform draws from the active SQL set, profile step rate"
          + " followed, D-12 still exact")
  void n06Load() throws InterruptedException {
    double stepRate = 3000;
    SimulationFixture f =
        new SimulationFixture(
            SimulationFixture.defaultConfig()
                .with("tps", "300")
                .with("instances-per-app", "30")
                .with("active-sqls", "50")
                .with("profile-step-rate", Double.toString(stepRate))
                .with("metric-series", "5"));
    assertThat(f.violations).isEmpty();
    assertThat(f.topology.instances).hasSize(300);

    // Statistics are still derived from the transactions.
    assertThat(f.statSqlCount).isEqualTo(f.sqlCount).isPositive();
    assertThat(f.statSqlMs).isEqualTo(f.sqlTimeMs);
    assertThat(f.statExtCount).isEqualTo(f.extCount).isPositive();
    assertThat(f.manifest.rows(Table.SQL_STAT_RAW)).isEqualTo(f.sqlStatRows);
    assertThat(f.manifest.rows(Table.PROFILE_STEP)).isEqualTo(f.profileRows);

    // Active sets: 50 distinct statements of the instance's app; every SQL run comes from it.
    for (Topology.Instance i : f.topology.instances) {
      assertThat(i.activeSqls).hasSize(50).doesNotHaveDuplicates();
      for (int k : i.activeSqls) {
        assertThat(k).isBetween(0, i.app.sqls.size() - 1);
      }
    }
    int checked = 0;
    for (Transaction tx : f.capturedTx.values()) {
      Set<Long> active = new HashSet<>();
      for (int k : tx.instance.activeSqls) {
        active.add(tx.instance.app.sqls.get(k).id);
      }
      for (Transaction.SqlRun run : tx.sqlRuns) {
        assertThat(active).contains(run.sql.id);
        checked++;
      }
    }
    assertThat(checked).isGreaterThan(10_000);
    // The number of statements per transaction keeps the 13 §15 mean.
    assertThat((double) f.rootHttpSqlIds / f.rootHttp).isCloseTo(3.0, within(0.15));

    // profile_step rows per second of the profile span follow the target (capture rate adapted).
    double profileSeconds = (f.range.end() - f.range.profileFrom()) / 1000.0;
    assertThat(f.profileRows / profileSeconds).isCloseTo(stepRate, within(stepRate * 0.05));
    assertThat((double) f.summary.get("profileStepsPerSecondAchieved"))
        .isCloseTo(stepRate, within(stepRate * 0.05));
    assertThat((double) f.summary.get("normalCaptureRateMean")).isBetween(0.2, 1.0);
  }

  @Test
  @DisplayName(
      "C-62 Q17 3b options: 500 instances and an active SQL set above the 13 §14 200 are accepted"
          + " when the catalog holds them; a larger active set than sqls / apps is refused")
  void statisticsLoadOptions() {
    GeneratorConfig load =
        GeneratorConfig.defaults()
            .with("sqls", "6000")
            .with("instances-per-app", "50")
            .with("active-sqls", "600");
    assertThat(load.apps * load.instancesPerApp).isEqualTo(500);
    assertThat(load.activeSqls).isEqualTo(600);
    assertThatThrownBy(() -> GeneratorConfig.defaults().with("active-sqls", "201"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("raise --sqls to at least active-sqls x apps");
    assertThat(GeneratorConfig.defaults().with("active-sqls", "200").activeSqls).isEqualTo(200);
    // The command line is validated as a whole: the order of the arguments does not matter.
    GeneratorConfig cli =
        GeneratorConfig.parse(
            List.of("--active-sqls=1000", "--instances-per-app=50", "--sqls=10000"));
    assertThat(cli.activeSqls).isEqualTo(1000);
    assertThat(cli.sqls).isEqualTo(10000);
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--active-sqls=1000")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("raise --sqls");
  }

  @Test
  @DisplayName(
      "C-62 the run prints the achieved statistics rows/s (sql_stat_raw + ext_stat_raw) and"
          + " whether it reached the N-06 bar of 10,000")
  void statisticsRateLine() {
    // The rates of a real (short) simulation: the default run of this class (50 instances).
    Map<String, Object> rates = Generator.ratesPerSimulatedSecond(seconds.range, seconds.manifest);
    double simulated = (seconds.range.end() - seconds.range.start()) / 1000.0;
    assertThat((double) rates.get("statistics"))
        .isCloseTo(
            (seconds.manifest.rows(Table.SQL_STAT_RAW) + seconds.manifest.rows(Table.EXT_STAT_RAW))
                / simulated,
            within(1e-6));
    assertThat(Generator.statisticsLine(rates))
        .startsWith("argos storage generator: statistics rows/simulated s: ")
        .endsWith("; N-06 bar 10,000: NOT reached");

    Map<String, Object> reached = new LinkedHashMap<>();
    reached.put("sql_stat_raw", 10_286.4);
    reached.put("ext_stat_raw", 125.6);
    reached.put("statistics", 10_412.0);
    assertThat(Generator.statisticsLine(reached))
        .isEqualTo(
            "argos storage generator: statistics rows/simulated s: 10,412 (sql_stat_raw 10,286 +"
                + " ext_stat_raw 126); N-06 bar 10,000: reached");
    reached.put("statistics", 9_999.9);
    assertThat(Generator.statisticsLine(reached)).endsWith("N-06 bar 10,000: NOT reached");
  }

  @Test
  @DisplayName("Defaults leave the N-06 options off (13 §15 shape)")
  void defaultsOff() {
    assertThat(seconds.topology.instances).allSatisfy(i -> assertThat(i.activeSqls).isNull());
    assertThat(seconds.summary).containsEntry("profileStepRateTarget", 0.0);
    assertThat(seconds.summary).doesNotContainKey("profileStepsPerSecondAchieved");
  }
}
