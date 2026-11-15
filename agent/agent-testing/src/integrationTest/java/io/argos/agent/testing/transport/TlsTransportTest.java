/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ScriptedAgent;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.proto.v1.AgentHello;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-SEC-07a and T-SEC-08c, the client side of T-SEC-07 and T-SEC-08 (SEC-07, SEC-08, 16 §3.4): the
 * certificate chain and the host name are always verified; {@code insecure-skip-verify} connects
 * with one WARN per connect; {@code server.tls: false} connects only when the collector allows it,
 * with one WARN per connect, and a refusal is not retried. The server side (cipher suites,
 * testssl.sh) is WO-21.
 */
@Tag("req:SEC-07")
@Tag("req:SEC-08")
class TlsTransportTest {
  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  @Test
  @DisplayName(
      "T-SEC-08c host name mismatch: the connection fails with a WARN, no session, no data")
  void wrongHost() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      collector.faults().wrongHostCertificate();
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("wrong-host"),
                  TransportRuns.mainJdk(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).isEmpty();
      assertThat(collector.batches()).isEmpty();
      assertThat(AgentLogAssert.linesAt(run.agentLog(), "WARN"))
          .as(run.agentLog())
          .anyMatch(line -> line.message().contains(TransportRuns.CONNECT_FAILED));
      ScriptedAgent.assertNoToken("the agent log", run.agentLog(), collector.token());
      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasNoErrors();
      assertThat(run.stdout()).doesNotContain("collector");
    }
  }

  @Test
  @DisplayName(
      "T-SEC-08c a CA the agent does not trust fails the same way: chain verification stays on")
  void unknownCa() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        FakeCollector other =
            FakeCollector.builder().tls(true).tlsDirectory(work.resolve("other")).start()) {
      Path dir = work.resolve("unknown-ca");
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                      dir, TransportRuns.mainJdk(), collector, TransportRuns.STAY_ALIVE_MILLIS)
                  .agentConfig("server.tls.ca-file", other.trustPem().toString()));
      assertThat(collector.sessions()).as(run.agentLog()).isEmpty();
      assertThat(AgentLogAssert.linesAt(run.agentLog(), "WARN"))
          .anyMatch(line -> line.message().contains(TransportRuns.CONNECT_FAILED));
    }
  }

  @Test
  @DisplayName(
      "T-SEC-08c insecure-skip-verify connects to a wrong-host certificate, with one WARN per"
          + " connect including reconnects")
  void skipVerify() throws Exception {
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).tlsDirectory(work).heartbeatIntervalS(1).start()) {
      collector.faults().wrongHostCertificate();
      Path dir = work.resolve("skip-verify");
      AppLauncher launcher =
          TransportRuns.trivial(
                  dir, TransportRuns.mainJdk(), collector, TransportRuns.STAY_ALIVE_MILLIS)
              .agentConfig("server.tls.insecure-skip-verify", "true");
      CompletableFuture<ExitedApp> run =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return TransportRuns.runToExit(launcher);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      // Connection loss twice: two more connects after the first (back-off 1 s, 2 s).
      collector.faults().closeStream();
      assertThat(collector.awaitSessions(2, Duration.ofSeconds(10))).hasSizeGreaterThanOrEqualTo(2);
      Thread.sleep(200);
      collector.faults().closeStream();
      assertThat(collector.awaitSessions(3, Duration.ofSeconds(10))).hasSizeGreaterThanOrEqualTo(3);

      ExitedApp exited = run.get();
      int connects = collector.sessions().size();
      assertThat(connects).isGreaterThanOrEqualTo(3);
      AgentLogAssert.assertThatAgentLog(exited.agentLog())
          .hasWarningCount(TransportRuns.SKIP_VERIFY_WARN, connects)
          .hasNoErrors();
      assertThat(exited.stderr()).doesNotContain("insecure-skip-verify");
    }
  }

  @Test
  @DisplayName(
      "T-SEC-07a plaintext: the collector refuses with PLAINTEXT_NOT_ALLOWED: one refusal, no retry,"
          + " one ERROR; the app is unaffected")
  void plaintextRefused() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(false).start()) {
      collector.faults().plaintextRejected();
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("plain-refused"),
                  TransportRuns.mainJdk(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      SessionInfo session = collector.sessions().get(0);
      assertThat(session.accepted()).isFalse();
      assertThat(session.rejectReason()).isEqualTo("PLAINTEXT_NOT_ALLOWED");
      assertThat(session.plaintext()).isTrue();
      assertThat(((AgentHello) session.hello()).getPlaintext())
          .as("AgentHello.plaintext, 16 §3.4")
          .isTrue();
      assertThat(collector.batches()).isEmpty();
      List<AgentLogAssert.LogLine> errors = AgentLogAssert.linesAt(run.agentLog(), "ERROR");
      assertThat(errors).hasSize(1);
      assertThat(errors.get(0).message()).contains("PLAINTEXT_NOT_ALLOWED");
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasWarningCount(TransportRuns.PLAINTEXT_WARN, 1);
      assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    }
  }

  @Test
  @DisplayName(
      "T-SEC-07a plaintext allowed: batches flow, one WARN per connect, AgentHello.plaintext true")
  void plaintextAllowed() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(false).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("plain"),
                  TransportRuns.mainJdk(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      assertThat(collector.sessions().get(0).accepted()).isTrue();
      assertThat(((AgentHello) collector.sessions().get(0).hello()).getPlaintext()).isTrue();
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).isNotEmpty();
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasWarningCount(TransportRuns.PLAINTEXT_WARN, 1)
          .hasNoErrors();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-08c default TLS: no security WARN, AgentHello.plaintext false, the session names TLS")
  void verifiedTls() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("tls"),
                  TransportRuns.mainJdk(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      AgentHello tlsHello = (AgentHello) collector.sessions().get(0).hello();
      assertThat(tlsHello.hasPlaintext())
          .as("AgentHello.plaintext is reported explicitly (optional field, C-101)")
          .isTrue();
      assertThat(tlsHello.getPlaintext()).isFalse();
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasWarningCount(TransportRuns.SKIP_VERIFY_WARN, 0)
          .hasWarningCount(TransportRuns.PLAINTEXT_WARN, 0)
          .hasNoErrors();
      assertThat(run.agentLog()).containsPattern("collector session established .*TLSv1\\.[23]");
    }
  }
}
