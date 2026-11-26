/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.storage.clickhouse.MemoryBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-18")
class ClickHouseSettingsRuleTest {

  private static final long GIB = MemoryBudget.GIB;

  private static ClickHouseProbe.Settings asConfigured(long containerLimit) {
    MemoryBudget b = MemoryBudget.forContainerLimit(containerLimit);
    return new ClickHouseProbe.Settings(
        b.serverBytes(), 512L * 1024 * 1024, 0, b.readerQueryBytes(), 10, 4);
  }

  @Test
  @DisplayName(
      "T-NFR-18a the settings of deploy/clickhouse for the container limit have no violation")
  void configured() {
    assertThat(ClickHouseSettingsRule.violations(4 * GIB, asConfigured(4 * GIB))).isEmpty();
    long full = 7_680L * 1024 * 1024;
    assertThat(ClickHouseSettingsRule.violations(full, asConfigured(full))).isEmpty();
    // 0.9 of the container limit; the reader share is 0.4 of the server value (13 §7.1).
    assertThat(asConfigured(full).maxServerMemoryUsage()).isEqualTo(full / 10 * 9);
    assertThat(asConfigured(full).readerMaxMemoryUsage()).isEqualTo(full / 10 * 9 / 5 * 2);
  }

  @Test
  @DisplayName(
      "T-NFR-18a a wrong server limit, mark cache, uncompressed cache or reader limit is a"
          + " violation each")
  void violations() {
    ClickHouseProbe.Settings ok = asConfigured(4 * GIB);
    ClickHouseProbe.Settings wrong =
        new ClickHouseProbe.Settings(ok.maxServerMemoryUsage() + 1, 1024, 1024, 3 * GIB, 30, 8);
    assertThat(ClickHouseSettingsRule.violations(4 * GIB, wrong))
        .hasSize(6)
        .anySatisfy(v -> assertThat(v).startsWith("max_server_memory_usage"))
        .anySatisfy(v -> assertThat(v).startsWith("mark_cache_size"))
        .anySatisfy(v -> assertThat(v).startsWith("uncompressed_cache_size"))
        .anySatisfy(v -> assertThat(v).startsWith("argos_reader max_memory_usage"))
        .anySatisfy(v -> assertThat(v).startsWith("argos_reader max_execution_time"))
        .anySatisfy(v -> assertThat(v).startsWith("argos_reader max_threads"));
    ClickHouseProbe.Settings unread = new ClickHouseProbe.Settings(-1, -1, -1, 0, 10, 4);
    assertThat(ClickHouseSettingsRule.violations(4 * GIB, unread)).hasSize(4);
  }

  @Test
  @DisplayName("T-NFR-18a the description names every setting")
  void describe() {
    assertThat(ClickHouseSettingsRule.describe(asConfigured(4 * GIB)))
        .contains("max_server_memory_usage 3.60 GiB")
        .contains("mark_cache_size 512.00 MiB")
        .contains("uncompressed_cache_size 0")
        .contains("10 s / 4 threads");
  }
}
