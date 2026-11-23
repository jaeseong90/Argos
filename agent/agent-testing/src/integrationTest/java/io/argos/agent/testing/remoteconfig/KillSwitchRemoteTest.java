/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.remoteconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.AgentEventInfo;
import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.ConfigApplyOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-043, remote part (F-A15, 10 §12.2, 11 §4.2, ADR-0010 C-231 (4)): the first stage of the kill
 * switch ({@code enabled}) turned off and on by a {@code ConfigPush} and by {@code
 * ServerHello.config}. While it is off the app answers as before and no transaction is recorded,
 * the session (heartbeats) and the metrics keep running, and the agent sends {@code AgentEvent
 * KILL_SWITCH_ON}; turned on again, collection resumes with {@code KILL_SWITCH_OFF}. The local
 * first and second stage are {@code attach.AgentAttachTest}.
 */
@Tag("req:F-A15")
@Tag("req:SEC-23")
class KillSwitchRemoteTest {
  @TempDir Path work;

  private static RemoteConfigRuns.Combo combo() {
    RemoteConfigRuns.Combo combo = RemoteConfigRuns.firstBoot3().orElse(null);
    Assumptions.assumeTrue(combo != null, "no shop-boot3@<jdk> in -Pagent.e2e");
    return combo;
  }

