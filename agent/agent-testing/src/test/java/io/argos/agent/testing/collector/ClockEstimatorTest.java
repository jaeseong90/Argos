/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 11 §9 clock arithmetic of the FakeCollector ({@link ClockEstimator}) on the fixed inputs of
 * the contract vectors ({@code contract/argos-v1-vectors.json}, section {@code clock}), without
 * network. {@code ProtocolContractTest} replays the same vectors over gRPC.
 */
@Tag("req:D-02")
@Tag("req:DSN-11-01")
class ClockEstimatorTest {

  @Test
  @DisplayName(
      "11 §9 vectors: offset = ((t2 - t1) + (t3 - t4)) / 2, rtt = (t4 - t1) - (t3 - t2), median"
          + " of the last 5 accepted offsets, rtt > 3 x median rtt discarded, first estimate from"
          + " the hello")
  void vectors() {
    List<Map<String, Object>> scenarios = ContractVectors.maps(ContractVectors.load().get("clock"));
    assertThat(scenarios).hasSizeGreaterThanOrEqualTo(2);
    int rounds = 0;
    int discarded = 0;
    for (Map<String, Object> scenario : scenarios) {
      String name = ContractVectors.string(scenario, "name");
      ClockEstimator clock = new ClockEstimator();
      assertThat(clock.estimate()).as(name).isEmpty();
      Map<String, Object> hello = ContractVectors.map(scenario.get("hello"));
      clock.hello(
          ContractVectors.number(hello, "sent_at_ms"),
          ContractVectors.number(hello, "server_time_ms"));
      assertThat(clock.estimate())
          .as(name + " first estimate")
          .hasValue(ContractVectors.number(hello, "expected_estimate_ms"));
      for (Map<String, Object> beat : ContractVectors.maps(scenario.get("heartbeats"))) {
        ClockEstimator.Round round =
            clock.heartbeat(
                ContractVectors.number(beat, "prev_t1"), ContractVectors.number(beat, "prev_t4"));
        Object expected = beat.get("expected_round");
        String step = name + " heartbeat t1=" + ContractVectors.number(beat, "t1");
        if (expected == null) {
          assertThat(round).as(step).isNull();
        } else {
          Map<String, Object> want = ContractVectors.map(expected);
          assertThat(round).as(step).isNotNull();
          assertThat(round.offsetMs()).as(step + " offset").isEqualTo(want.get("offset_ms"));
          assertThat(round.rttMs()).as(step + " rtt").isEqualTo(want.get("rtt_ms"));
          assertThat(round.discarded()).as(step + " discarded").isEqualTo(want.get("discarded"));
          rounds++;
          discarded += round.discarded() ? 1 : 0;
        }
        assertThat(clock.estimate())
            .as(step + " estimate")
            .hasValue(ContractVectors.number(beat, "expected_estimate_ms"));
        clock.acked(
            ContractVectors.number(beat, "t1"),
            ContractVectors.number(beat, "t2"),
            ContractVectors.number(beat, "t3"));
      }
    }
    assertThat(rounds).as("rounds checked").isGreaterThanOrEqualTo(10);
    assertThat(discarded).as("discarded rounds checked").isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("11 §9 formula and integer rules: server minus agent, halves toward -infinity")
  void formula() {
    // Server 3 s ahead, 10 ms each way: offset +3000, rtt 20.
    assertThat(ClockEstimator.offset(1_000, 4_010, 4_010, 1_020)).isEqualTo(3_000);
    assertThat(ClockEstimator.rtt(1_000, 4_010, 4_010, 1_020)).isEqualTo(20);
    assertThat(ClockEstimator.offset(0, 1, 0, 0)).isZero();
    assertThat(ClockEstimator.offset(0, -1, 0, 0)).isEqualTo(-1);
    assertThat(ClockEstimator.median(new long[] {5})).isEqualTo(5);
    assertThat(ClockEstimator.median(new long[] {3, 1, 2})).isEqualTo(2);
    assertThat(ClockEstimator.median(new long[] {-1001, -1000})).isEqualTo(-1001);
    assertThat(ClockEstimator.median(new long[] {2999, 3000})).isEqualTo(2999);
  }

  /** One round with these values at the agent's t1 = {@code t1} and no time at the server. */
  private static ClockEstimator.Round round(ClockEstimator clock, long t1, long rtt, long offset) {
    clock.acked(t1, t1 + offset + rtt / 2, t1 + offset + rtt / 2);
    return clock.heartbeat(t1, t1 + rtt);
  }

  @Test
  @DisplayName(
      "11 §9: an estimate never freezes: after a run of 0 ms rounds (median rtt 0) or a negative"
          + " rtt from a clock step, later rounds are accepted again once they fill the rtt window")
  void estimateFollowsAfterRttChange() {
    ClockEstimator zero = new ClockEstimator();
    assertThat(round(zero, 1_000, 0, 100).discarded()).isFalse();
    assertThat(round(zero, 2_000, 0, 100).discarded()).isFalse();
    // Median 0: rounds of 2 ms are over 3 x 0 until the rtt window holds {0, 0, 2, 2} (median 1).
    assertThat(round(zero, 3_000, 2, 500).discarded()).isTrue();
    assertThat(round(zero, 4_000, 2, 500).discarded()).isTrue();
    assertThat(zero.estimate()).hasValue(100);
    assertThat(round(zero, 5_000, 2, 500).discarded()).isFalse();
    assertThat(round(zero, 6_000, 2, 500).discarded()).isFalse();
    assertThat(round(zero, 7_000, 2, 500).discarded()).isFalse();
    assertThat(zero.estimate()).hasValue(500);

    ClockEstimator negative = new ClockEstimator();
    assertThat(round(negative, 1_000, -4, 100).discarded()).isFalse();
    assertThat(round(negative, 2_000, 0, 100).discarded()).as("0 > 3 x -4").isTrue();
    assertThat(round(negative, 3_000, 0, 100).discarded()).as("0 > 3 x -2").isTrue();
    assertThat(round(negative, 4_000, 0, 100).discarded()).as("0 is not > 3 x 0").isFalse();
  }

  @Test
  @DisplayName(
      "the window keeps 5 accepted rounds, remembers a bounded number of acknowledged heartbeats"
          + " and ignores prev values <= 0")
  void bounds() {
    ClockEstimator clock = new ClockEstimator();
    for (int i = 1; i <= 8; i++) {
      long t1 = i * 1_000L;
      clock.acked(t1, t1 + 100, t1 + 100);
      assertThat(clock.heartbeat(t1, t1)).isNotNull();
    }
    assertThat(clock.acceptedRounds()).isEqualTo(ClockEstimator.WINDOW);
    assertThat(clock.estimate()).isEqualTo(OptionalLong.of(100));
    assertThat(clock.heartbeat(0, 5)).isNull();
    assertThat(clock.heartbeat(5, 0)).isNull();
    for (int i = 0; i < ClockEstimator.PENDING + 4; i++) {
      clock.acked(100_000L + i, 0, 0);
    }
    assertThat(clock.heartbeat(100_000L, 100_001L)).as("evicted").isNull();
    assertThat(clock.heartbeat(100_000L + ClockEstimator.PENDING + 3, 1)).isNotNull();
  }
}
