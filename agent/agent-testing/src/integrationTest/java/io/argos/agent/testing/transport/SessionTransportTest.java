/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.ConfigApplyOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.UserIdMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session and the batch reactions of 11 §3, §5, §6, §7, §8, §10 end to end: the real agent
 * against the FakeCollector. The unit tests of agent-core ({@code SessionClientTest}, {@code
 * SenderTest}) cover each reaction with fakes; these runs show them on the shaded stack.
 */
@Tag("req:DSN-02-11")
class SessionTransportTest {
  private static final String PATH = "/api/orders/1";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private static long nodeOf(long txId) {
    return (txId >>> 11) & 0xFFFL;
  }

  private AppLauncher app(FakeCollector collector, String run) {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    return AppLauncher.of(
            SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
        .withAgent(TransportRuns.releaseJar())
        .collector(collector)
        .workDir(work.resolve(run));
  }

  @Test
  @DisplayName(
      "11 §5: AgentHello from the configuration and the runtime; tx_id node = instance_id mod 4096;"
          + " dictionary entries reach the collector; DictionaryRequest is answered")
  void helloNodeDictionary() throws Exception {
    int instance = 4096 + 77;
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).firstInstanceId(instance).start();
        RunningApp app =
            app(collector, "hello")
                .agentConfig("app.version", "9.8.7")
                .start(Duration.ofMinutes(4))) {
      List<SessionInfo> sessions = collector.awaitSessions(1, Duration.ofSeconds(30));
      assertThat(sessions).hasSize(1);
      AgentHello hello = (AgentHello) sessions.get(0).hello();
      assertThat(hello.getProtocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
      assertThat(hello.getAppName()).isEqualTo(app.app().name());
      assertThat(hello.getEnv()).isEqualTo(AppLauncher.HARNESS_ENV);
      assertThat(hello.getAppVersion()).isEqualTo("9.8.7");
      assertThat(hello.getAgentVersion()).isEqualTo("0.0.0-SNAPSHOT");
      assertThat(hello.getPid()).isEqualTo((int) app.pid());
      assertThat(hello.getInstanceName()).endsWith(":" + app.pid());
      assertThat(hello.getJvmVersion()).isNotEmpty();
      assertThat(hello.getOs()).isNotEmpty();
      assertThat(hello.getArch()).isNotEmpty();
      assertThat(hello.getCgroup()).isEqualTo("none");
      assertThat(hello.getConfigHash()).hasSize(16);
      assertThat(hello.getAgentEpoch()).isNotZero();
      assertThat(hello.getProcessStartMs()).isPositive();
      assertThat(hello.getRemoteConfigAllowList())
          .containsExactly(
              "sampling.**",
              "instrumentation.disabled",
              "instrumentation.ignore-paths",
              "log.level",
              "enabled");
      assertThat(hello.getInstrumentationModulesList()).contains("servlet", "jdbc");
      assertThat(hello.getIpMode()).isEqualTo(IpMode.IP_MASKED);
      assertThat(hello.getUserIdMode()).isEqualTo(UserIdMode.USER_ID_OFF);
      assertThat(hello.getQueryStringMode()).isEqualTo(QueryStringMode.QS_OFF);
      assertThat(hello.getSqlParamMode()).isEqualTo(SqlParamMode.SQL_PARAM_MASKED);

      assertThat(app.get(PATH).status()).isEqualTo(200);
      List<ReceivedBatch> batches =
          collector.awaitBatches(BatchKind.TRANSACTIONS, 1, Duration.ofSeconds(20));
      TransactionBatch batch = (TransactionBatch) batches.get(0).body();
      assertThat(batch.getHeader().getAgentEpoch()).isEqualTo(hello.getAgentEpoch());
      assertThat(batch.getHeader().getInstanceId()).isEqualTo(instance);
      assertThat(
              TransportRuns.await(
                  () -> collector.dictionary().containsValue("GET /api/orders/{id}"),
                  Duration.ofSeconds(5)))
          .as("the service name was pre-sent (11 §7); %s", collector.dictionary())
          .isTrue();

      // A transaction after the hello carries the server's node (C-91, 10 §9.5).
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                          .flatMap(b -> ((TransactionBatch) b.body()).getRecordsList().stream())
                          .anyMatch(record -> nodeOf(record.getTxId()) == instance % 4096),
                  Duration.ofSeconds(20)))
          .as("tx_id node %d", instance % 4096)
          .isTrue();

