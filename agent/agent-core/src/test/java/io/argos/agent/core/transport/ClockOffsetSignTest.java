/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.HeartbeatAck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The agent's half of the {@code HeartbeatAck.clock_offset_ms} sign (server minus agent, the 11 §9
 * formula, ADR-0010 C-139): the agent keeps the server's value exactly as sent, so the sign is what
 * the server (and the FakeCollector) sends, never converted by the agent. The collector's half is
 * {@code ClockOffsetSignTest} of agent-testing.
 */
class ClockOffsetSignTest {

  @Test
  @Tag("req:D-02")
  @DisplayName(
      "11 §9 clock_offset_ms (server minus agent, ADR-0010 C-139): ClockSync and"
          + " argos_agent_clock_offset_ms keep the HeartbeatAck value as sent, without sign"
          + " conversion")
  void keptAsSent() {
    int[] values = {3_000, -3_000, 0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};
    for (int sent : values) {
      ClockSync sync = new ClockSync(() -> 1_000_000L);
      sync.acknowledged(HeartbeatAck.newBuilder().setClockOffsetMs(sent).build());
      assertThat(sync.offsetKnown()).isTrue();
      assertThat(sync.offsetMillis()).as("sent %d", sent).isEqualTo((long) sent);

      TransportCounters counters = new TransportCounters();
      counters.clockOffset(sync.offsetMillis());
      assertThat(counters.snapshot())
          .as("self metric for sent %d", sent)
          .containsEntry(TransportCounters.CLOCK_OFFSET, (long) sent);
    }
  }
}
