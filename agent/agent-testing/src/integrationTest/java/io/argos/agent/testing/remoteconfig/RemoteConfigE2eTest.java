/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.remoteconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.ConfigApplyOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-AG-069 and the agent side of T-SEC-23 (10 §12.2, 16 §5.5, SEC-23, ADR-0010 C-92, C-118 (3)) end
 * to end: the real agent in a shop app answers a {@code ConfigPush} of the FakeCollector. Allowed
 * keys take effect and are visible in what the agent sends ({@code sampling.profile.normal-rate=0}
 * stops the profile batches, {@code instrumentation.ignore-paths} drops the transactions of that
 * path) and in the {@code SELF_DIAGNOSTICS} effective configuration with source {@code remote}; a
 * key outside the allow list, an immutable, an unknown and an invalid key and a mask pattern
 * removal are rejected with their code while the local value stays (value and source in
 * self-diagnostics); a key widened by {@code -Dargos.remote-config.allow} is applied; the new
 * configuration hash reaches the collector ({@code InstanceUpdate.config_hash}); the WARN carries
 * key names, no values. The agent-side contribution to N-25 is printed as {@code [wo17-n25]}
 * (laptop numbers are references only).
 */
@Tag("req:SEC-23")
@Tag("req:F-A13")
@Tag("req:N-25")
class RemoteConfigE2eTest {
  private static final String SENTINEL = "wo17-push-sentinel-7f3a";
  private static final String LOCAL_MASK = "wo17-local-[0-9]+";

  @TempDir Path work;

  static List<RemoteConfigRuns.Combo> combos() {
    return RemoteConfigRuns.shopCombos();
  }

