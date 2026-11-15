/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.ScriptedAgent;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ContainerApp;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-SEC-06 on the agent (SEC-06, 11 §11, 16 §2.6, ADR-0010 C-94, C-122): the token reaches the
 * collector in the call metadata only; a plaintext token in the yml gives one WARN; the agent log,
 * the console, the self-diagnostics, the hello and every batch body never hold the value ({@code
 * argos_****} is the only form). The file-permission WARN needs POSIX permissions: on Windows the
 * check does not exist (documented in docs/agent-config.md); the tests run it in container mode
 * ({@code -Pagent.container=true}) with the token file copied inside the container and set to 0644
 * or 0600 ({@code AppLauncher.tokenFileMode}), so the result does not depend on the Docker host.
 */
@Tag("req:SEC-06")
class TokenTransportTest {
  private static final String PLAINTEXT_WARNING =
      "token is stored in plaintext in argos-agent.yml; prefer token-file or ARGOS_TOKEN";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  // ScriptedAgent.assertNoToken fails without printing the token: an AssertJ doesNotContain(token)
  // failure message would put the value into the test output (11 §11, SEC-06).

  private static void assertNotInMessages(FakeCollector collector, String token) {
    for (SessionInfo session : collector.sessions()) {
      ScriptedAgent.assertNoToken("a session", session.toString(), token);
      ScriptedAgent.assertNoToken("a hello", session.hello().toString(), token);
      ScriptedAgent.assertNoToken(
          "the hello bytes",
          new String(session.hello().toByteArray(), StandardCharsets.ISO_8859_1),
          token);
    }
    for (ReceivedBatch batch : collector.batches()) {
      ScriptedAgent.assertNoToken("a batch", batch.toString(), token);
      ScriptedAgent.assertNoToken(
          "a batch body",
          new String(batch.body().toByteArray(), StandardCharsets.ISO_8859_1),
          token);
    }
  }

  private static void assertNotInRun(ExitedApp run, String token) throws Exception {
    ScriptedAgent.assertNoToken("the agent log", run.agentLog(), token);
    ScriptedAgent.assertNoToken("stdout", run.stdout(), token);
    ScriptedAgent.assertNoToken("stderr", run.stderr(), token);
    ScriptedAgent.assertNoToken("the command line", String.join(" ", run.command()), token);
    Path diagnostics = run.out().resolve(AppLauncher.SELF_DIAGNOSTICS_FILE);
    ScriptedAgent.assertNoToken(
        "the self-diagnostics", Files.readString(diagnostics, StandardCharsets.UTF_8), token);
  }

