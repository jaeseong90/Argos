/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.byReason;
import static io.argos.agent.testing.servlet.ServletRuns.delta;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exclusion rules (10 §7.2, F-A23, D-19) on every shop jar app of {@code -Pagent.e2e}, default
 * rules plus {@code instrumentation.ignore-user-agents=kube-probe/*}:
 *
 * <p>T-AG-012 / T-ACC-19: 100 noise requests (20 each: {@code /actuator/health}, {@code
 * /favicon.ico}, {@code /static/app.js}, CORS preflight {@code OPTIONS /api/orders}, {@code GET
 * /api/orders/1} with {@code User-Agent: kube-probe/1.29}) create 0 transactions, and the agent's
 * self-diagnostics count exactly 100 more exclusions: {@code method} 20, {@code path} 40, {@code
 * extension} 20, {@code user_agent} 20 ({@code argos_agent_ignored_total{reason}}; 18 §10 calls it
 * {@code transactions_ignored_total}).
 */
@Tag("req:F-A23")
@Tag("req:D-19")
@ExtendWith(ServletRuns.class)
class IgnoreRulesTest {
  /** The exclusion counter of the agent (AgentCounters), and the 18 §10 name. */
  static final List<String> COUNTERS =
      List.of("argos_agent_ignored_total", "argos_agent_transactions_ignored_total");

  static final int EACH = 20;

  @TestFactory
  @DisplayName("T-AG-012/T-ACC-19 exclusion rules on noise requests (shop jar apps)")
  Stream<DynamicTest> noise() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-012/T-ACC-19 "
                  + combo
                  + ": 100 noise requests (health, favicon, static .js, OPTIONS, kube-probe"
                  + " User-Agent) are 0 transactions and 100 counted exclusions by reason",
              () -> noiseIsExcluded(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    return tests.stream();
  }

  static Map<String, Long> reasons(SelfDiagnostics diagnostics) {
    Map<String, Long> all = new LinkedHashMap<>();
    for (String counter : COUNTERS) {
      byReason(diagnostics, counter)
          .forEach((reason, value) -> all.merge(reason, value, Long::sum));
    }
    return all;
  }

  static long sum(Map<String, Long> reasons) {
    return reasons.values().stream().mapToLong(Long::longValue).sum();
  }

  static void noiseIsExcluded(Run run) throws Exception {
    int transactionsBefore = run.settle();
    Map<String, Long> before = reasons(run.freshSelfDiagnostics());
    for (int i = 0; i < EACH; i++) {
      assertThat(run.get("/actuator/health").status()).isEqualTo(200);
      assertThat(run.get("/favicon.ico").status()).isEqualTo(200);
      assertThat(run.get("/static/app.js").status()).isEqualTo(200);
      assertThat(
              run.call(
                      "OPTIONS",
                      "/api/orders",
                      Map.of(
                          "Origin", "http://localhost:5173",
                          "Access-Control-Request-Method", "GET"))
                  .status())
          .isEqualTo(200);
      assertThat(run.call("GET", "/api/orders/1", Map.of("User-Agent", "kube-probe/1.29")).status())
          .isEqualTo(200);
    }
    long expected = sum(before) + 5L * EACH;
    long deadline = System.nanoTime() + ServletRuns.WAIT.toNanos();
    Map<String, Long> after = reasons(run.freshSelfDiagnostics());
    while (sum(after) < expected && System.nanoTime() < deadline) {
      Thread.sleep(500);
      after = reasons(run.app.selfDiagnostics());
    }
    Thread.sleep(2500);
    after = reasons(run.freshSelfDiagnostics());
    assertThat(run.settle() - transactionsBefore)
        .as("F-A23: noise requests create no transaction%n%s", run.describe())
        .isZero();
    assertThat(sum(after) - sum(before))
        .as("exclusions counted (before %s, after %s)", before, after)
        .isEqualTo(5L * EACH);
    assertThat(delta(before, after, "method")).as("OPTIONS").isEqualTo(EACH);
    assertThat(delta(before, after, "path")).as("health and favicon").isEqualTo(2L * EACH);
    assertThat(delta(before, after, "extension")).as("static .js").isEqualTo(EACH);
    assertThat(delta(before, after, "user_agent")).as("kube-probe").isEqualTo(EACH);
    System.out.println("[wo12] T-ACC-19 " + run.combo + ": exclusions " + before + " -> " + after);
  }
}
