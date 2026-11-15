/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.RejectReason;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The collector inside the real server ({@link io.argos.server.ServerApplication}, every start
 * phase): the gRPC listener starts as the {@code LISTENERS} step after the WAL recovery
 * (DSN-12-15), serves TLS with the configured certificate, accepts a session and a batch end to
 * end, and keeps agent tokens out of logs, meters, replies and PostgreSQL (T-SEC-02 server part).
 */
@Tag("integration")
@Tag("req:SEC-02")
@Tag("req:DSN-12-15")
class CollectorServerIT {

  private static final Pattern TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{40,}");

  @TempDir static Path walDir;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of("argos.wal.dir", walDir.toString()));
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static FakeAgent agent(String token, String app) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", env.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token)
        .appName(app)
        .build();
  }

  @Test
  @DisplayName(
      "DSN-12-15 the gRPC listener is the LISTENERS step, after WAL_RECOVERY, and serves a session and a batch over TLS")
  void listenerStep() throws Exception {
    StartupSequence sequence = server.bean(StartupSequence.class);
    List<String> order = sequence.results().stream().map(r -> r.phase() + ":" + r.name()).toList();
    assertThat(order).contains("LISTENERS:grpc-collector");
    int listeners = order.indexOf("LISTENERS:grpc-collector");
    for (int i = 0; i < order.size(); i++) {
      if (order.get(i).startsWith(PhaseOrder.WAL_RECOVERY.name())) {
        assertThat(i).as("WAL recovery before the listener").isLessThan(listeners);
      }
    }
    String app = "server-it";
    String token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod")).token();
    }
    try (FakeAgent a = agent(token, app)) {
      HelloResult hello = a.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      CallResult r =
          a.sendTransactions(a.batches().transactions(List.of(a.records().transaction().build())));
      assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-02a no token in the server log, meters, replies or agent_tokens; the log names the prefix only")
  @Tag("req:SEC-01")
  void tokenSecrecy() throws Exception {
    String app = "secrecy-it";
    AgentTokens.IssuedToken issued;
    try (Connection c = env.connect(PgAccount.APP)) {
      issued = AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
    String unknown = AgentTokens.generate();
    List<String> replies = new ArrayList<>();
    try (FakeAgent good = agent(issued.token(), app);
        FakeAgent bad = agent(unknown, app)) {
      HelloResult ok = good.connect();
      assertThat(ok.accepted()).isTrue();
      HelloResult rejected = bad.connect();
      assertThat(rejected.rejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
      replies.add(rejected.hello().toString());
      replies.add(String.valueOf(rejected.status()));
      replies.add(String.valueOf(rejected.trailers()));
      CallResult data =
          bad.send(
              io.argos.server.testing.fakeagent.BatchKind.TRANSACTIONS,
              good.batches().transactions(List.of(good.records().transaction().build())),
              1,
              FakeAgent.CallSpec.defaults().withSession(good.instanceId(), good.sessionId()));
      assertThat(data.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
      replies.add(String.valueOf(data.status()));
      replies.add(String.valueOf(data.trailers()));
    }
    for (String reply : replies) {
      assertThat(reply).doesNotContain(issued.token()).doesNotContain(unknown);
      assertThat(TOKEN.matcher(reply).find()).as(reply).isFalse();
    }

    MeterRegistry meters = server.bean(MeterRegistry.class);
    for (Meter m : meters.getMeters()) {
      String id = m.getId().toString();
      assertThat(TOKEN.matcher(id).find()).as(id).isFalse();
    }

    try (Connection c = env.connect(PgAccount.APP);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT * FROM argos.agent_tokens")) {
      ResultSetMetaData md = rs.getMetaData();
      int rows = 0;
      while (rs.next()) {
        rows++;
        for (int i = 1; i <= md.getColumnCount(); i++) {
          String value = rs.getString(i);
          if (value != null) {
            assertThat(TOKEN.matcher(value).find())
                .as("agent_tokens.%s holds no token", md.getColumnName(i))
                .isFalse();
          }
        }
      }
      assertThat(rows).isPositive();
    }

    Path log = IntegrationLogs.logFile();
    String prefix = unknown.substring(0, 14);
    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .until(
            () ->
                Files.exists(log)
                    && Files.readString(log, StandardCharsets.UTF_8).contains(prefix));
    String text = Files.readString(log, StandardCharsets.UTF_8);
    assertThat(text).doesNotContain(issued.token()).doesNotContain(unknown);
    assertThat(TOKEN.matcher(text).find()).as("no 43-character token in the server log").isFalse();
    assertThat(text).contains("Agent token rejected: reason=token_unknown prefix=" + prefix);
  }
}
