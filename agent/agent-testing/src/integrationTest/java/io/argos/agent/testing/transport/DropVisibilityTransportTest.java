/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.HeartbeatRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-ACC-06a, the agent side of T-ACC-06 (D-06, 11 §6, F-S10, ADR-0010 C-122 letter suffix): a
 * collector that hangs longer than {@code buffer.max-age-seconds} (lowered here) makes the agent
 * drop what it cannot send; the drop counter rises, and after recovery the heartbeat's {@code
 * dropped_total} shows it to the server. The collection status API is WO-22's part of T-ACC-06.
 */
@Tag("req:D-06")
class DropVisibilityTransportTest {
  private static final String DROPS = "argos_agent_dropped_total{queue=\"transaction\"}";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  @Test
  @DisplayName(
      "T-ACC-06a hang() longer than buffer.max-age-seconds: the drop counter rises (self-diagnostics),"
          + " and the heartbeat after recovery carries dropped_total")
  void dropsBecomeVisible() throws Exception {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).heartbeatIntervalS(1).start();
        RunningApp app =
            AppLauncher.of(
                    SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
                .withAgent(TransportRuns.releaseJar())
                .collector(collector)
                .agentConfig("buffer.max-age-seconds", "2")
                .agentConfig("server.deadline-ms", "500")
                .workDir(work.resolve("hang"))
                .start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      assertThat(collector.awaitHeartbeats(1, Duration.ofSeconds(15))).isNotEmpty();
      assertThat(app.get("/api/orders/1").status()).isEqualTo(200);

      collector.faults().hang();
      app.load(100, Duration.ofSeconds(5), "/api/orders/1");
      // Longer than the age bound: what waits in the queue is too old when a slot frees up.
      assertThat(
              TransportRuns.await(
                  () -> app.selfDiagnostics().counter(DROPS) > 0, Duration.ofSeconds(25)))
          .as("drop counter; %s; log:\n%s", app.selfDiagnostics(), app.agentLog())
          .isTrue();
      long dropped = app.selfDiagnostics().counter(DROPS);
      assertThat(
              app.selfDiagnostics()
                  .counter("argos_agent_batches_dropped_total{kind=\"transactions\"}"))
          .as("batches given up after their retries")
          .isPositive();

      collector.faults().clear();
      Instant recovered = Instant.now();
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.heartbeats().stream()
                          .anyMatch(
                              h ->
                                  h.receivedAt().isAfter(recovered) && h.droppedTotal() >= dropped),
                  Duration.ofSeconds(20)))
          .as("a heartbeat after recovery with dropped_total >= %d", dropped)
          .isTrue();
      List<HeartbeatRecord> heartbeats = collector.heartbeats();
      assertThat(heartbeats.get(0).droppedTotal()).as("nothing dropped at the start").isZero();
    }
  }
}
