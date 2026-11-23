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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-AG-046 (F-A15, 10 §12.2 step 4, §6.10 "동적 추가"): a remote {@code instrumentation.custom-methods}
 * change retransforms the loaded classes it matches, at most 50 classes per change and one change
 * per minute. In a shop app whose local {@code remote-config.allow} adds the key: a change matching
 * more than 50 Spring classes is refused {@code LIMIT_EXCEEDED retransform_limit} without using up
 * the minute; a change matching two classes right after it is retransformed ({@code
 * retransformed_classes=2}) and the app keeps recording; another change within the minute is
 * refused and the value in force stays. The custom advice (a METHOD step) is WO-18; here a
 * retransformation re-applies the agent's existing transformations.
 */
@Tag("req:F-A15")
@Tag("req:SEC-25")
class RetransformLimitTest {
  private static final String KEY = "instrumentation.custom-methods";
  private static final String TWO_CLASSES =
      "[org.springframework.web.servlet.DispatcherServlet#doService,"
          + " org.springframework.web.servlet.FrameworkServlet#service]";

  @TempDir Path work;

  static List<RemoteConfigRuns.Combo> combos() {
    return RemoteConfigRuns.shopCombos();
  }

  @ParameterizedTest(name = "T-AG-046 {0}: retransform limit of 50 classes and one per minute")
  @MethodSource("combos")
  void limit(RemoteConfigRuns.Combo combo) throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      RunningApp app =
          RemoteConfigRuns.shop(work.resolve("app"), combo, collector)
              .agentConfig(
                  "remote-config.allow", "[" + RemoteConfigRuns.DEFAULT_ALLOW + ", " + KEY + "]")
              // a pattern of a few hundred Spring classes stays active (500 would disable it)
              .agentConfig(KEY + ".max-classes-per-pattern", "10000")
              .start(Duration.ofMinutes(4));
      try {
        RemoteConfigRuns.awaitEstablished(app, collector);
        RemoteConfigRuns.get(app, "/api/orders/1", 2);

        // More than 50 classes: refused, the minute stays free.
        ConfigApplyOutcome many =
            RemoteConfigRuns.push(collector, Map.of(KEY, "[org.springframework.core.**#*]"));
        assertThat(many.appliedKeys()).isEmpty();
        assertThat(many.rejected()).containsEntry(KEY, "LIMIT_EXCEEDED");
        assertThat(many.message(KEY))
            .startsWith("retransform_limit: ")
            .contains("at most 50 per request");
        assertThat(many.retransformedClasses()).isZero();

        // Two classes right after: retransformed.
        ConfigApplyOutcome two = RemoteConfigRuns.push(collector, Map.of(KEY, TWO_CLASSES));
        assertThat(two.appliedKeys()).containsExactly(KEY);
        assertThat(two.retransformedClasses()).isEqualTo(2);
        System.out.println(
            "[wo17-retransform] app="
                + combo
                + " classes="
                + two.retransformedClasses()
                + " duration_ms="
                + two.durationMs()
                + " push_to_result_ms="
                + two.roundTripMs());

        // The retransformed DispatcherServlet still records (existing transformations re-applied).
        RemoteConfigRuns.get(app, "/api/orders/7", 3);
        assertThat(
                RemoteConfigRuns.awaitTransactions(
                    collector, "/api/orders/7", 3, Duration.ofSeconds(30)))
            .as("transactions after the retransformation")
            .isTrue();

        // Another change within the minute: refused, the value in force stays.
        ConfigApplyOutcome soon =
            RemoteConfigRuns.push(
                collector, Map.of(KEY, "[org.springframework.web.servlet.DispatcherServlet#*]"));
        assertThat(soon.rejected()).containsEntry(KEY, "LIMIT_EXCEEDED");
        assertThat(soon.message(KEY))
            .startsWith("retransform_limit: at most one retransformation per minute");
        assertThat(soon.retransformedClasses()).isZero();

        SelfDiagnostics diagnostics = RemoteConfigRuns.selfDiagnostics(app, collector);
        assertThat(RemoteConfigRuns.effectiveValue(diagnostics, KEY))
            .contains("DispatcherServlet#doService")
            .doesNotContain("DispatcherServlet#*");
        assertThat(RemoteConfigRuns.effectiveSource(diagnostics, KEY)).isEqualTo("remote");
        assertThat(RemoteConfigRuns.extra(diagnostics, "retransform_requests")).isEqualTo(3);
        assertThat(RemoteConfigRuns.extra(diagnostics, "retransform_rejected")).isEqualTo(2);
        assertThat(RemoteConfigRuns.extra(diagnostics, "retransformed_classes")).isEqualTo(2);
        assertThat(RemoteConfigRuns.extra(diagnostics, "retransform_failed_classes")).isZero();

        // The agent log is written asynchronously.
        RemoteConfigRuns.await(
            () -> app.agentLog().contains("custom methods change refused (retransform_limit)"),
            Duration.ofSeconds(10));
        assertThat(app.agentLog())
            .contains("custom methods change: 2 class(es) retransformed")
            .contains("custom methods change refused (retransform_limit)");
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
      } finally {
        app.close();
        RemoteConfigRuns.keepEvidence("retransform-" + combo.app() + "-" + combo.jdk(), app);
      }
    }
  }
}
