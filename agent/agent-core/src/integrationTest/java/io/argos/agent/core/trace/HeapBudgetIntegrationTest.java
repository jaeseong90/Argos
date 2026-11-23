/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.stats.StatsAggregator;
import io.argos.agent.core.transport.Queues;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The agent's heap budget (N-02 reduced, 10 §14 "힙 예산", ADR-0009 C-87): in a forked JVM ({@link
 * HeapBudgetProbe}) every queue, the dictionary and the service names are filled to their caps with
 * worst-case data (records with 2 KB paths and 1 KB messages, profiles of 1,000 steps with 4 KB
 * details, service names of 1,024 characters); then the {@code io.argos.agent.} instance bytes of
 * {@code GC.class_histogram} and the heap growth after full GCs stay within 64 MB, the estimates
 * stay within the caps of 10 §10.1 and every dropped item is counted.
 */
@Tag("slow")
@Tag("req:N-02")
class HeapBudgetIntegrationTest {
  private static final long MB = 1024L * 1024;
  private static final long BUDGET = 64 * MB;

  @Test
  @DisplayName(
      "N-02 (reduced) queues and dictionary at their caps: io.argos.agent. histogram bytes and heap"
          + " growth <= 64 MB, estimates within the caps, every drop counted")
  void queuesAtTheirCapsFitTheBudget() throws Exception {
    Map<String, Long> values = runProbe();
    System.out.println("[N-02] heap budget probe (reference values, shared machine): " + values);

    long histogram = values.get("histogram_agent_bytes");
    long growth = values.get("heap_growth_bytes");
    assertThat(histogram).as("C-87 io.argos.agent. instance bytes").isPositive();
    assertThat(histogram).isLessThanOrEqualTo(BUDGET);
    assertThat(growth).as("heap growth after full GCs").isLessThanOrEqualTo(BUDGET);

    // Estimates within the caps of 10 §10.1 and §14.
    assertThat(values.get("queue_size_after_count_fill"))
        .as("buffer.transaction.capacity")
        .isEqualTo(20_000);
    assertThat(values.get("queue_size.transaction")).isBetween(1L, 20_000L);
    assertThat(values.get("queue_bytes.transaction") + values.get("queue_bytes.profile"))
        .as("buffer.max-bytes shared by transaction and profile items")
        .isLessThanOrEqualTo(32 * MB);
    assertThat(values.get("queue_bytes.profile")).isLessThanOrEqualTo(Queues.PROFILE_MAX_BYTES);
    assertThat(values.get("queue_size.stats")).isEqualTo(Queues.STATS_CAPACITY);
    assertThat(values.get("queue_size.metric")).isEqualTo(Queues.METRIC_CAPACITY);
    assertThat(values.get("queue_size.active")).isEqualTo(1);
    assertThat(values.get("dictionary_bytes")).isLessThanOrEqualTo(Dictionary.MAX_BYTES);
    assertThat(values.get("dictionary_overflow_frame")).isPositive();
    assertThat(values.get("service_names_bytes"))
        .as("service names at their byte bound")
        .isLessThanOrEqualTo(ServiceNames.DEFAULT_MAX_BYTES)
        .isGreaterThan(ServiceNames.DEFAULT_MAX_BYTES - ServiceNames.bytesOf(1024));
    assertThat(values.get("service_names_overflow")).isPositive();
    // Full statistics (WO-16): two windows at stats.max-keys (5,000 SQL) and 1,000 EXT keys plus
    // the overflow keys of three kinds, every key beyond the caps counted.
    long cellBytes = StatsAggregator.estimatedCellBytes();
    long windowCap =
        (StatsAggregator.DEFAULT_MAX_SQL_KEYS + StatsAggregator.MAX_EXT_KEYS + 2L * 3) * cellBytes;
    assertThat(values.get("stats_two_windows_bytes"))
        .as("two full statistics windows")
        .isGreaterThan(windowCap)
        .isLessThanOrEqualTo(2 * windowCap);
    assertThat(values.get("stats_bytes")).isPositive().isLessThanOrEqualTo(2 * windowCap);
    assertThat(values.get("stats_overflow_sql"))
        .as("SQL keys beyond stats.max-keys, two windows")
        .isGreaterThanOrEqualTo(2L * (StatsAggregator.DEFAULT_MAX_SQL_KEYS / 10));
    assertThat(values.get("stats_overflow_ext"))
        .isEqualTo(2L * (StatsAggregator.MAX_EXT_KEYS / 10));

    // Every item is kept or counted as dropped.
    assertThat(values.get("queue_size.transaction") + values.get("dropped.transaction"))
        .isEqualTo(values.get("transactions_ended"));
    assertThat(values.get("queue_size.profile") + values.get("dropped.profile"))
        .isEqualTo(values.get("profiles_captured"));
    assertThat(values.get("dropped.stats")).isEqualTo(10);
    assertThat(values.get("dropped.metric")).isEqualTo(10);
    assertThat(values.get("dropped.active")).isEqualTo(2);
    assertThat(values.get("profile_dropped_total"))
        .as("every dropped profile is a lost captured profile (10 §9.2 step 3)")
        .isEqualTo(values.get("dropped.profile"))
        .isPositive();
    // The estimates are not optimistic: the measured growth stays near what they account for.
    assertThat(growth)
        .as("heap growth vs estimated bytes " + values.get("estimated_total_bytes"))
        .isLessThanOrEqualTo(values.get("estimated_total_bytes") + 16 * MB);
  }

  private static Map<String, Long> runProbe() throws Exception {
    String java =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    List<String> command = new ArrayList<String>();
    command.add(java);
    command.add("-Xmx512m");
    command.add("-Xms512m");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(HeapBudgetProbe.class.getName());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    Map<String, Long> values = new HashMap<String, Long>();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
        int equals = line.indexOf('=');
        if (equals > 0 && line.substring(equals + 1).matches("-?[0-9]+")) {
          values.put(line.substring(0, equals), Long.parseLong(line.substring(equals + 1)));
        }
      }
    }
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
    assertThat(process.exitValue()).as(output.toString()).isZero();
    assertThat(values).as(output.toString()).containsKey("heap_growth_bytes");
    return values;
  }
}
