/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.load.Criterion.Kind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
@Tag("req:N-18")
@Tag("req:D-13")
class VerdictsTest {

  private static final long GIB = 1L << 30;

  /** A run that meets every target. */
  private static Verdicts.Measurements good(boolean fullScale) {
    return new Verdicts.Measurements(
        fullScale,
        fullScale ? "full" : "smoke",
        Duration.ofMinutes(60),
        5_010,
        50_100,
        10_050,
        1.2,
        21_600,
        0,
        350,
        10,
        120,
        0,
        List.of(),
        List.of(),
        18_000_000,
        18_000_000,
        18_000_000,
        18_000_000,
        4 * GIB + GIB / 2,
        5 * GIB,
        5 * GIB,
        (long) (7.5 * GIB));
  }

  private static Map<String, Criterion> byTitle(List<Criterion> criteria) {
    return criteria.stream()
        .collect(Collectors.toMap(Criterion::title, Function.identity(), (a, b) -> a));
  }

  @Test
  @DisplayName("T-NFR-06a a full-scale run within every target passes every criterion")
  void fullScalePasses() {
    List<Criterion> c = Verdicts.evaluate(good(true));
    assertThat(c).extracting(Criterion::verdict).containsOnly(Verdict.PASS);
    assertThat(Verdicts.taskFails(c)).isFalse();
    assertThat(c).extracting(Criterion::requirement).contains("N-06", "N-18", "D-13", "12 §11");
  }

  @Test
  @DisplayName(
      "T-NFR-06a any other profile makes every timing and resource criterion INVALID and still"
          + " judges the invariants")
  void otherProfilesAreInvalid() {
    List<Criterion> c = Verdicts.evaluate(good(false));
    assertThat(c)
        .filteredOn(x -> x.kind() == Kind.MEASUREMENT)
        .isNotEmpty()
        .allSatisfy(
            x -> {
              assertThat(x.verdict()).isEqualTo(Verdict.INVALID);
              assertThat(x.note()).contains("profile smoke").contains("reference only");
            });
    assertThat(c)
        .filteredOn(x -> x.kind() == Kind.INVARIANT)
        .extracting(Criterion::verdict)
        .containsOnly(Verdict.PASS);
    assertThat(Verdicts.taskFails(c)).isFalse();
  }

  @Test
  @DisplayName(
      "T-NFR-06a at full scale p99 above 5 s, p95 above 2 s, 300 parts, DelayedInserts or a rate"
          + " below 98 % fail")
  void fullScaleThresholds() {
    Verdicts.Measurements g = good(true);
    Verdicts.Measurements bad =
        new Verdicts.Measurements(
            true,
            "full",
            Duration.ofMinutes(59),
            4_850,
            50_100,
            10_050,
            5.01,
            21_600,
            0,
            2_001,
            10,
            300,
            1,
            List.of(),
            List.of(),
            g.sent(),
            g.xlogRows(),
            g.xlogDistinct(),
            g.serviceStatCount(),
            g.serverRssPeak(),
            g.serverBudget(),
            g.clickHouseMemoryPeak(),
            g.clickHouseServerLimit());
    Map<String, Criterion> c = byTitle(Verdicts.evaluate(bad));
    assertThat(c.get("Transactions per second, sustained").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(c.get("Duration of the measured window").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(c.get("Ingest lag p99 (argos_ingest_lag_seconds)").verdict())
        .isEqualTo(Verdict.FAIL);
    assertThat(c.get("Dashboard 10 users, 5 s polling: p95").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(c.get("Active parts per partition (maximum during the run)").verdict())
        .isEqualTo(Verdict.FAIL);
    assertThat(c.get("DelayedInserts during the run").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(c.get("Profile step rows per second, sustained").verdict()).isEqualTo(Verdict.PASS);
    // Timing failures do not fail the task: the report carries them.
    assertThat(Verdicts.taskFails(Verdicts.evaluate(bad))).isFalse();
  }

  @Test
  @DisplayName(
      "T-NFR-06a a missing measurement is FAIL at full scale and INVALID in other profiles")
  void missingMeasurement() {
    assertThat(Verdicts.measurementVerdict(true, false, true)).isEqualTo(Verdict.FAIL);
    assertThat(Verdicts.measurementVerdict(false, false, true)).isEqualTo(Verdict.INVALID);
    assertThat(Verdicts.invariantVerdict(false, true)).isEqualTo(Verdict.FAIL);
  }

  @Test
  @DisplayName(
      "T-NFR-06a D-13: sent, count(xlog), uniqExact(tx_id) and sum(service_stat_1m.count) must"
          + " be equal in every profile, otherwise the task fails")
  void d13() {
    Verdicts.Measurements g = good(false);
    Verdicts.Measurements lost =
        new Verdicts.Measurements(
            false,
            "smoke",
            g.window(),
            1_000,
            10_000,
            2_000,
            1,
            100,
            0,
            100,
            10,
            50,
            0,
            List.of(),
            List.of(),
            300_000,
            300_000,
            299_999,
            300_000,
            g.serverRssPeak(),
            g.serverBudget(),
            g.clickHouseMemoryPeak(),
            g.clickHouseServerLimit());
    List<Criterion> c = Verdicts.evaluate(lost);
    Criterion d13 =
        byTitle(c).get("Sent = count(xlog) = uniqExact(tx_id) = sum(service_stat_1m.count)");
    assertThat(d13.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(d13.measured()).isEqualTo("300,000 / 300,000 / 299,999 / 300,000");
    assertThat(Verdicts.taskFails(c)).isTrue();
  }

  @Test
  @DisplayName(
      "T-NFR-18a an OOM finding, a settings violation or an unchecked OOM fails the task in"
          + " every profile")
  void oomAndSettings() {
    Verdicts.Measurements g = good(false);
    Verdicts.Measurements oom =
        new Verdicts.Measurements(
            false,
            "smoke",
            g.window(),
            1,
            1,
            1,
            1,
            1,
            0,
            1,
            10,
            1,
            0,
            List.of("mark_cache_size = 0, expected 536870912"),
            List.of("ClickHouse container OOMKilled"),
            5,
            5,
            5,
            5,
            -1,
            g.serverBudget(),
            -1,
            -1);
    Map<String, Criterion> c = byTitle(Verdicts.evaluate(oom));
    assertThat(c.get("OOM").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(c.get("OOM").measured()).contains("OOMKilled");
    assertThat(c.get("ClickHouse memory settings").verdict()).isEqualTo(Verdict.FAIL);
    Verdicts.Measurements unchecked =
        new Verdicts.Measurements(
            false,
            "smoke",
            g.window(),
            1,
            1,
            1,
            1,
            1,
            0,
            1,
            10,
            1,
            0,
            null,
            null,
            5,
            5,
            5,
            5,
            -1,
            g.serverBudget(),
            -1,
            -1);
    Map<String, Criterion> u = byTitle(Verdicts.evaluate(unchecked));
    assertThat(u.get("OOM").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(u.get("ClickHouse memory settings").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(u.get("Server resident set peak").verdict()).isEqualTo(Verdict.INVALID);
  }
}