  @Test
  @DisplayName(
      "T-SEC-06 token in the yml: one WARN, the collector authenticates it from the metadata, and"
          + " neither log, console, self-diagnostics, hello nor batch bodies hold it")
  void plaintextInYml() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      collector.faults().requireToken(collector.token());
      Path dir = work.resolve("yml");
      Files.createDirectories(dir);
      Path yml = dir.resolve("agent-with-token.yml");
      Files.writeString(
          yml, "server:\n  token: " + collector.token() + "\n", StandardCharsets.UTF_8);
      // No server.token-file here: the yml value is the token the agent uses (16 §2.6 order).
      AppLauncher launcher =
          AppLauncher.of(TransportRuns.trivialApp(), Jdk.of(TransportRuns.mainJdk()))
              .withAgent(
                  TransportRuns.probeJar(),
                  Map.of(
                      "test.exporter", "grpc",
                      "server.address", collector.address(),
                      "server.tls", "true",
                      "server.tls.ca-file", collector.trustPem().toString(),
                      "config", yml.toString()))
              .args(
                  dir.resolve("out").resolve("results.properties").toString(),
                  TransportRuns.STAY_ALIVE_MILLIS)
              .workDir(dir);
      ExitedApp run = TransportRuns.runToExit(launcher);

      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasWarningCount(PLAINTEXT_WARNING, 1);
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      SessionInfo session = collector.sessions().get(0);
      assertThat(session.accepted()).isTrue();
      assertThat(session.token().matches(collector.token()))
          .as("the token in the call metadata")
          .isTrue();
      assertThat(collector.batches()).as(run.agentLog()).isNotEmpty();
      assertThat(collector.batches())
          .allSatisfy(batch -> assertThat(batch.token().matches(collector.token())).isTrue());
      assertNotInRun(run, collector.token());
      assertNotInMessages(collector, collector.token());
    }
  }

  @Test
  @DisplayName(
      "T-SEC-06 token-file with log.level=DEBUG: no plaintext WARN, the start banner shows the mask,"
          + " the collector gets the value, and even the libraries' DEBUG lines never show it")
  void tokenFile() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      collector.faults().requireToken(collector.token());
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                      work.resolve("file"),
                      TransportRuns.mainJdk(),
                      collector,
                      TransportRuns.STAY_ALIVE_MILLIS)
                  .agentConfig("log.level", "DEBUG"));
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasWarningCount(PLAINTEXT_WARNING, 0)
          .contains("token=argos_****");
      assertThat(collector.sessions()).as(run.agentLog()).isNotEmpty();
      assertThat(collector.sessions().get(0).accepted()).isTrue();
      assertNotInRun(run, collector.token());
      assertNotInMessages(collector, collector.token());
    }
  }

  @Test
  @DisplayName(
      "T-SEC-06 a refused token: the rejection and the agent's reaction never show the value, only"
          + " one WARN (ADR-0010 C-102), no ERROR")
  void refusedToken() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      collector.faults().rejectAuth();
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("refused"),
                  TransportRuns.mainJdk(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      assertThat(collector.sessions().get(0).accepted()).isFalse();
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasWarningCount("collector rejected the agent token", 1)
          .hasNoErrors();
      assertNotInRun(run, collector.token());
      assertThat(run.selfDiagnostics().counter("argos_agent_session_rejected_total")).isEqualTo(1);
    }
  }

  /** The 16 §2.6 WARN of a token file that group or others can read (TokenFile, ST-06-3). */
  private static final String PERMISSION_WARNING = "can be read by group or others";

  @Test
  @DisplayName(
      "T-SEC-06 token-file permission WARN on POSIX (container mode, 16 §2.6): a token file with"
          + " mode 0644 gives the WARN once, and the agent still connects")
  void tokenFileReadableByOthers() throws Exception {
    String log = runWithTokenFileMode("0644");
    AgentLogAssert.assertThatAgentLog(log)
        .hasWarningCount(PERMISSION_WARNING, 1)
        .hasWarningCount(
            "server.token-file " + ContainerApp.TOKEN_FILE + " " + PERMISSION_WARNING, 1);
  }

  @Test
  @DisplayName(
      "T-SEC-06 token-file permission on POSIX (container mode, 16 §2.6): an owner-only token file"
          + " (0600) gives no WARN, and the agent connects")
  void tokenFileOwnerOnly() throws Exception {
    String log = runWithTokenFileMode("0600");
    AgentLogAssert.assertThatAgentLog(log).hasWarningCount(PERMISSION_WARNING, 0);
  }

  /**
   * Runs the first jar app of {@code -Pagent.e2e} in a container whose token file has {@code mode}
   * (the launcher copies it inside the container and chmods it, so the host's bind mount does not
   * decide the permissions), waits for the accepted session and returns the agent log, which never
   * holds the token.
   */
  private String runWithTokenFileMode(String mode) throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true (Docker, POSIX permissions); on Windows"
            + " docs/agent-config.md documents that the check does not exist");
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).bindAddress("0.0.0.0").start();
        RunningApp app =
            AppLauncher.of(
                    SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
                .withAgent(TransportRuns.releaseJar())
                .collector(collector)
                .container(true, "2", "1g")
                .tokenFileMode(mode)
                .workDir(work.resolve("posix-" + mode))
                .start(Duration.ofMinutes(4))) {
      assertThat(app.command())
          .as("the agent reads the in-container copy")
          .contains("-Dargos.server.token-file=" + ContainerApp.TOKEN_FILE);
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      assertThat(collector.sessions().get(0).accepted()).as("the session is established").isTrue();
      assertThat(collector.sessions().get(0).token().matches(collector.token()))
          .as("the token of the file reached the collector")
          .isTrue();
      String log = app.agentLog();
      ScriptedAgent.assertNoToken("the agent log", log, collector.token());
      return log;
    }
  }
}