  @Test
  @DisplayName(
      "T-AG-043 remote kill switch: enabled=false push stops transactions (KILL_SWITCH_ON,"
          + " heartbeats and metrics continue, responses unchanged); enabled=true resumes"
          + " (KILL_SWITCH_OFF)")
  void pushOffAndOn() throws Exception {
    RemoteConfigRuns.Combo combo = combo();
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).heartbeatIntervalS(1).start()) {
      RunningApp app =
          RemoteConfigRuns.shop(work.resolve("app"), combo, collector)
              .agentConfig("metrics.interval-seconds", "5")
              .start(Duration.ofMinutes(4));
      try {
        RemoteConfigRuns.awaitEstablished(app, collector);
        HttpResult reference = app.get("/api/orders/1");
        assertThat(reference.status()).isEqualTo(200);
        assertThat(
                RemoteConfigRuns.awaitTransactions(
                    collector, "/api/orders/1", 1, Duration.ofSeconds(30)))
            .isTrue();

        long pushed = System.nanoTime();
        ConfigApplyOutcome off = RemoteConfigRuns.push(collector, Map.of("enabled", "false"));
        assertThat(off.appliedKeys()).containsExactly("enabled");
        List<AgentEventInfo> onEvents =
            collector.awaitAgentEvents("KILL_SWITCH_ON", 1, Duration.ofSeconds(10));
        assertThat(onEvents).hasSize(1);
        System.out.println(
            "[wo17-killswitch] app="
                + combo
                + " push_to_result_ms="
                + off.roundTripMs()
                + " push_to_event_ms="
                + Duration.ofNanos(System.nanoTime() - pushed).toMillis()
                + " (shared laptop: INVALID reference)");

        Instant offAt = Instant.now();
        int heartbeatsAtOff = collector.heartbeats().size();
        RemoteConfigRuns.get(app, "/api/orders/2", 4);
        HttpResult same = app.get("/api/orders/1");
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.body())
            .as("the app answers as with the kill switch off")
            .isEqualTo(reference.body());
        assertThat(
                RemoteConfigRuns.await(
                    () ->
                        collector.heartbeats().size() >= heartbeatsAtOff + 3
                            && collector.batchesAfter(offAt).stream()
                                .anyMatch(b -> b.kind() == BatchKind.METRICS),
                    Duration.ofSeconds(20)))
            .as("heartbeats and metrics continue with the kill switch on")
            .isTrue();
        Thread.sleep(2_000L);
        assertThat(RemoteConfigRuns.transactionsAt(collector, "/api/orders/2"))
            .as("no transaction while the kill switch is on")
            .isZero();
        assertThat(RemoteConfigRuns.transactionsAt(collector, "/api/orders/1"))
            .as("only the transaction from before the kill switch")
            .isEqualTo(1);
        SelfDiagnostics diagnostics = RemoteConfigRuns.selfDiagnostics(app, collector);
        assertThat(RemoteConfigRuns.effectiveValue(diagnostics, "enabled")).isEqualTo("false");
        assertThat(RemoteConfigRuns.effectiveSource(diagnostics, "enabled")).isEqualTo("remote");

        ConfigApplyOutcome on = RemoteConfigRuns.push(collector, Map.of("enabled", "true"));
        assertThat(on.appliedKeys()).containsExactly("enabled");
        assertThat(collector.awaitAgentEvents("KILL_SWITCH_OFF", 1, Duration.ofSeconds(10)))
            .hasSize(1);
        RemoteConfigRuns.get(app, "/api/orders/3", 3);
        assertThat(
                RemoteConfigRuns.awaitTransactions(
                    collector, "/api/orders/3", 3, Duration.ofSeconds(30)))
            .as("collection resumes after enabled=true")
            .isTrue();
        assertThat(collector.sessions()).as("one session throughout").hasSize(1);
        // The agent log is written asynchronously.
        RemoteConfigRuns.await(
            () -> app.agentLog().contains("kill switch off"), Duration.ofSeconds(10));
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
        assertThat(app.agentLog()).contains("kill switch on").contains("kill switch off");
      } finally {
        app.close();
        RemoteConfigRuns.keepEvidence("killswitch-push-" + combo.jdk(), app);
      }
    }
  }

  @Test
  @Tag("req:DSN-02-17")
  @DisplayName(
      "T-AG-043 C-231 (4) ServerHello.config enabled=false: the agent starts with the kill switch"
          + " on; a new session without the key turns it off again, a later one with it on again")
  void helloConfig() throws Exception {
    RemoteConfigRuns.Combo combo = combo();
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).config(Map.of("enabled", "false")).start()) {
      RunningApp app =
          RemoteConfigRuns.shop(work.resolve("app"), combo, collector).start(Duration.ofMinutes(4));
      try {
        RemoteConfigRuns.awaitEstablished(app, collector);
        assertThat(collector.awaitAgentEvents("KILL_SWITCH_ON", 1, Duration.ofSeconds(15)))
            .as("kill switch on from the first hello")
            .hasSize(1);
        RemoteConfigRuns.get(app, "/api/orders/4", 3);
        Thread.sleep(3_000L);
        assertThat(RemoteConfigRuns.transactionsAt(collector, "/api/orders/4"))
            .as("no transaction with the hello's enabled=false")
            .isZero();

        // A new session whose hello no longer carries the key: back to the local value (true).
        collector.helloConfig(Map.of());
        collector.faults().closeStream();
        assertThat(collector.awaitSessions(2, Duration.ofSeconds(60)))
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(collector.awaitAgentEvents("KILL_SWITCH_OFF", 1, Duration.ofSeconds(15)))
            .hasSize(1);
        RemoteConfigRuns.get(app, "/api/orders/5", 3);
        assertThat(
                RemoteConfigRuns.awaitTransactions(
                    collector, "/api/orders/5", 3, Duration.ofSeconds(30)))
            .as("collection with the local enabled=true")
            .isTrue();

        // And a reconnect whose hello carries enabled=false again.
        int sessions = collector.sessions().size();
        collector.helloConfig(Map.of("enabled", "false"));
        collector.faults().closeStream();
        assertThat(collector.awaitSessions(sessions + 1, Duration.ofSeconds(60)))
            .hasSizeGreaterThan(sessions);
        assertThat(collector.awaitAgentEvents("KILL_SWITCH_ON", 2, Duration.ofSeconds(15)))
            .hasSize(2);
        RemoteConfigRuns.get(app, "/api/orders/6", 3);
        Thread.sleep(3_000L);
        assertThat(RemoteConfigRuns.transactionsAt(collector, "/api/orders/6")).isZero();
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
      } finally {
        app.close();
        RemoteConfigRuns.keepEvidence("killswitch-hello-" + combo.jdk(), app);
      }
    }
  }
}
