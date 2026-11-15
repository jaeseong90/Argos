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