  @ParameterizedTest(name = "T-AG-069/T-SEC-23 {0}: hard cap end to end")
  @MethodSource("combos")
  void hardCap(RemoteConfigRuns.Combo combo) throws Exception {
    Assumptions.assumeTrue(combo != null, "no shop app in -Pagent.e2e");
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      RunningApp app =
          RemoteConfigRuns.shop(work.resolve("app"), combo, collector)
              .agentConfig("sampling.profile.normal-rate", "1.0")
              .agentConfig("capture.sql-parameters", "masked")
              .agentConfig("capture.mask-patterns", "[\"" + LOCAL_MASK + "\"]")
              .agentConfig(
                  "remote-config.allow",
                  "["
                      + RemoteConfigRuns.DEFAULT_ALLOW
                      + ", capture.query-string, capture.mask-patterns]")
              .start(Duration.ofMinutes(4));
      try {
        RemoteConfigRuns.awaitEstablished(app, collector);
        // Before: every normal transaction has a profile (local normal-rate 1.0).
        RemoteConfigRuns.get(app, "/api/orders/1", 3);
        RemoteConfigRuns.get(app, "/api/version", 1);
        assertThat(
                RemoteConfigRuns.await(
                    () ->
                        RemoteConfigRuns.profileBatchesAfter(collector, Instant.EPOCH) > 0
                            && RemoteConfigRuns.transactionsAt(collector, "/api/version") >= 1,
                    Duration.ofSeconds(30)))
            .as("profiles and /api/version transactions before the push")
            .isTrue();
        Thread.sleep(3_000L); // let the batches of these requests drain
        SelfDiagnostics before = RemoteConfigRuns.selfDiagnostics(app, collector);
        String hashBefore = String.valueOf(before.member("config_hash").orElse(""));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("sampling.profile.normal-rate", "0");
        values.put("instrumentation.ignore-paths", "[/api/version, /actuator/health]");
        values.put("capture.query-string", "full"); // widened by -Dargos.remote-config.allow
        values.put("capture.sql-parameters", "full"); // not in the allow list
        values.put("server.address", "attacker.example:1"); // immutable
        values.put("no.such.key", SENTINEL); // unknown
        values.put("sampling.profile.max-steps", SENTINEL); // invalid
        values.put("capture.mask-patterns", "[\"wo17-other\"]"); // drops the local pattern
        ConfigApplyOutcome outcome = RemoteConfigRuns.push(collector, values);
        Instant applied = Instant.now();

        assertThat(outcome.appliedKeys())
            .containsExactlyInAnyOrder(
                "sampling.profile.normal-rate",
                "instrumentation.ignore-paths",
                "capture.query-string");
        assertThat(outcome.rejected())
            .containsEntry("capture.sql-parameters", "NOT_ALLOWED")
            .containsEntry("server.address", "NOT_ALLOWED")
            .containsEntry("no.such.key", "UNKNOWN_KEY")
            .containsEntry("sampling.profile.max-steps", "INVALID_VALUE")
            .containsEntry("capture.mask-patterns", "FORBIDDEN_PATTERN")
            .hasSize(5);
        assertThat(outcome.message("capture.sql-parameters")).startsWith("not_allowed: ");
        assertThat(outcome.message("server.address")).startsWith("immutable: ");
        assertThat(outcome.message("capture.mask-patterns")).startsWith("reduce_masking: ");
        assertThat(outcome.toString()).doesNotContain(SENTINEL).doesNotContain("attacker");
        System.out.println(
            "[wo17-n25] app="
                + combo
                + " push_to_result_ms="
                + outcome.roundTripMs()
                + " duration_ms="
                + outcome.durationMs()
                + " (shared laptop: INVALID reference)");
        assertThat(outcome.roundTripMs())
            .as("N-25 agent contribution: push to ConfigApplyResult")
            .isLessThanOrEqualTo(10_000L);
        assertThat(outcome.durationMs()).isLessThanOrEqualTo(10_000L);

        // The new hash: answered and sent as InstanceUpdate.config_hash.
        assertThat(outcome.effectiveConfigHash()).hasSize(16).isNotEqualTo(hashBefore);
        assertThat(collector.awaitConfigHash(outcome.effectiveConfigHash(), Duration.ofSeconds(10)))
            .as(
                "InstanceUpdate.config_hash %s; got %s",
                outcome.effectiveConfigHash(), collector.configHashes())
            .isTrue();

        // Effects: no profile for normal transactions, no transaction of the ignored path.
        RemoteConfigRuns.get(app, "/api/orders/2", 5);
        RemoteConfigRuns.get(app, "/api/version", 3);
        assertThat(
                RemoteConfigRuns.awaitTransactions(
                    collector, "/api/orders/2", 5, Duration.ofSeconds(30)))
            .as("transactions after the push")
            .isTrue();
        Thread.sleep(3_000L);
        assertThat(RemoteConfigRuns.profileBatchesAfter(collector, applied))
            .as("sampling.profile.normal-rate=0: no profile batch")
            .isZero();
        assertThat(RemoteConfigRuns.transactionsAt(collector, "/api/version"))
            .as("instrumentation.ignore-paths: no new /api/version transaction")
            .isEqualTo(1);

        // Self-diagnostics: applied keys have source remote, rejected keys keep the local value.
        SelfDiagnostics after = RemoteConfigRuns.selfDiagnostics(app, collector);
        assertThat(after.member("config_hash")).contains(outcome.effectiveConfigHash());
        assertThat(RemoteConfigRuns.effectiveValue(after, "sampling.profile.normal-rate"))
            .isIn("0", "0.0");
        assertThat(RemoteConfigRuns.effectiveSource(after, "sampling.profile.normal-rate"))
            .isEqualTo("remote");
        assertThat(RemoteConfigRuns.effectiveValue(after, "capture.query-string"))
            .isEqualTo("full");
        assertThat(RemoteConfigRuns.effectiveSource(after, "capture.query-string"))
            .isEqualTo("remote");
        assertThat(RemoteConfigRuns.effectiveValue(after, "capture.sql-parameters"))
            .isEqualTo("masked");
        assertThat(RemoteConfigRuns.effectiveSource(after, "capture.sql-parameters"))
            .isEqualTo("sysprop");
        assertThat(RemoteConfigRuns.effectiveValue(after, "capture.mask-patterns"))
            .contains(LOCAL_MASK)
            .doesNotContain("wo17-other");
        assertThat(RemoteConfigRuns.effectiveSource(after, "capture.mask-patterns"))
            .isEqualTo("sysprop");
        assertThat(RemoteConfigRuns.effectiveValue(after, "server.address"))
            .isEqualTo(collector.agentSettings().get("server.address"));
        assertThat(RemoteConfigRuns.effectiveValue(after, "sampling.profile.max-steps"))
            .isEqualTo(RemoteConfigRuns.effectiveValue(before, "sampling.profile.max-steps"));

        // 16 §5.5 step 6: one WARN with key names and reasons, never values.
        assertThat(app.agentLog())
            .contains("remote config rejected keys: ")
            .contains("capture.sql-parameters(not_allowed)")
            .contains("server.address(immutable)")
            .contains("no.such.key(unknown_key)")
            .doesNotContain(SENTINEL)
            .doesNotContain("attacker.example")
            .doesNotContain("wo17-other");
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
        assertThat(collector.sessions()).as("one session throughout").hasSize(1);
      } finally {
        app.close();
        RemoteConfigRuns.keepEvidence("remoteconfig-" + combo.app() + "-" + combo.jdk(), app);
      }
    }
  }
}
