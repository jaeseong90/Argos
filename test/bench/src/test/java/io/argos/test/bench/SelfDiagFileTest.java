/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-BLD-015f: the agent's self-diagnostics file as the bench reads it (01 §7.3 item 5, 18 §7.4,
 * ADR-0010 C-95): parser of the real format-1 layout, the window delta of {@code
 * agent_threads_cpu_seconds_total}, missing values never read as zero, the premain source, and the
 * test-only key only on agent variants of a development build (C-88).
 */
@Tag("req:DSN-18-07")
@Tag("req:N-01")
@Tag("req:N-26")
@Tag("req:N-30")
class SelfDiagFileTest {

  /**
   * One snapshot exactly as agent-core {@code SelfDiagnosticsFile} writes it (format 1: {@code
   * SelfDiagnostics.toJson()} plus the development-only {@code pools} and {@code connectors}
   * members, one line and a newline).
   */
  static String snapshot(String cpu, String direct, String premain) {
    StringBuilder s =
        new StringBuilder(
            "{\"format\":1,\"threadlocal_contexts_live\":0,\"active_registry_size\":2,"
                + "\"weakmap_size\":0,\"weakmap_entries\":14");
    if (cpu != null) {
      s.append(",\"agent_threads_cpu_seconds_total\":").append(cpu);
    }
    if (direct != null) {
      s.append(",\"direct_memory_bytes\":").append(direct);
    }
    if (premain != null) {
      s.append(",\"premain_ms\":").append(premain);
    }
    s.append(
        ",\"stack_capture_seconds_total\":0.000000,\"stack_captures_total\":0,\"counters\":"
            + "{\"argos_agent_transactions_total\":120344,\"argos_agent_dropped_total"
            + "{reason=\\\"queue_full\\\"}\":0,\"argos_agent_log_dropped_total\":0},\"modules\":["
            + "{\"id\":0,\"name\":\"servlet\",\"state\":\"ENABLED\",\"errors\":0},"
            + "{\"id\":1,\"name\":\"jdbc\",\"state\":\"DISABLED_BY_CONFIG\",\"errors\":0,"
            + "\"reason\":\"instrumentation.disabled (agent configuration at start)\"}],"
            + "\"pools\":[{\"name\":\"HikariPool-1\",\"kind\":\"hikari\",\"active\":0,"
            + "\"idle\":10}],\"connectors\":[{\"name\":\"http-nio-8080\",\"threads_busy\":1}]}\n");
    return s.toString();
  }

  @Test
  @DisplayName(
      "T-BLD-015f the format-1 snapshot of the agent is parsed: CPU, direct memory, premain")
  void parsesTheAgentFormat() {
    SelfDiagFile.Snapshot s = SelfDiagFile.parse(snapshot("12.345", "8192", "231.456"));
    assertThat(s.available()).isTrue();
    assertThat(s.agentThreadsCpuSeconds().getAsDouble()).isEqualTo(12.345);
    assertThat(s.directMemoryBytes().getAsLong()).isEqualTo(8192);
    assertThat(s.premainMs().getAsDouble()).isEqualTo(231.456);
  }

  @Test
  @DisplayName(
      "T-BLD-015f NOT_AVAILABLE, null, absent or negative gauges are absent, never zero (C-4)")
  void missingIsNeverZero() {
    SelfDiagFile.Snapshot s = SelfDiagFile.parse(snapshot("\"NOT_AVAILABLE\"", "null", null));
    assertThat(s.available()).isTrue();
    assertThat(s.agentThreadsCpuSeconds()).isEmpty();
    assertThat(s.directMemoryBytes()).isEmpty();
    assertThat(s.premainMs()).isEmpty();
    assertThat(SelfDiagFile.parse(snapshot("-1", "-1", "-1")).agentThreadsCpuSeconds()).isEmpty();

    Map<String, Object> window = SelfDiagFile.window(s, s);
    assertThat(window)
        .containsEntry("source", "selfdiag-file")
        .doesNotContainKeys("agentThreadsCpuSeconds", "directMemoryBytes", "premainMs");
  }

