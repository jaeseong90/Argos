/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0010 C-95: the self-diagnostics harness has two sources, the {@code argos.test.selfdiag-file}
 * snapshot and the {@code SELF_DIAGNOSTICS} command, read by one parser ({@code
 * launcher.SelfDiagnostics}). In one run of shop-boot3 the command result agrees with the file
 * snapshots written just before and just after it on every shared key whose value did not change
 * between those two snapshots (module states and error counts, counters, gauges, {@code
 * premain_ms}), and adds the 10 §12.1 items (version, effective configuration with sources) without
 * the token.
 */
@Tag("req:F-A12")
class SelfDiagCommandE2eTest {
  @TempDir Path work;

  @Test
  @DisplayName(
      "C-95 the SELF_DIAGNOSTICS command and the self-diagnostics file agree on their shared keys,"
          + " parsed by the same harness parser")
  void commandMatchesFile() throws Exception {
    List<Integer> jdks = CommandRuns.shopJdks();
    Assumptions.assumeFalse(jdks.isEmpty(), "no shop-boot3@<jdk> in -Pagent.e2e");
    int jdk = jdks.get(0);
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      RunningApp app =
          CommandRuns.shop(work.resolve("app"), jdk, collector, new LinkedHashMap<>())
              .start(Duration.ofMinutes(4));
      try {
        CommandRuns.awaitEstablished(app, collector);
        for (int i = 0; i < 5; i++) {
          assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
        }
        Thread.sleep(2_500L);
        SelfDiagnostics before = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(10));
        assertThat(before.available()).as(before.reason()).isTrue();
        Instant asked = Instant.now();
        SelfDiagnostics command = app.selfDiagnostics(collector);
        assertThat(command.available()).as(command.reason()).isTrue();
        SelfDiagnostics after = app.awaitSelfDiagnostics(asked, Duration.ofSeconds(10));
        assertThat(after.available()).as(after.reason()).isTrue();

        // Modules: same names, states and error counts.
        assertThat(command.modules()).isEqualTo(after.modules());
        // premain_ms is fixed after the start.
        assertThat(command.decimal("premain_ms")).isEqualTo(before.decimal("premain_ms"));
        int compared = 0;
        // The file's own "counters" member (its development-only registry_counters are never in
        // the command result, C-95).
        Map<?, ?> fileCounters = (Map<?, ?>) before.json().get("counters");
        assertThat(fileCounters).as("counters of the file snapshot").isNotEmpty();
        for (Map.Entry<String, Long> counter : before.counters().entrySet()) {
          if (!fileCounters.containsKey(counter.getKey())) {
            continue;
          }
          Long later = after.counters().get(counter.getKey());
          if (Objects.equals(counter.getValue(), later)) {
            assertThat(command.counters())
                .as("counter %s (unchanged between the file snapshots)", counter.getKey())
                .containsEntry(counter.getKey(), later);
            compared++;
          }
        }
        for (String field :
            List.of(
                "threadlocal_contexts_live",
                "active_registry_size",
                "weakmap_size",
                "weakmap_entries")) {
          if (before.json().containsKey(field)
              && Objects.equals(before.json().get(field), after.json().get(field))) {
            assertThat(command.json().get(field)).as(field).isEqualTo(before.json().get(field));
            compared++;
          }
        }
        System.out.println("[wo17-selfdiag] jdk=" + jdk + " shared values compared=" + compared);
        assertThat(compared).as("shared values compared").isGreaterThan(5);

        // The command adds the 10 §12.1 items.
        assertThat(command.member("agent.version")).isPresent();
        assertThat(command.member("config_hash")).isPresent();
        assertThat(command.member("effective_config.app.name.value")).contains("shop-boot3");
        assertThat(command.member("effective_config.app.name.source")).contains("sysprop");
        assertThat(command.member("remote_config_allow")).isPresent();
        CommandOutcome raw = CommandRuns.run(collector, "SELF_DIAGNOSTICS", Map.of());
        assertThat(raw.status()).isIn("OK", "COOLDOWN");
        if (raw.ok()) {
          assertThat(raw.payloadText()).doesNotContain(collector.token());
          assertThat(raw.payload().size()).isLessThanOrEqualTo(256 * 1024);
        }
      } finally {
        app.close();
        CommandRuns.keepEvidence("selfdiag-" + jdk, app);
      }
    }
  }
}
