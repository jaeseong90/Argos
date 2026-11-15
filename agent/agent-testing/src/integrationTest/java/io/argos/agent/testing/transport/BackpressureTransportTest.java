/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.LoadResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-071 (DSN-02-11, DSN-10-10, 11 §6): the first jar app of {@code -Pagent.e2e} under load. With
 * a slow collector at most 4 unary calls are concurrent and {@code seq} strictly increases per
 * kind; with a collector that does not answer, the agent drops and counts, its heap stays within
 * the queue caps and the application does not notice (N-05). The stream's {@code isReady} back
 * pressure is proven on the real grpc-netty stack in agent-core's {@code
 * SessionFlowControlIntegrationTest} (a server that stops reading needs a raw gRPC server, not the
 * FakeCollector) and in {@code SessionClientTest}. The 8u252 TLS part is {@code
 * OldJreTransportTest}.
 */
@Tag("req:DSN-02-11")
@Tag("req:DSN-10-10")
class BackpressureTransportTest {
  private static final String PATH = "/api/orders/1";
  private static final String TRANSACTION_DROPS =
      "argos_agent_dropped_total{queue=\"transaction\"}";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private AppLauncher launcher(FakeCollector collector, String run) {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    return AppLauncher.of(
            SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
        .withAgent(TransportRuns.releaseJar())
        .collector(collector)
        .workDir(work.resolve(run));
  }

  /**
   * The agent's seqs of a kind strictly increase in the order it sent them. The collector's arrival
   * order can differ: gRPC runs each call on its own executor task, and 11 §6 says that a server
   * assumes no order ("서버는 순서를 가정하지 않는다"). So the check is on the agent's side of the data: sorted
   * by seq the batches are 1..n without a gap or repeat, and {@code sent_at_ms} never falls.
   */
  private static void assertStrictlyIncreasing(List<ReceivedBatch> batches, String kind) {
    List<ReceivedBatch> sorted =
        batches.stream().sorted(Comparator.comparingLong(ReceivedBatch::seq)).toList();
    long last = 0;
    long lastSent = 0;
    for (ReceivedBatch batch : sorted) {
      assertThat(batch.seq()).as("%s seq after %d", kind, last).isEqualTo(last + 1);
      assertThat(batch.sentAtMs())
          .as("%s sent_at_ms of seq %d", kind, batch.seq())
          .isGreaterThanOrEqualTo(lastSent);
      last = batch.seq();
      lastSent = batch.sentAtMs();
    }
  }

  @Test
  @DisplayName(
      "T-AG-071 latency 200 ms under load: the collector never sees more than 4 concurrent unary"
          + " calls and reaches that cap; seq strictly increases per kind; nothing is dropped")
  void inFlightCap() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            launcher(collector, "in-flight")
                .agentConfig("sender.batch-size", "5")
                .agentConfig("sampling.profile.normal-rate", "1.0")
                .start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      collector.faults().latency(Duration.ofMillis(200));
      collector.resetMaxConcurrentUnaryCalls();

      LoadResult load = app.load(200, Duration.ofSeconds(10), PATH);
      assertThat(load.failures()).isZero();
      Thread.sleep(1_000);

      assertThat(collector.maxConcurrentUnaryCalls())
          .as("high-water mark of concurrent data calls; %s", collector.counts())
          .isEqualTo(4);
      assertStrictlyIncreasing(collector.batches(BatchKind.TRANSACTIONS), "transactions");
      assertStrictlyIncreasing(collector.batches(BatchKind.PROFILES), "profiles");
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).hasSizeGreaterThan(5);
      assertThat(collector.batches())
          .as("no retries: the latency stays below the deadline")
          .allSatisfy(batch -> assertThat(batch.acked()).isTrue());
      assertThat(app.selfDiagnostics().counter(TRANSACTION_DROPS)).isZero();
    }
  }

  @Test
  @Tag("req:N-05")
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-071 hang: drops are counted, the agent's heap stays within the queue caps, the app is"
          + " unaffected; after recovery batches flow again with the same epoch and higher seqs")
  void hangDropsAndBounds() throws Exception {
    int capacity = 200;
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            launcher(collector, "hang")
                .agentConfig("buffer.transaction.capacity", Integer.toString(capacity))
                .agentConfig("server.deadline-ms", "500")
                .start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      collector.faults().hang();
      LoadResult load = app.load(200, Duration.ofSeconds(8), PATH);
      assertThat(load.failures()).as("N-05: the application does not notice").isZero();
      assertThat(load.ok()).isEqualTo(load.scheduled());

      assertThat(
              TransportRuns.await(
                  () -> app.selfDiagnostics().counter(TRANSACTION_DROPS) > 0,
                  Duration.ofSeconds(20)))
          .as("drops counted: %s", app.selfDiagnostics())
          .isTrue();

      long instances =
          app.jcmd().classHistogram().instancesOf("io.argos.agent.core.trace.Transaction");
      assertThat(instances)
          .as("transactions held on the heap, queue cap %d + one in the sender", capacity)
          .isLessThanOrEqualTo(capacity + 1L);
      assertThat(app.jcmd().classHistogram().bytesOf("io.argos.agent."))
          .as("10 §14 heap budget (N-02)")
          .isLessThanOrEqualTo(64L * 1024 * 1024);

      collector.faults().clear();
      int before = collector.seqs(BatchKind.TRANSACTIONS).size();
      app.load(50, Duration.ofSeconds(3), PATH);
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                          .skip(before)
                          .anyMatch(batch -> batch.acked()),
                  Duration.ofSeconds(30)))
          .as("batches are acknowledged after recovery; log:\n%s", app.agentLog())
          .isTrue();
      assertThat(
              collector.batches(BatchKind.TRANSACTIONS).stream()
                  .map(batch -> batch.agentEpoch())
                  .distinct()
                  .count())
          .as("agent_epoch stays for the process")
          .isEqualTo(1);
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
    }
  }
}
