/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The harness side of the agent's self-diagnostics file (18 §19, ADR-0010 C-95). */
@Tag("req:N-02")
class SelfDiagnosticsTest {
  static final String SNAPSHOT =
      "{\"format\":1,\"threadlocal_contexts_live\":0,\"active_registry_size\":3,"
          + "\"weakmap_size\":0,\"weakmap_entries\":5,\"agent_threads_cpu_seconds_total\":0.125,"
          + "\"direct_memory_bytes\":8192,\"premain_ms\":231.456,"
          + "\"counters\":{\"argos_agent_dropped_total{queue=\\\"transaction\\\"}\":2,"
          + "\"argos_agent_sql_outside_tx_total\":4,"
          + "\"argos_agent_orphan_steps_total{type=\\\"sql\\\"}\":1,"
          + "\"argos_agent_orphan_steps_total{type=\\\"http\\\"}\":2},"
          + "\"modules\":[{\"id\":3,\"name\":\"jdbc\",\"state\":\"DISABLED_BY_ERRORS\","
          + "\"errors\":11},{\"id\":1,\"name\":\"servlet\",\"state\":\"ENABLED\",\"errors\":0}]}";

  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-AG-062f WO-13 registry members: pools and connectors by name with their measured values;"
          + " a missing value (DBCP2 waiting) is absent, not zero; registry counters join counters")
  void registryMembers() {
    SelfDiagnostics diagnostics =
        SelfDiagnostics.parse(
            "{\"format\":1,\"pools\":[{\"name\":\"HikariPool-1\",\"kind\":\"hikari\",\"active\":0,"
                + "\"idle\":10,\"waiting\":0,\"total\":10,\"max\":10},{\"name\":"
                + "\"BasicDataSource-1\",\"kind\":\"dbcp2\",\"active\":1,\"idle\":2,\"max\":8}],"
                + "\"connectors\":[{\"name\":\"http-nio-8080\",\"port\":8080,\"threads_busy\":1,"
                + "\"threads_current\":10,\"connections_max\":8192}],\"registry_counters\":"
                + "{\"argos_agent_registry_overflow_total{registry=\\\"pool\\\"}\":0}}");
    assertThat(diagnostics.pools())
        .extracting(SelfDiagnostics.Gauges::name)
        .containsExactly("HikariPool-1", "BasicDataSource-1");
    SelfDiagnostics.Gauges dbcp2 = diagnostics.pools().get(1);
    assertThat(dbcp2.kind()).isEqualTo("dbcp2");
    assertThat(dbcp2.value("max")).isEqualTo(8);
    assertThat(dbcp2.has("waiting")).isFalse();
    assertThatThrownBy(() -> dbcp2.value("waiting"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class);
    SelfDiagnostics.Gauges connector = diagnostics.connectors().get(0);
    assertThat(connector.value("port")).isEqualTo(8080);
    assertThat(connector.has("threads_max")).isFalse();
    assertThat(diagnostics.counter("argos_agent_registry_overflow_total{registry=\"pool\"}"))
        .isZero();
    assertThatThrownBy(() -> SelfDiagnostics.parse(SNAPSHOT).pools())
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class)
        .hasMessageContaining("pools: not reported");
  }

  @Test
  @DisplayName(
      "18 §19 fields of the agent's snapshot, the flat counters sql_outside_tx_total and"
          + " orphan_steps_total, modules")
  void parsesTheAgentSnapshot() {
    SelfDiagnostics diagnostics = SelfDiagnostics.parse(SNAPSHOT);
    assertThat(diagnostics.available()).isTrue();
    assertThat(diagnostics.reason()).isEmpty();
    assertThat(diagnostics.value("threadlocal_contexts_live")).isZero();
    assertThat(diagnostics.value("active_registry_size")).isEqualTo(3);
    assertThat(diagnostics.value("weakmap_size")).isZero();
    assertThat(diagnostics.value(SelfDiagnostics.WEAKMAP_ENTRIES)).isEqualTo(5);
    assertThat(diagnostics.decimal("agent_threads_cpu_seconds_total")).isEqualTo(0.125);
    assertThat(diagnostics.value("direct_memory_bytes")).isEqualTo(8192);
    assertThat(diagnostics.decimal("premain_ms")).isEqualTo(231.456);
    assertThat(diagnostics.value("sql_outside_tx_total")).isEqualTo(4);
    assertThat(diagnostics.value("orphan_steps_total")).isEqualTo(3);
    assertThat(diagnostics.counter("argos_agent_dropped_total{queue=\"transaction\"}"))
        .isEqualTo(2);
    assertThat(diagnostics.module("jdbc"))
        .hasValue(new SelfDiagnostics.Module(3, "jdbc", "DISABLED_BY_ERRORS", 11));
    assertThat(diagnostics.module("servlet").orElseThrow().state()).isEqualTo("ENABLED");
    assertThat(diagnostics.module("nope")).isEmpty();
    assertThat(SelfDiagnostics.FIELDS).hasSize(8);
  }

  @Test
  @DisplayName(
      "missing is not zero: an unreported field, an unreported counter and a run without a"
          + " snapshot throw instead of returning 0")
  void missingIsNotZero() throws IOException {
    SelfDiagnostics partial =
        SelfDiagnostics.parse("{\"format\":1,\"counters\":{},\"modules\":[]}");
    assertThatThrownBy(() -> partial.value("weakmap_size"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class)
        .hasMessageContaining("weakmap_size: not reported by the agent");
    assertThatThrownBy(() -> partial.value(SelfDiagnostics.WEAKMAP_ENTRIES))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class)
        .hasMessageContaining("weakmap_entries: not reported by the agent");
    assertThatThrownBy(() -> partial.value("orphan_steps_total"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class);
    assertThatThrownBy(() -> partial.counter("argos_agent_x_total"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class);

    SelfDiagnostics none = SelfDiagnostics.read(dir.resolve("out").resolve("selfdiag.json"));
    assertThat(none.available()).isFalse();
    assertThat(none.reason()).contains("selfdiag.json").contains("release build");
    assertThatThrownBy(() -> none.value("active_registry_size"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class);
    assertThatThrownBy(none::modules).isInstanceOf(SelfDiagnostics.NotAvailableException.class);

    Path file = dir.resolve("selfdiag.json");
    Files.writeString(file, SNAPSHOT + "\n", StandardCharsets.UTF_8);
    assertThat(SelfDiagnostics.read(file).value("active_registry_size")).isEqualTo(3);
  }

  @Test
  @DisplayName("an unknown format or broken JSON is an error, never an empty snapshot")
  void rejectsOtherFormats() {
    assertThatThrownBy(() -> SelfDiagnostics.parse("{\"format\":2}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported format");
    assertThatThrownBy(() -> SelfDiagnostics.parse("{\"format\":1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SelfDiagnostics.parse("[1]"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
