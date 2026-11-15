/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.HeartbeatRecord;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.ScriptedAgent;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where and how often the agent connects (DSN-02-22, 10 §10.2, §10.3, §14, 11 §5, §6, §10, 12 §16),
 * seen from outside the agent with {@link TcpProbe}: no proxy of the application JVM is used, the
 * TCP connects follow the session's back-off, a host name that does not resolve logs once per
 * minute, a refusal for good releases the connection and {@code argos-io}, {@code SessionClose
 * REPLACED} is not retried, and {@code Ack.throttle} lowers the profile rate.
 */
@Tag("req:DSN-02-11")
class ConnectionTransportTest {
  private static final String PATH = "/api/orders/1";

  /** The agent's WARN for a session replaced by another JVM (SessionClient, 12 §16). */
  private static final String REPLACED_WARN = "SessionClose REPLACED";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private RunningApp jarApp(FakeCollector collector, String run, Map<String, String> settings)
      throws Exception {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    AppLauncher launcher =
        AppLauncher.of(
                SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
            .withAgent(TransportRuns.releaseJar())
            .collector(collector)
            .workDir(work.resolve(run));
    settings.forEach(launcher::agentConfig);
    return launcher.start(Duration.ofMinutes(4));
  }

  private static CompletableFuture<ExitedApp> runAsync(AppLauncher launcher) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return TransportRuns.runToExit(launcher);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** The time stamp at the start of an agent log line. */
  private static Instant timeOf(AgentLogAssert.LogLine line) {
    return OffsetDateTime.parse(line.raw().substring(0, line.raw().indexOf(' '))).toInstant();
  }

  @Test
  @Tag("req:N-17")
  @Tag("req:DSN-02-22")
  @Tag("req:SEC-06")
  @DisplayName(
      "T-BLD-011a run time: with http(s).proxyHost, socksProxyHost and java.net.useSystemProxies set"
          + " in the application JVM, the agent connects to server.address only: the session is"
          + " established and the proxy sees no connection")
  void applicationProxyIsNotUsed() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        TcpProbe proxy = TcpProbe.holding()) {
      String port = Integer.toString(proxy.port());
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                      work.resolve("proxy"),
                      TransportRuns.mainJdk(),
                      collector,
                      TransportRuns.STAY_ALIVE_MILLIS)
                  .withJvmOptions(
                      "-Dhttps.proxyHost=127.0.0.1",
                      "-Dhttps.proxyPort=" + port,
                      "-Dhttp.proxyHost=127.0.0.1",
                      "-Dhttp.proxyPort=" + port,
                      "-DsocksProxyHost=127.0.0.1",
                      "-DsocksProxyPort=" + port,
                      "-Djava.net.useSystemProxies=true"));
      assertThat(collector.sessions()).as(run.agentLog()).isNotEmpty();
      assertThat(collector.sessions().get(0).accepted()).isTrue();
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).as("batches went direct").isNotEmpty();
      assertThat(proxy.accepted()).as("connections to the proxy").isZero();
      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasNoErrors();
    }
  }

  @Test
  @DisplayName(
      "10 §10.2: against an address that closes every connection, the TCP connects follow the"
          + " session's back-off (1, 2, 4, 8, 16, 30 s with ±20 % jitter): at most one connect per"
          + " session attempt, not the channel's own faster reconnects")
  void connectsFollowTheBackoff() throws Exception {
    try (TcpProbe probe = TcpProbe.closing()) {
      ExitedApp run =
          TransportRuns.runToExit(
              AppLauncher.of(TransportRuns.trivialApp(), Jdk.of(TransportRuns.mainJdk()))
                  .withAgent(
                      TransportRuns.probeJar(),
                      Map.of(
                          "test.exporter", "grpc",
                          "server.address", probe.address(),
                          "server.tls", "false"))
                  .args(
                      work.resolve("backoff")
                          .resolve("out")
                          .resolve("results.properties")
                          .toString(),
                      "70000")
                  .workDir(work.resolve("backoff")));

      // Every session attempt logs the plaintext WARN once (16 §3.4 "매 접속").
      List<AgentLogAssert.LogLine> attempts = new ArrayList<>();
      for (AgentLogAssert.LogLine line : AgentLogAssert.linesAt(run.agentLog(), "WARN")) {
        if (line.message().contains(TransportRuns.PLAINTEXT_WARN)) {
          attempts.add(line);
        }
      }
      assertThat(attempts).as(run.agentLog()).hasSizeBetween(6, 8);
      assertThat(probe.accepted())
          .as(
              "TCP connects (%s) against session attempts (%s)",
              probe.acceptTimes(), attempts.size())
          .isBetween(attempts.size() - 1, attempts.size() + 1);

      long[] steps = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
      for (int i = 1; i < attempts.size(); i++) {
        long gap =
            Duration.between(timeOf(attempts.get(i - 1)), timeOf(attempts.get(i))).toMillis();
        long step = steps[i - 1];
        assertThat(gap)
            .as("attempt %d after %d ms (step %d ms)", i, gap, step)
            .isBetween((long) (step * 0.8) - 100, (long) (step * 1.2) + 1_000);
      }
      assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    }
  }

  @Test
  @Tag("req:N-05")
  @DisplayName(
      "T-AG-067 server.address does not resolve (collector.invalid, RFC 6761): for over a minute"
          + " the log has only the agent's connection WARN once per minute, no library WARN, no"
          + " stack trace; the app is unaffected")
  void unresolvableHostLogsOncePerMinute() throws Exception {
    Path dir = work.resolve("unresolvable");
    ExitedApp run =
        TransportRuns.runToExit(
            AppLauncher.of(TransportRuns.trivialApp(), Jdk.of(TransportRuns.mainJdk()))
                .withAgent(
                    TransportRuns.probeJar(),
                    Map.of("test.exporter", "grpc", "server.address", "collector.invalid:9100"))
                .args(dir.resolve("out").resolve("results.properties").toString(), "65000")
                .workDir(dir));
    String log = run.agentLog();
    List<AgentLogAssert.LogLine> warnings = AgentLogAssert.linesAt(log, "WARN");
    assertThat(warnings)
        .as("only the agent's own connection WARN; log:%n%s", log)
        .allSatisfy(line -> assertThat(line.message()).contains(TransportRuns.CONNECT_FAILED));
    assertThat(warnings).as("once per minute over about 65 s").hasSizeBetween(1, 2);
    assertThat(log).as("no library line at WARN").doesNotContain("Failed to resolve name");
    assertThat(log).as("no stack trace").doesNotContain("\tat ");
    assertThat(log.length()).as("log size").isLessThan(16 * 1024);
    AgentLogAssert.assertThatAgentLog(log).hasNoErrors();
    assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    assertThat(run.stdout().strip()).isEqualTo("trivial-app done");
  }

  @Test
  @DisplayName(
      "10 §10.2: name lookups of an unresolvable server.address follow the session's back-off:"
          + " with log.level=debug, at most one 'Failed to resolve name' line (gRPC, DEBUG) per"
          + " session attempt and none at WARN")
  void nameLookupsFollowTheBackoff() throws Exception {
    Path dir = work.resolve("lookups");
    ExitedApp run =
        TransportRuns.runToExit(
            AppLauncher.of(TransportRuns.trivialApp(), Jdk.of(TransportRuns.mainJdk()))
                .withAgent(
                    TransportRuns.probeJar(),
                    Map.of(
                        "test.exporter", "grpc",
                        "server.address", "collector.invalid:9100",
                        "server.tls", "false",
                        "log.level", "debug"))
                .args(dir.resolve("out").resolve("results.properties").toString(), "35000")
                .workDir(dir));
    String log = run.agentLog();
    long attempts =
        AgentLogAssert.linesAt(log, "WARN").stream()
            .filter(line -> line.message().contains(TransportRuns.PLAINTEXT_WARN))
            .count();
    // ManagedChannelImpl's line, once per failed resolution ("[{0}] Failed to resolve name.
    // status={1}"); gRPC's channel trace repeats it as "Failed to resolve name: ..." at FINE.
    long lookups =
        AgentLogAssert.linesAt(log, "DEBUG").stream()
            .filter(line -> line.message().contains("Failed to resolve name. status="))
            .count();
    // 35 s: attempts at about 0, 1, 3, 7, 15, 31 s (±20 %).
    assertThat(attempts).as(log).isBetween(4L, 7L);
    assertThat(lookups).as("failed lookups against %s session attempts", attempts).isPositive();
    assertThat(lookups).isLessThanOrEqualTo(attempts + 1);
    assertThat(AgentLogAssert.linesAt(log, "WARN"))
        .noneMatch(line -> line.message().contains("Failed to resolve name"));
  }

  @Test
  @Tag("req:SEC-07")
  @Tag("req:N-26")
  @DisplayName(
      "11 §10, 10 §14: after a refusal for good (PLAINTEXT_NOT_ALLOWED, retry_after_s 0) the agent"
          + " releases the transport: no connection to the collector, no argos-io thread;"
          + " argos-sender keeps discarding")
  void refusalReleasesTheTransport() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(false).start();
        TcpProbe relay = TcpProbe.relay(collector.port())) {
      collector.faults().plaintextRejected();
      try (RunningApp app =
          jarApp(collector, "refused", Map.of("server.address", relay.address()))) {
        assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).hasSize(1);
        assertThat(collector.sessions().get(0).accepted()).isFalse();
        assertThat(TransportRuns.await(() -> relay.open() == 0, Duration.ofSeconds(15)))
            .as("the agent's connection to the collector is closed; accepted %s", relay.accepted())
            .isTrue();
        String[] dump = {""};
        assertThat(
                TransportRuns.await(
                    () -> {
                      dump[0] = app.jcmd().threadPrint();
                      return !dump[0].contains("\"argos-io");
                    },
                    Duration.ofSeconds(15)))
            .as("no argos-io thread:%n%s", dump[0])
            .isTrue();
        assertThat(dump[0]).contains("\"argos-sender");
        assertThat(app.get(PATH).status()).isEqualTo(200);
        Thread.sleep(2_000);
        assertThat(relay.accepted()).as("no new connection").isEqualTo(1);
        assertThat(collector.sessions()).hasSize(1);
        List<AgentLogAssert.LogLine> errors = AgentLogAssert.linesAt(app.agentLog(), "ERROR");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("PLAINTEXT_NOT_ALLOWED");
      }
    }
  }

  @Test
  @DisplayName(
      "12 §16, 11 §5 check 7: two JVMs with the same app.name and app.instance: the first is"
          + " replaced, logs one WARN and does not reconnect; for 15 s the collector sees no new"
          + " session and the second agent's session stays up")
  void replacedIsNotRetried() throws Exception {
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).tlsDirectory(work).heartbeatIntervalS(1).start()) {
      CompletableFuture<ExitedApp> first =
          runAsync(
              TransportRuns.trivial(
                      work.resolve("first"), TransportRuns.mainJdk(), collector, "50000")
                  .agentConfig("app.instance", "duplicate:1"));
      List<SessionInfo> one = collector.awaitSessions(1, Duration.ofSeconds(30));
      assertThat(one).hasSize(1);
      assertThat(one.get(0).accepted()).isTrue();

      CompletableFuture<ExitedApp> second =
          runAsync(
              TransportRuns.trivial(
                      work.resolve("second"), TransportRuns.mainJdk(), collector, "35000")
                  .agentConfig("app.instance", "duplicate:1"));
      List<SessionInfo> two = collector.awaitSessions(2, Duration.ofSeconds(30));
      assertThat(two).hasSize(2);
      SessionInfo replacing = two.get(1);
      assertThat(replacing.accepted()).isTrue();
      assertThat(replacing.agentEpoch()).isNotEqualTo(one.get(0).agentEpoch());
      assertThat(replacing.instanceId()).as("the same instance").isEqualTo(one.get(0).instanceId());

      Thread.sleep(15_000);
      Instant now = Instant.now();
      assertThat(collector.sessions()).as("no session after the replacement").hasSize(2);
      assertThat(collector.openSessionCount()).isEqualTo(1);
      assertThat(collector.heartbeats())
          .as("the second agent's session is alive")
          .anyMatch(
              (HeartbeatRecord beat) ->
                  beat.sessionId().equals(replacing.sessionId())
                      && beat.receivedAt().isAfter(now.minusSeconds(3)));
      assertThat(collector.heartbeats())
          .as("nothing from the first agent's session in the last 10 s")
          .noneMatch(
              (HeartbeatRecord beat) ->
                  beat.sessionId().equals(one.get(0).sessionId())
                      && beat.receivedAt().isAfter(now.minusSeconds(10)));

      ExitedApp firstRun = first.get();
      ExitedApp secondRun = second.get();
      assertThat(collector.sessions()).as("no reconnect until both exited").hasSize(2);
      AgentLogAssert.assertThatAgentLog(firstRun.agentLog())
          .hasWarningCount(REPLACED_WARN, 1)
          .hasNoErrors();
      assertThat(TransportRuns.count(firstRun.agentLog(), TransportRuns.SESSION_ESTABLISHED))
          .isEqualTo(1);
      AgentLogAssert.assertThatAgentLog(secondRun.agentLog())
          .hasWarningCount(REPLACED_WARN, 0)
          .hasNoErrors();
      ScriptedAgent.assertNoToken("the first agent log", firstRun.agentLog(), collector.token());
    }
  }

  @Test
  @DisplayName(
      "11 §4.2, §6: Ack.throttle.profile_normal_rate 0 (in the Ack, not on the session stream)"
          + " stops the profiling of normal requests")
  void ackThrottleLowersTheProfileRate() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            jarApp(collector, "ack-throttle", Map.of("sampling.profile.normal-rate", "1.0"))) {
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(30))).isNotEmpty();
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () -> !collector.batches(BatchKind.PROFILES).isEmpty(), Duration.ofSeconds(20)))
          .as("profiles flow at rate 1.0")
          .isTrue();

      collector.faults().throttle(0.0f, null);
      int acked = collector.batches(BatchKind.TRANSACTIONS).size();
      assertThat(app.get(PATH).status()).isEqualTo(200);
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                              .filter(ReceivedBatch::acked)
                              .count()
                          > acked,
                  Duration.ofSeconds(20)))
          .as("an Ack with the throttle reached the agent")
          .isTrue();
      Thread.sleep(1_000);
      int profiles = collector.batches(BatchKind.PROFILES).size();
      app.load(20, Duration.ofSeconds(2), PATH);
      Thread.sleep(1_500);
      assertThat(collector.batches(BatchKind.PROFILES))
          .as("normal requests are no longer profiled")
          .hasSize(profiles);
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
    }
  }
}
