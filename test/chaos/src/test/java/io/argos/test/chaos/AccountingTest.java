/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("scenario drop accounting from agent counters")
class AccountingTest {

  @Test
  @DisplayName(
      "T-CH-020e accounting uses the scenario's deltas: xlog rows, agent and server transaction"
          + " drops, answered requests")
  void deltas() {
    ScenarioContext c = new ScenarioContext("T-CH-001", null, null, Duration.ofMinutes(3));
    c.xlogBefore = 1_000;
    c.countsBefore =
        new AgentCounts(
            Map.of(
                AgentCounts.DROPPED_TRANSACTIONS,
                5L,
                AgentCounts.SERVER_DROPPED_TRANSACTIONS,
                1L,
                "argos_agent_dropped_total{queue=\"profile\"}",
                100L));
    c.load = new LoadSummary(18_000, 18_000, 0, 900, 0, 0, 305.0);
    AgentCounts after =
        new AgentCounts(
            Map.of(
                AgentCounts.DROPPED_TRANSACTIONS,
                6_005L,
                AgentCounts.SERVER_DROPPED_TRANSACTIONS,
                1L,
                "argos_agent_dropped_total{queue=\"profile\"}",
                9_999L));
    DropAccounting a = ScenarioDriver.accounting(c, 13_000, after);
    assertThat(a.stored()).isEqualTo(12_000);
    assertThat(a.agentDropped()).isEqualTo(6_000);
    assertThat(a.serverDropped()).isZero();
    assertThat(a.holds()).isTrue();
    assertThat(a.exact()).isTrue();
  }

  @Test
  @DisplayName("T-CH-020e agent counters: label sums and deltas, missing is not zero")
  void counters() {
    AgentCounts before =
        new AgentCounts(
            Map.of(
                "argos_agent_batches_sent_total{kind=\"transactions\"}",
                10L,
                "argos_agent_batches_sent_total{kind=\"metrics\"}",
                2L,
                AgentCounts.SESSIONS,
                1L));
    AgentCounts after =
        new AgentCounts(
            Map.of(
                "argos_agent_batches_sent_total{kind=\"transactions\"}",
                30L,
                "argos_agent_batches_sent_total{kind=\"metrics\"}",
                7L,
                AgentCounts.SESSIONS,
                2L));
    assertThat(AgentCounts.sumDelta(before, after, AgentCounts.BATCHES_SENT)).hasValue(25);
    assertThat(AgentCounts.delta(before, after, AgentCounts.SESSIONS)).hasValue(1);
    assertThat(AgentCounts.delta(before, after, AgentCounts.DROPPED_TRANSACTIONS)).isEmpty();
    assertThat(AgentCounts.sumDelta(before, after, AgentCounts.BATCH_RETRIES)).isEmpty();
    // A metric name that is a prefix of another is not summed with it.
    assertThat(
            new AgentCounts(Map.of("argos_agent_batches_sent_totalx", 1L))
                .sum(AgentCounts.BATCHES_SENT))
        .isEmpty();
  }
}
