/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The collector's half of the {@code HeartbeatAck.clock_offset_ms} sign (ADR-0010 C-139: server
 * minus agent, the 11 §9 formula): the FakeCollector sends the §9 value, so a server clock 3 s
 * ahead sends +3000. The agent's half ({@code ClockOffsetSignTest} of agent-core: the value is kept
 * as sent) stays. Without network: {@code FakeCollectorTest.clockOffset} and {@code
 * ProtocolContractTest.clock} check the same sign over gRPC.
 */
class ClockOffsetSignTest {

  @Test
  @Tag("req:D-02")
  @DisplayName(
      "11 §9 / ADR-0010 C-139 clock_offset_ms is server minus agent: with the server clock"
          + " 3 s ahead the FakeCollector sends +3000, 3 s behind -3000")
  void serverMinusAgent() {
    assertThat(sentOffset(3_000)).isEqualTo(3_000L);
    assertThat(sentOffset(-3_000)).isEqualTo(-3_000L);
  }

  /**
   * The {@code clock_offset_ms} the FakeCollector sends for a server clock {@code serverAheadMs}
   * ahead of the agent: after the hello (first estimate) and after one completed round with rtt 0.
   */
  private static long sentOffset(long serverAheadMs) {
    long agent = 1_000_000L;
    ClockEstimator clock = new ClockEstimator();
    clock.hello(agent, agent + serverAheadMs);
    long afterHello = FakeCollector.ackClockOffsetMs(clock.estimate().getAsLong());

    long t1 = agent + 100;
    long t2 = t1 + serverAheadMs;
    long t3 = t2;
    long t4 = t1;
    clock.acked(t1, t2, t3);
    ClockEstimator.Round round = clock.heartbeat(t1, t4);
    assertThat(round).isNotNull();
    assertThat(round.rttMs()).isZero();
    assertThat(round.offsetMs()).isEqualTo(serverAheadMs);
    long afterRound = FakeCollector.ackClockOffsetMs(clock.estimate().getAsLong());

    assertThat(afterRound).as("first estimate and round agree").isEqualTo(afterHello);
    return afterRound;
  }
}