  @Test
  @DisplayName(
      "T-BLD-015f measure.selfDiag: CPU is the window delta, direct memory and premain of the end")
  void windowDelta() {
    SelfDiagFile.Snapshot start =
        SelfDiagFile.parse(snapshot("1.250", "4096", "231.456")).withModified(1_000_000);
    SelfDiagFile.Snapshot end =
        SelfDiagFile.parse(snapshot("4.000", "1048576", "231.456")).withModified(1_602_000);
    Map<String, Object> w = SelfDiagFile.window(start, end);
    assertThat(Json.number(w.get("agentThreadsCpuSeconds"))).isCloseTo(2.75, within(1e-9));
    assertThat(w)
        .containsEntry("directMemoryBytes", 1_048_576L)
        .containsEntry("premainMs", 231.456)
        .containsEntry("source", "selfdiag-file")
        .containsEntry("snapshotIntervalSeconds", 602.0);
    // The contract keys of lane A (run.json measure.selfDiag), as written to run.json.
    Object roundTrip = Json.parse(Json.write(Map.of("measure", Map.of("selfDiag", w))));
    assertThat(Json.number(roundTrip, "measure", "selfDiag", "agentThreadsCpuSeconds"))
        .isCloseTo(2.75, within(1e-9));
    assertThat(Json.number(roundTrip, "measure", "selfDiag", "directMemoryBytes"))
        .isEqualTo(1_048_576.0);
  }

  @Test
  @DisplayName("T-BLD-015f a falling CPU total (an agent thread ended) is not measured, not 0")
  void fallingTotalIsNotMeasured() {
    Map<String, Object> w =
        SelfDiagFile.window(
            SelfDiagFile.parse(snapshot("5.000", "1", "2")),
            SelfDiagFile.parse(snapshot("4.500", "1", "2")));
    assertThat(w).doesNotContainKey("agentThreadsCpuSeconds");
    assertThat(String.valueOf(w.get("agentThreadsCpuNote"))).contains("fell by 0.500 s");
  }

  @Test
  @DisplayName("T-BLD-015f no file, broken JSON or another format is not available with the reason")
  void notAvailable(@TempDir Path dir) throws IOException {
    SelfDiagFile.Snapshot missing = SelfDiagFile.read(dir.resolve(SelfDiagFile.FILE_NAME));
    assertThat(missing.available()).isFalse();
    assertThat(missing.reason()).contains("no self-diagnostics file").contains("release build");
    assertThat(SelfDiagFile.parse("{\"format\":1").reason()).contains("unreadable");
    assertThat(SelfDiagFile.parse("{\"format\":2,\"premain_ms\":1}").reason())
        .contains("unsupported self-diagnostics format 2");
    assertThat(SelfDiagFile.parse("[1]").available()).isFalse();
    assertThat(SelfDiagFile.window(missing, missing)).isNull();

    // One side missing: the other's values, the missing side named, no delta.
    Map<String, Object> half =
        SelfDiagFile.window(missing, SelfDiagFile.parse(snapshot("3.0", "10", "200.5")));
    assertThat(half)
        .doesNotContainKey("agentThreadsCpuSeconds")
        .containsEntry("directMemoryBytes", 10L)
        .containsEntry("premainMs", 200.5);
    assertThat(String.valueOf(half.get("startSnapshot"))).startsWith("not available: ");
  }

