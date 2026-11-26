/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The agent counters a scenario compares before and after (self-diagnostics snapshot, C-95; names
 * of agent-core {@code AgentCounters} and {@code TransportCounters}).
 *
 * @param values counter name with labels to value
 */
public record AgentCounts(Map<String, Long> values) {

  /** Transactions the agent dropped (queue bound, age, batches given up). */
  public static final String DROPPED_TRANSACTIONS =
      "argos_agent_dropped_total{queue=\"transaction\"}";

  /** Transactions the server reported dropped ({@code Ack.dropped_items}: quota, full WAL). */
  public static final String SERVER_DROPPED_TRANSACTIONS =
      "argos_agent_server_dropped_items_total{kind=\"transactions\"}";

  /** Sessions the agent established. */
  public static final String SESSIONS = "argos_agent_session_established_total";

  /** Batches the server acknowledged, every kind ({@code {kind=...}}). */
  public static final String BATCHES_SENT = "argos_agent_batches_sent_total";

  /** Resends of a batch with the same {@code seq}, every kind. */
  public static final String BATCH_RETRIES = "argos_agent_batch_retries_total";

  /** Copies. */
  public AgentCounts {
    values = Map.copyOf(values);
  }

  /** The counter {@code key}, empty when the agent did not report it (missing is not zero). */
  public OptionalLong get(String key) {
    Long v = values.get(key);
    return v == null ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /** The sum over every label set of {@code metric}; empty when none is reported. */
  public OptionalLong sum(String metric) {
    long sum = 0;
    boolean any = false;
    for (Map.Entry<String, Long> e : values.entrySet()) {
      String k = e.getKey();
      if (k.equals(metric) || k.startsWith(metric + "{")) {
        sum += e.getValue();
        any = true;
      }
    }
    return any ? OptionalLong.of(sum) : OptionalLong.empty();
  }

  /** {@code after - before} of {@code key}; empty when either lacks it. */
  public static OptionalLong delta(AgentCounts before, AgentCounts after, String key) {
    OptionalLong a = before.get(key);
    OptionalLong b = after.get(key);
    return a.isPresent() && b.isPresent()
        ? OptionalLong.of(b.getAsLong() - a.getAsLong())
        : OptionalLong.empty();
  }

  /** {@code after - before} of the sum of {@code metric}; empty when either lacks it. */
  public static OptionalLong sumDelta(AgentCounts before, AgentCounts after, String metric) {
    OptionalLong a = before.sum(metric);
    OptionalLong b = after.sum(metric);
    return a.isPresent() && b.isPresent()
        ? OptionalLong.of(b.getAsLong() - a.getAsLong())
        : OptionalLong.empty();
  }
}
