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
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-SEC-25 (SEC-25, 16 §5.6, 10 §6.10) in the running agent: local custom method items with a
 * forbidden class prefix are ignored with a WARN at start; a pushed item with one is rejected
 * {@code FORBIDDEN_PATTERN}; a pushed item that matches more than 500 loaded classes is disabled
 * with a WARN and counted in self-diagnostics. The unit cases are agent-core {@code
 * command.CustomMethodPolicyTest}; the custom advice is WO-18.
 */
@Tag("req:SEC-25")
class CustomMethodPolicyE2eTest {
  private static final String KEY = "instrumentation.custom-methods";

  @TempDir Path work;

  @Test
  @DisplayName(
      "T-SEC-25 forbidden prefixes ignored with a WARN (local) and rejected FORBIDDEN_PATTERN"
          + " (push); an item matching more than 500 classes is disabled, WARN, self-diagnostics")
  void policyInTheAgent() throws Exception {
    RemoteConfigRuns.Combo combo = RemoteConfigRuns.firstBoot3().orElse(null);
    Assumptions.assumeTrue(combo != null, "no shop-boot3@<jdk> in -Pagent.e2e");
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      RunningApp app =
          RemoteConfigRuns.shop(work.resolve("app"), combo, collector)
              .agentConfig(KEY, "[java.lang.String#*, io.argos.sample.**#*]")
              .agentConfig(
                  "remote-config.allow", "[" + RemoteConfigRuns.DEFAULT_ALLOW + ", " + KEY + "]")
              .start(Duration.ofMinutes(4));
      try {
        RemoteConfigRuns.awaitEstablished(app, collector);
        RemoteConfigRuns.get(app, "/api/orders/1", 2);
        assertThat(app.agentLog())
            .contains(
                "local configuration: instrumentation.custom-methods item 1 ignored: the class"
                    + " pattern starts with the forbidden prefix java.")
            .contains(
                "local configuration: instrumentation.custom-methods item 2 ignored: the class"
                    + " pattern starts with the forbidden prefix io.argos.");
        SelfDiagnostics start = RemoteConfigRuns.selfDiagnostics(app, collector);
        assertThat(RemoteConfigRuns.extra(start, "custom_methods_items_ignored")).isEqualTo(2);

        ConfigApplyOutcome forbidden =
            RemoteConfigRuns.push(
                collector,
                Map.of(KEY, "[com.example.A#a, jakarta.servlet.http.HttpServlet#service]"));
        assertThat(forbidden.appliedKeys()).isEmpty();
        assertThat(forbidden.rejected()).containsEntry(KEY, "FORBIDDEN_PATTERN");
        assertThat(forbidden.message(KEY))
            .isEqualTo("forbidden_pattern: item 2 starts with the forbidden prefix jakarta.");
        assertThat(forbidden.retransformedClasses()).isZero();

        ConfigApplyOutcome tooWide =
            RemoteConfigRuns.push(collector, Map.of(KEY, "[org.springframework.**#*]"));
        assertThat(tooWide.appliedKeys()).containsExactly(KEY);
        assertThat(tooWide.retransformedClasses())
            .as("the disabled item contributes no class")
            .isZero();
        String disabledWarn =
            "instrumentation.custom-methods item 1 disabled: it matches more than 500 classes";
        // The agent log is written asynchronously: wait for the line.
        RemoteConfigRuns.await(() -> app.agentLog().contains(disabledWarn), Duration.ofSeconds(10));
        assertThat(app.agentLog())
            .contains(disabledWarn)
            .contains(KEY + "(forbidden_pattern)")
            .doesNotContain("HttpServlet#service");
        Thread.sleep(1_200L);
        SelfDiagnostics after = RemoteConfigRuns.selfDiagnostics(app, collector);
        assertThat(RemoteConfigRuns.extra(after, "custom_methods_items_disabled")).isEqualTo(1);
        assertThat(RemoteConfigRuns.extra(after, "custom_methods_items_ignored")).isZero();
        assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
      } finally {
        app.close();
        RemoteConfigRuns.keepEvidence("custom-policy-" + combo.jdk(), app);
      }
    }
  }
}
