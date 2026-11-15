/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.ByeInfo;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-067, F-A26, 11 §6 "종료", 10 §10.2: at a normal exit the agent sends {@code AgentBye{SHUTDOWN,
 * last seqs}}, then the batches still queued within 1 s, from the shutdown hook. The trivial app
 * runs to its end ({@code runToExit}) because Windows stops processes without shutdown hooks. Runs
 * on every JDK of {@code -Pagent.attach.jdks}.
 */
@Tag("req:F-A26")
@Tag("req:DSN-02-11")
class ShutdownTransportTest {
  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  @TestFactory
  Stream<DynamicTest> shutdownOnEveryJdk() {
    return TransportRuns.attachJdks().stream()
        .flatMap(
            jdk ->
                Stream.of(
                    DynamicTest.dynamicTest(
                        "T-AG-067 JDK "
                            + jdk
                            + ": AgentBye reaches the collector, then the queued batches within 1 s",
                        () -> byeThenFlush(jdk)),
                    DynamicTest.dynamicTest(
                        "T-AG-067 JDK "
                            + jdk
                            + ": AgentBye carries the last seqs of what was sent before it",
                        () -> byeCarriesSeqs(jdk))));
  }

  /**
   * N-05 and 11 §1: no collector at all: the application runs and exits as usual, the agent says so
   * once, nothing on the console.
   */
  @Test
  @Tag("req:N-05")
  @DisplayName(
      "T-AG-067 no collector reachable: exit code 0, the shutdown hook does not wait for a session,"
          + " one connection WARN, clean console")
  void noCollector() throws Exception {
    int port;
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Path dir = work.resolve("no-collector");
    ExitedApp run =
        TransportRuns.runToExit(
            AppLauncher.of(TransportRuns.trivialApp(), Jdk.of(TransportRuns.mainJdk()))
                .withAgent(
                    TransportRuns.probeJar(),
                    Map.of(
                        "test.exporter", "grpc",
                        "server.address", "127.0.0.1:" + port,
                        "server.tls", "false"))
                .args(dir.resolve("out").resolve("results.properties").toString(), "6000")
                .workDir(dir));
    assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    assertThat(run.wallTime())
        .as("6 s of the app plus start and exit: the hook does not wait for a session")
        .isLessThan(Duration.ofSeconds(30));
    AgentLogAssert.assertThatAgentLog(run.agentLog())
        .hasWarningCount(TransportRuns.CONNECT_FAILED, 1)
        .hasNoErrors();
    assertThat(run.stdout().strip()).isEqualTo("trivial-app done");
    assertThat(run.stderrLines())
        .as("only the JVM's CDS notice")
        .allMatch(line -> line.contains("Sharing is only supported"));
  }

  void byeThenFlush(int jdk) throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      Path dir = work.resolve("flush-" + jdk);
      // The interval outlasts the run: the trivial app's transactions wait for the shutdown flush.
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(dir, jdk, collector, TransportRuns.STAY_ALIVE_MILLIS)
                  .agentConfig("sender.flush-interval-ms", "60000")
                  .agentConfig("sampling.profile.normal-rate", "1.0"));

      assertThat(collector.sessions()).as(run.agentLog()).isNotEmpty();
      List<ByeInfo> byes = collector.byes();
      assertThat(byes).as(run.agentLog()).hasSize(1);
      ByeInfo bye = byes.get(0);
      assertThat(bye.reason()).isEqualTo("SHUTDOWN");
      assertThat(bye.lastTxSeq()).as("nothing was sent before the bye").isZero();

      List<ReceivedBatch> after = collector.batchesAfter(bye.receivedAt());
      assertThat(after).as("batches after the bye; log:\n%s", run.agentLog()).isNotEmpty();
      assertThat(after)
          .as("the flush answers are acknowledged (the session stays valid, 11 §6)")
          .allSatisfy(batch -> assertThat(batch.acked()).isTrue());
      assertThat(collector.batches(BatchKind.TRANSACTIONS))
          .as("every batch arrived after the bye")
          .allSatisfy(batch -> assertThat(batch.receivedAt()).isAfterOrEqualTo(bye.receivedAt()));
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).containsExactly(1L);
      assertThat(collector.collected().transactions())
          .as("the trivial app's three handled requests")
          .hasSize(3);

      Optional<Duration> flush = collector.flushAfterBye(bye);
      assertThat(flush).isPresent();
      assertThat(flush.get()).isLessThanOrEqualTo(Duration.ofSeconds(1));

      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasNoErrors()
          .contains("Argos agent stopped: 3 transaction(s) exported");
    }
  }

  void byeCarriesSeqs(int jdk) throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      Path dir = work.resolve("seqs-" + jdk);
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(dir, jdk, collector, TransportRuns.STAY_ALIVE_MILLIS)
                  .agentConfig("sampling.profile.normal-rate", "1.0"));

      List<ByeInfo> byes = collector.byes();
      assertThat(byes).as(run.agentLog()).hasSize(1);
      assertThat(byes.get(0).lastTxSeq())
          .isEqualTo(collector.seqs(BatchKind.TRANSACTIONS).stream().max(Long::compare).orElse(0L))
          .isPositive();
      assertThat(byes.get(0).lastProfileSeq())
          .isEqualTo(collector.seqs(BatchKind.PROFILES).stream().max(Long::compare).orElse(0L));
      assertThat(collector.flushAfterBye(byes.get(0)))
          .as("the queues were empty: nothing to flush")
          .satisfiesAnyOf(
              value -> assertThat(value).isEmpty(),
              value -> assertThat(value.get()).isLessThanOrEqualTo(Duration.ofSeconds(1)));
    }
  }
}