      long service =
          collector.dictionary().entrySet().stream()
              .filter(entry -> entry.getValue().equals("GET /api/orders/{id}"))
              .findFirst()
              .orElseThrow()
              .getKey();
      int entries = collector.dictionaryEntries().size();
      collector.requestDictionary("SERVICE", service, 123_456_789L);
      assertThat(
              TransportRuns.await(
                  () -> collector.dictionaryEntries().size() == entries + 1,
                  Duration.ofSeconds(10)))
          .as("the known id is answered, the unknown one skipped silently (11 §7)")
          .isTrue();
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors().hasNoWarnings();
    }
  }

  @Test
  @Tag("req:SEC-23")
  @Tag("req:SEC-24")
  @DisplayName(
      "11 §8, C-103, C-118: a ConfigPush is answered NOT_ALLOWED for every key and a Command"
          + " REJECTED; nothing is applied or run; the session stays up")
  void remoteCommandsAreRejected() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            app(collector, "remote")
                .agentConfig("sampling.profile.normal-rate", "1.0")
                .start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      Thread.sleep(500);
      ConfigApplyOutcome outcome =
          collector
              .pushConfig(
                  Map.of(
                      "sampling.profile.normal-rate", "0",
                      "capture.user-id.mode", "plain",
                      "server.address", "attacker.example:1"))
              .get(20, TimeUnit.SECONDS);
      assertThat(outcome.appliedKeys()).isEmpty();
      assertThat(outcome.rejected())
          .containsOnlyKeys(
              "sampling.profile.normal-rate", "capture.user-id.mode", "server.address")
          .allSatisfy((key, code) -> assertThat(code).isEqualTo("NOT_ALLOWED"));

      CommandOutcome dump = collector.command("THREAD_DUMP", Map.of()).get(20, TimeUnit.SECONDS);
      assertThat(dump.status()).isEqualTo("REJECTED");
      assertThat(dump.payload().isEmpty()).isTrue();
      assertThat(dump.chunks()).isEqualTo(1);

      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () -> !collector.batches(BatchKind.PROFILES).isEmpty(), Duration.ofSeconds(20)))
          .as("sampling is unchanged: the push was not applied")
          .isTrue();
      assertThat(collector.sessions()).hasSize(1);
      assertThat(app.agentLog())
          .doesNotContain("attacker.example")
          .contains("collector pushed a remote configuration change");
    }
  }

  /**
   * 11 §3, §10: a data call answered {@code FAILED_PRECONDITION} with {@code SESSION_REQUIRED} (the
   * collector's {@code failNext} keeps that description) makes the agent open a new session and
   * resend the same batch with the same epoch and seq. {@code SessionFlowControlIntegrationTest} of
   * agent-core shows the same against a server that really forgets the session while its stream
   * stays open.
   */
  @Test
  @DisplayName(
      "11 §3, §10 SESSION_REQUIRED end to end: a new hello, then the refused batch again with the"
          + " same epoch and seq, acknowledged")
  void sessionRequiredResendsSameSeq() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app = app(collector, "session-required").start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      int sessions = collector.sessions().size();
      collector
          .faults()
          .failNext(1, io.grpc.Status.FAILED_PRECONDITION.withDescription("SESSION_REQUIRED"));
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(TransportRuns.await(() -> refusedAndResent(collector), Duration.ofSeconds(30)))
          .as("the resend; %s; log:%n%s", collector.counts(), app.agentLog())
          .isTrue();
      assertThat(collector.sessions()).as("a new hello").hasSizeGreaterThan(sessions);
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
    }
  }

  /** True when a batch refused with the injected status was later acknowledged under its key. */
  private static boolean refusedAndResent(FakeCollector collector) {
    List<ReceivedBatch> batches = collector.batches();
    for (ReceivedBatch refused : batches) {
      if (!refused.outcome().equals("FAILED " + io.grpc.Status.Code.FAILED_PRECONDITION)) {
        continue;
      }
      for (ReceivedBatch resent : batches) {
        if (resent.acked()
            && resent.kind() == refused.kind()
            && resent.agentEpoch() == refused.agentEpoch()
            && resent.seq() == refused.seq()) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  @DisplayName(
      "11 §3, §6 a server restart is a reconnect, seq goes on with the same epoch; 11 §5, §10"
          + " SessionClose SERVER_SHUTDOWN waits its retry_after_s (REPLACED is never retried: see"
          + " ConnectionTransportTest)")
  void serverRestartKeepsSeqGoing() throws Exception {
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).firstInstanceId(11).start();
        RunningApp app = app(collector, "restart").start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      assertThat(app.get(PATH).status()).isEqualTo(200);
      List<ReceivedBatch> before =
          collector.awaitBatches(BatchKind.TRANSACTIONS, 1, Duration.ofSeconds(20));
      long lastSeq = before.get(before.size() - 1).seq();

      collector.faults().serverRestart(21);
      assertThat(collector.awaitSessions(2, Duration.ofSeconds(20)))
          .as("a new session; log:\n%s", app.agentLog())
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                          .anyMatch(batch -> batch.acked() && batch.instanceId() == 21),
                  Duration.ofSeconds(20)))
          .as("a batch of the new instance; %s", collector.counts())
          .isTrue();
      assertThat(
              collector.batches(BatchKind.TRANSACTIONS).stream()
                  .filter(batch -> batch.instanceId() == 21)
                  .mapToLong(ReceivedBatch::seq)
                  .min()
                  .orElse(0))
          .as("11 §6: seq does not start over with a new session")
          .isGreaterThan(lastSeq);
      assertThat(
              collector.batches(BatchKind.TRANSACTIONS).stream()
                  .map(ReceivedBatch::agentEpoch)
                  .distinct()
                  .count())
          .as("agent_epoch stays for the process")
          .isEqualTo(1);

      long start = System.nanoTime();
      int sessions = collector.sessions().size();
      collector.faults().sessionClose("SERVER_SHUTDOWN", Duration.ofSeconds(1));
      assertThat(collector.awaitSessions(sessions + 1, Duration.ofSeconds(15)))
          .hasSizeGreaterThan(sessions);
      assertThat(Duration.ofNanos(System.nanoTime() - start))
          .as("at least retry_after_s")
          .isGreaterThanOrEqualTo(Duration.ofMillis(900));
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors().hasNoWarnings();
    }
  }

  @Test
  @DisplayName(
      "11 §6, §10 failures end to end: UNAVAILABLE retried with the same seq; ACK_PARTIAL_DROP"
          + " counted and never resent; a Throttle lowers the profile rate; the collector's idle"
          + " close (C-19) reconnects")
  void failuresAndThrottle() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            app(collector, "failures")
                .agentConfig("sampling.profile.normal-rate", "1.0")
                .start(Duration.ofMinutes(4))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();

      collector.faults().failNext(1, io.grpc.Status.UNAVAILABLE);
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                          .anyMatch(ReceivedBatch::acked),
                  Duration.ofSeconds(20)))
          .as("the retry; %s", collector.counts())
          .isTrue();
      long firstSeq = collector.batches(BatchKind.TRANSACTIONS).get(0).seq();
      assertThat(collector.batches(BatchKind.TRANSACTIONS))
          .anyMatch(batch -> batch.acked() && batch.seq() == firstSeq);

      collector.faults().partialDrop(1);
      int before = collector.batches(BatchKind.TRANSACTIONS).size();
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () -> collector.batches(BatchKind.TRANSACTIONS).size() > before,
                  Duration.ofSeconds(20)))
          .isTrue();
      collector.faults().clear();
      Thread.sleep(1_000);
      assertThat(collector.batches(BatchKind.TRANSACTIONS))
          .as("nothing is resent after a partial drop")
          .hasSize(before + 1);
      assertThat(
              TransportRuns.await(
                  () ->
                      app.selfDiagnostics()
                              .counters()
                              .getOrDefault(
                                  "argos_agent_server_dropped_items_total{kind=\"transactions\"}",
                                  0L)
                          == 1,
                  Duration.ofSeconds(10)))
          .as("server drop counted; %s", app.selfDiagnostics().counters())
          .isTrue();

      collector.sendThrottle(0.0f, Duration.ofMillis(10));
      Thread.sleep(500);
      int profiles = collector.batches(BatchKind.PROFILES).size();
      app.load(20, Duration.ofSeconds(2), PATH);
      Thread.sleep(1_000);
      assertThat(collector.batches(BatchKind.PROFILES))
          .as("11 §4.2 Throttle.profile_normal_rate: normal requests are no longer profiled")
          .hasSize(profiles);

      collector.faults().sessionClose("IDLE_TIMEOUT", Duration.ZERO);
      assertThat(collector.awaitSessions(2, Duration.ofSeconds(15)))
          .as("C-19: a normal reconnect")
          .hasSizeGreaterThanOrEqualTo(2);
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors().hasNoWarnings();
    }
  }

  @Test
  @DisplayName(
      "11 §5 APP_NOT_REGISTERED with retry_after_s: the agent logs one ERROR, comes back after that"
          + " time and is accepted")
  void rejectedThenAccepted() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      collector.faults().rejectHello("APP_NOT_REGISTERED", Duration.ofSeconds(2));
      java.util.concurrent.CompletableFuture<ExitedApp> run =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return TransportRuns.runToExit(
                      TransportRuns.trivial(
                          work.resolve("reject"),
                          TransportRuns.mainJdk(),
                          collector,
                          TransportRuns.STAY_ALIVE_MILLIS));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      collector.faults().clear();
      assertThat(collector.awaitSessions(2, Duration.ofSeconds(15))).hasSizeGreaterThanOrEqualTo(2);
      ExitedApp exited = run.get();
      assertThat(collector.sessions().get(0).accepted()).isFalse();
      assertThat(collector.sessions().get(1).accepted()).isTrue();
      assertThat(AgentLogAssert.linesAt(exited.agentLog(), "ERROR"))
          .as(exited.agentLog())
          .hasSize(1)
          .allSatisfy(line -> assertThat(line.message()).contains("APP_NOT_REGISTERED"));
    }
  }
}
