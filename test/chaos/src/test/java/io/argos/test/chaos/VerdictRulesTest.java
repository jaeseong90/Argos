/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("chaos verdict rules")
class VerdictRulesTest {

  private static Check row(Verdict v) {
    return new Check("T-CH-001", "x", "t", "m", Check.Kind.INVARIANT, v, "");
  }

  @Test
  @DisplayName("T-CH-020g INVALID rule: measurements judge only profile full on argos-matrix")
  void invalidRule() {
    assertThat(Judge.valid(ChaosProfile.FULL, "argos-matrix")).isTrue();
    assertThat(Judge.valid(ChaosProfile.SHORT, "argos-matrix")).isFalse();
    assertThat(Judge.valid(ChaosProfile.FULL, "dev-laptop")).isFalse();
    assertThat(Judge.valid(ChaosProfile.FULL, null)).isFalse();
    assertThat(Judge.measurement(false, true, false)).isEqualTo(Verdict.INVALID);
    assertThat(Judge.measurement(false, false, false)).isEqualTo(Verdict.INVALID);
    assertThat(Judge.measurement(true, true, true)).isEqualTo(Verdict.PASS);
    assertThat(Judge.measurement(true, true, false)).isEqualTo(Verdict.FAIL);
    // A valid run that could not measure fails.
    assertThat(Judge.measurement(true, false, true)).isEqualTo(Verdict.FAIL);
    assertThat(Judge.invariant(true)).isEqualTo(Verdict.PASS);
    assertThat(Judge.invariant(false)).isEqualTo(Verdict.FAIL);
  }

  @Test
  @DisplayName("T-CH-020g scenario verdict: FAIL wins, then INVALID; INFO and SKIPPED rows pass")
  void scenarioVerdict() {
    assertThat(Judge.scenario(List.of(row(Verdict.PASS), row(Verdict.INFO))))
        .isEqualTo(Verdict.PASS);
    assertThat(Judge.scenario(List.of(row(Verdict.PASS), row(Verdict.SKIPPED))))
        .isEqualTo(Verdict.PASS);
    assertThat(Judge.scenario(List.of(row(Verdict.INVALID), row(Verdict.PASS))))
        .isEqualTo(Verdict.INVALID);
    assertThat(Judge.scenario(List.of(row(Verdict.INVALID), row(Verdict.FAIL))))
        .isEqualTo(Verdict.FAIL);
  }

  @Test
  @DisplayName(
      "T-CH-020b noise band: max + max(spread, 10 % of median), judged by the fault median")
  void noiseBand() {
    NoiseBand band = new NoiseBand(List.of(300.0, 310.0, 305.0, 302.0));
    assertThat(band.defined()).isTrue();
    // spread 10, 10 % of median (303.5) = 30.35 -> upper 340.35
    assertThat(band.upper().getAsDouble())
        .isCloseTo(340.35, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(band.contains(340.0)).isTrue();
    assertThat(band.contains(341.0)).isFalse();
    assertThat(band.contains(Double.NaN)).isFalse();
    NoiseBand wide = new NoiseBand(List.of(100.0, 300.0, 200.0));
    // spread 200 > 10 % of 200 -> upper 500
    assertThat(wide.upper().getAsDouble()).isEqualTo(500.0);
    assertThat(new NoiseBand(List.of(1.0, Double.NaN, 2.0)).defined()).isFalse();
    assertThat(NoiseBand.median(List.of(5.0, 1.0, 3.0, 7.0))).isEqualTo(4.0);
    assertThat(NoiseBand.median(List.of())).isNaN();
    assertThat(band.describe()).contains("300.0..310.0 ms (4 intervals)").contains("340.4 ms");
  }

  @Test
  @DisplayName("T-CH-020c recovery: both signals within 60 s of the removal; none = not resumed")
  void recovery() {
    Instant end = Instant.parse("2026-11-26T10:00:00Z");
    Recovery r = new Recovery(end, end.plusSeconds(12), end.plusSeconds(40));
    assertThat(r.resumed()).isTrue();
    assertThat(r.time()).contains(Duration.ofSeconds(40));
    assertThat(r.withinTarget()).isTrue();
    Recovery late = new Recovery(end, end.plusSeconds(61), end.plusSeconds(3));
    assertThat(late.withinTarget()).isFalse();
    Recovery none = new Recovery(end, end.plusSeconds(5), null);
    assertThat(none.resumed()).isFalse();
    assertThat(none.time()).isEmpty();
    assertThat(none.withinTarget()).isFalse();
    assertThat(Recovery.instanceBack("WARN", end, end)).isTrue();
    assertThat(Recovery.instanceBack("UP", end.minusSeconds(1), end)).isFalse();
    assertThat(Recovery.instanceBack("STALE", end.plusSeconds(1), end)).isFalse();
    assertThat(Recovery.instanceBack("UP", null, end)).isFalse();
  }

  @Test
  @DisplayName("T-CH-020e drop accounting: exact without unanswered requests, a range otherwise")
  void dropAccounting() {
    DropAccounting exact = new DropAccounting(17_000, 1_000, 0, 18_000, 18_000);
    assertThat(exact.exact()).isTrue();
    assertThat(exact.holds()).isTrue();
    assertThat(exact.describe())
        .isEqualTo("stored 17000 + agent drops 1000 + server drops 0 = 18000; transactions 18000");
    assertThat(new DropAccounting(16_999, 1_000, 0, 18_000, 18_000).holds()).isFalse();
    // Counted twice (stored and dropped) is a failure too.
    assertThat(new DropAccounting(17_001, 1_000, 0, 18_000, 18_000).holds()).isFalse();
    DropAccounting range = new DropAccounting(17_998, 0, 3, 18_000, 18_002);
    assertThat(range.exact()).isFalse();
    assertThat(range.holds()).isTrue();
    assertThat(range.describe()).endsWith("transactions 18000..18002");
    LoadSummary s = new LoadSummary(100, 97, 2, 5, 0, 1, 300.0);
    assertThat(s.minTransactions()).isEqualTo(97);
    assertThat(s.maxTransactions()).isEqualTo(100);
  }

  @Test
  @DisplayName("T-CH-020e queue drops reference of T-CH-001: rate x (outage - 120 s), capacity")
  void expectedDrops() {
    assertThat(ServerOutageFault.expectedDrops(100, Duration.ofMinutes(3))).isEqualTo(6_000);
    assertThat(ServerOutageFault.expectedDrops(100, Duration.ofSeconds(90))).isZero();
    // 1 h at 200/s: 720,000 - 20,000 capacity beats the age rule (696,000).
    assertThat(ServerOutageFault.expectedDrops(200, Duration.ofHours(1))).isEqualTo(700_000);
  }

  @Test
  @DisplayName("T-CH-020f in-flight bound of T-CH-002: 4 batches per round trip of 1 s")
  void inFlightBound() {
    assertThat(LatencyFault.maxAckRate(4, Duration.ofSeconds(1))).isEqualTo(4.0);
    assertThat(LatencyFault.maxAckRate(4, Duration.ofMillis(500))).isEqualTo(8.0);
  }
}