  @Test
  @DisplayName("T-BLD-015f the harness waits for the agent's next write of the file")
  void awaitsTheNextWrite(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(SelfDiagFile.FILE_NAME);
    Files.writeString(file, snapshot("1.000", "1", "2"), StandardCharsets.UTF_8);
    Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));
    long before = SelfDiagFile.modified(file);
    assertThat(before).isEqualTo(1_000_000_000_000L);
    // The agent's way: a temporary file moved over the snapshot.
    CompletableFuture<Void> writer =
        CompletableFuture.runAsync(
            () -> {
              try {
                TimeUnit.MILLISECONDS.sleep(300);
                Path tmp = dir.resolve(SelfDiagFile.FILE_NAME + ".tmp");
                Files.writeString(tmp, snapshot("1.500", "1", "2"), StandardCharsets.UTF_8);
                Files.setLastModifiedTime(tmp, FileTime.fromMillis(1_000_000_002_000L));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
              } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });
    SelfDiagFile.Snapshot next = SelfDiagFile.awaitNext(file, before, Duration.ofSeconds(10));
    writer.get(10, TimeUnit.SECONDS);
    assertThat(next.agentThreadsCpuSeconds().getAsDouble()).isEqualTo(1.5);
    assertThat(next.modifiedMillis()).isEqualTo(1_000_000_002_000L);

    // No new write in time: the latest snapshot after the timeout.
    long now = SelfDiagFile.modified(file);
    long start = System.nanoTime();
    SelfDiagFile.Snapshot same = SelfDiagFile.awaitNext(file, now, Duration.ofMillis(300));
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(300_000_000L);
    assertThat(same.agentThreadsCpuSeconds().getAsDouble()).isEqualTo(1.5);
  }

  @Test
  @DisplayName("T-BLD-015f premain: the snapshot first, else the start banner, with its source")
  void premainSource() {
    String banner =
        "2026-11-26 01:02:03.456 INFO  Argos agent 0.0.0-SNAPSHOT started in 464.816 ms:"
            + " modules=12 exporter=noop\n";
    Map<String, Object> run = new LinkedHashMap<>();
    SelfDiagFile.putPremain(run, SelfDiagFile.parse(snapshot("1", "1", "401.5")), banner);
    assertThat(run)
        .containsEntry("premainMs", 401.5)
        .containsEntry("premainSource", "selfdiag-file");
    SelfDiagFile.putPremain(run, SelfDiagFile.parse(snapshot("1", "1", null)), banner);
    assertThat(run)
        .containsEntry("premainMs", 464.816)
        .containsEntry("premainSource", "agent-log-banner");
    SelfDiagFile.putPremain(run, null, "");
    assertThat(run).containsEntry("premainMs", null).containsEntry("premainSource", null);
  }

  static Platform.Setup setup(boolean selfDiag, String otlpEndpoint) {
    return new Platform.Setup(
        null,
        null,
        null,
        null,
        null,
        null,
        "shop-boot3",
        null,
        null,
        null,
        null,
        List.of(),
        Map.of(),
        Map.of("test.exporter", "noop"),
        Map.of("diag", Map.of("instrumentation.disabled", "jdbc")),
        null,
        null,
        null,
        null,
        2,
        "2g",
        null,
        selfDiag,
        otlpEndpoint);
  }

  @Test
  @DisplayName(
      "T-BLD-015f argos.test.selfdiag-file only on agent variants of a development build (C-88)")
  void selfDiagOnlyOnDevelopmentAgentVariants(@TempDir Path dir) throws IOException {
    Platform.Setup on = setup(true, null);
    assertThat(Platform.variantOptions("on", on, "shop-boot3", "/a.jar", null, "/l", "/r/x.json"))
        .endsWith("-Dargos.test.selfdiag-file=/r/x.json");
    assertThat(Platform.variantOptions("diag", on, "shop-boot3", "/a.jar", null, "/l", "/r/x.json"))
        .contains("-Dargos.instrumentation.disabled=jdbc", "-Dargos.test.selfdiag-file=/r/x.json");
    assertThat(Platform.variantOptions("off", on, "shop-boot3", "/a.jar", null, "/l", "/r/x.json"))
        .isEmpty();
    assertThat(
            Platform.variantOptions(
                "otel", on, "shop-boot3", "/a.jar", "/o.jar", "/l", "/r/x.json"))
        .noneMatch(o -> o.contains("selfdiag"));
    assertThat(
            Platform.variantOptions(
                "on", setup(false, null), "shop-boot3", "/a.jar", null, "/l", "/r/x.json"))
        .noneMatch(o -> o.contains("selfdiag"));
    // The WO-05 overloads keep working without a snapshot file.
    assertThat(Platform.variantOptions("on", on, "/a.jar", null, "/l"))
        .noneMatch(o -> o.contains("selfdiag"));

    assertThat(LoadSet.selfDiagnostics(AgentSettingsTest.agentJar(dir, "0.0.0-SNAPSHOT", false)))
        .isTrue();
    assertThat(LoadSet.selfDiagnostics(AgentSettingsTest.agentJar(dir, "1.0.0", false))).isFalse();
    assertThat(LoadSet.selfDiagDescription(false)).contains("release build").contains("jcmd");
    assertThat(LoadSet.selfDiagDescription(true)).contains("argos.test.selfdiag-file=<run>/");
  }
}
