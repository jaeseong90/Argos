/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceState;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.session.SessionRegistry;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.collector.RecordingIngest;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.wal.api.WalRecordType;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The minimal session of ADR-0010 C-128 over the wire: data calls need the session of the same
 * token (SESSION_REQUIRED, PERMISSION_DENIED), a half-closed stream keeps its session, a cancelled
 * one drops it, heartbeats carry the 11 §9 offset (server − agent, C-139) that becomes the C-129
 * correction of the next batch, dictionary entries reach the ingest port, InstanceUpdate is applied
 * once (C-146), the other session messages are counted and ignored, and a stream without Hello
 * closes after 10 s.
 */
@Tag("integration")
@Tag("req:F-S01")
class SessionRulesIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness collector;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector = CollectorHarness.start(env);
  }

  @AfterAll
  static void stop() {
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static String app() {
    return "sess-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static TransactionBatch one(FakeAgent a) {
    return a.batches().transactions(List.of(a.records().transaction().build()));
  }

  @Test
  @DisplayName(
      "11 §3 data calls without session ids, with an unknown session or another instance are SESSION_REQUIRED; another token's session is PERMISSION_DENIED")
  void sessionInterceptor() {
    String app = app();
    String token = collector.token(app, "prod").token();
    String other = collector.token(app, "prod").token();
    try (FakeAgent a = collector.agent(token).appName(app).build()) {
      CallResult before = a.sendTransactions(one(a));
      assertThat(before.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(before.status().getDescription()).isEqualTo("SESSION_REQUIRED");
      assertThat(a.connect().accepted()).isTrue();
      TransactionBatch b = one(a);
      assertThat(
              a.send(BatchKind.TRANSACTIONS, b, 1, FakeAgent.CallSpec.defaults().withoutSession())
                  .status()
                  .getDescription())
          .isEqualTo("SESSION_REQUIRED");
      assertThat(
              a.send(
                      BatchKind.TRANSACTIONS,
                      b,
                      1,
                      FakeAgent.CallSpec.defaults()
                          .withSession(a.instanceId(), UUID.randomUUID().toString()))
                  .status()
                  .getDescription())
          .isEqualTo("SESSION_REQUIRED");
      assertThat(
              a.send(
                      BatchKind.TRANSACTIONS,
                      b,
                      1,
                      FakeAgent.CallSpec.defaults()
                          .withSession(a.instanceId() + 1_000, a.sessionId()))
                  .status()
                  .getDescription())
          .isEqualTo("SESSION_REQUIRED");
      CallResult foreign =
          a.send(BatchKind.TRANSACTIONS, b, 1, FakeAgent.CallSpec.defaults().withToken(other));
      assertThat(foreign.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
      CallResult unauthenticated =
          a.send(BatchKind.TRANSACTIONS, b, 1, FakeAgent.CallSpec.defaults().withoutToken());
      assertThat(unauthenticated.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(unauthenticated.rejectReason()).isEqualTo("TOKEN_INVALID");
      assertThat(a.sendTransactions(b).ok()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "11 §3 a half-closed stream keeps its session for the last data calls; a cancelled call drops it (C-128)")
  void streamEnd() {
    String app = app();
    String token = collector.token(app, "prod").token();
    SessionRegistry registry = collector.bean(SessionRegistry.class);
    try (FakeAgent a = collector.agent(token).appName(app).build()) {
      assertThat(a.connect().accepted()).isTrue();
      a.sendBye(ByeReason.SHUTDOWN);
      a.completeSession();
      assertThat(a.sendTransactions(one(a)).ok()).as("shutdown flush after the bye").isTrue();
      assertThat(registry.find(a.instanceId())).isPresent();
      a.cancelSession();
      int instance = a.instanceId();
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> registry.find(instance).isEmpty());
      CallResult after = a.sendTransactions(one(a));
      assertThat(after.status().getDescription()).isEqualTo("SESSION_REQUIRED");
    }
  }

  @Test
  @DisplayName(
      "C-129 an agent 60 s ahead: HeartbeatAck offset about -60 s, the next batch's WAL header carries a 60 s correction; 3 s ahead is not corrected")
  @Tag("req:D-02")
  @Tag("req:D-15")
  void clockOffsetAndCorrection() {
    for (long skew : new long[] {60_000, 3_000, 90_000_000}) {
      String app = app();
      String token = collector.token(app, "prod").token();
      try (FakeAgent a =
          collector
              .agent(token)
              .appName(app)
              .clock(AgentClock.skewed(Duration.ofMillis(skew)))
              .build()) {
        HelloResult hello = a.connect();
        assertThat(hello.accepted()).isTrue();
        Optional<HeartbeatAck> first = a.heartbeatRound(Duration.ofSeconds(5));
        Optional<HeartbeatAck> second = a.heartbeatRound(Duration.ofSeconds(5));
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().getState()).isEqualTo(InstanceState.UP);
        assertThat(second.get().getT1Ms()).isPositive();
        assertThat((long) second.get().getClockOffsetMs())
            .as("server − agent for an agent %d ms ahead", skew)
            .isBetween(-skew - 1_000, -skew + 1_000);
        CallResult r = a.sendTransactions(one(a));
        assertThat(r.ok()).isTrue();
        long correction =
            collector.wal().appended(WalRecordType.TX).stream()
                .filter(x -> x.instanceId() == a.instanceId())
                .reduce((x, y) -> y)
                .orElseThrow()
                .tsCorrectionMs();
        long expected = skew > 5_000 ? Math.round(skew / 10_000.0) * 10_000 : 0;
        assertThat(correction).as("C-129 correction for %d ms ahead", skew).isEqualTo(expected);
      }
    }
  }

  @Test
  @DisplayName(
      "11 §7 dictionary entries reach the ingest port; entries of an unknown kind are dropped and counted")
  void dictionary() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (FakeAgent a = collector.agent(token).appName(app).build()) {
      assertThat(a.connect().accepted()).isTrue();
      long hash = a.records().id(DictionaryKind.SERVICE, "GET /dictionary");
      a.sendDictionaryEntries(
          List.of(
              a.records().entry(DictionaryKind.SERVICE, hash).orElseThrow(),
              DictionaryEntry.newBuilder().setKindValue(77).setHash(5).setText("x").build()));
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(
              () ->
                  collector.ingest().dictionary().stream()
                      .anyMatch(d -> d.instanceId() == a.instanceId()));
      RecordingIngest.Dictionary d =
          collector.ingest().dictionary().stream()
              .filter(x -> x.instanceId() == a.instanceId())
              .findFirst()
              .orElseThrow();
      assertThat(d.entries()).extracting(DictionaryEntry::getHash).containsExactly(hash);
      MeterRegistry meters = collector.bean(MeterRegistry.class);
      assertThat(
              meters
                  .get("argos.collector.unknown.enum")
                  .tag("field", "dictionary.kind")
                  .counter()
                  .count())
          .isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "C-146 the same InstanceUpdate twice changes the instances row once; the app version becomes the session's (C-143 (6))")
  void instanceUpdateIdempotent() throws Exception {
    String app = app();
    AgentTokens.IssuedToken issued = collector.token(app, "prod");
    try (FakeAgent a =
        collector.agent(issued.token()).appName(app).instanceName("host-u:1234").build()) {
      assertThat(a.connect().accepted()).isTrue();
      long xmin0 = xmin(a.instanceId());
      InstanceUpdate update =
          InstanceUpdate.newBuilder().setInstanceName("host-u:8080").setAppVersion("2.0.0").build();
      a.sendInstanceUpdate(update);
      Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> xmin(a.instanceId()) != xmin0);
      long xmin1 = xmin(a.instanceId());
      a.sendInstanceUpdate(update);
      a.heartbeatRound(Duration.ofSeconds(5));
      assertThat(xmin(a.instanceId()))
          .as("the second identical update writes nothing")
          .isEqualTo(xmin1);
      assertThat(text(a.instanceId(), "name")).isEqualTo("host-u:8080");
      assertThat(text(a.instanceId(), "version_tag")).isEqualTo("2.0.0");
      assertThat(a.sendTransactions(one(a)).ok()).isTrue();
      var last =
          collector.ingest().calls("transactions").stream()
              .filter(c -> c.context().instanceId() == a.instanceId())
              .reduce((x, y) -> y)
              .orElseThrow();
      assertThat(last.context().appVersion()).isEqualTo("2.0.0");
    }
  }

  @Test
  @DisplayName(
      "C-128 Bye, events, command and configuration results are counted and ignored; the stream stays open")
  void unhandledMessages() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (FakeAgent a = collector.agent(token).appName(app).build()) {
      assertThat(a.connect().accepted()).isTrue();
      a.sendEvent(
          AgentEvent.newBuilder()
              .setType(AgentEventType.MODULE_DISABLED)
              .setModule("jdbc")
              .build());
      a.send(
          AgentMessage.newBuilder()
              .setCommandResult(CommandResult.newBuilder().setCommandId("c"))
              .build());
      a.send(
          AgentMessage.newBuilder()
              .setConfigApplyResult(ConfigApplyResult.newBuilder().setChangeId("x"))
              .build());
      a.send(AgentMessage.newBuilder().setHello(a.helloTemplate()).build());
      a.send(AgentMessage.newBuilder().build());
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(a.sessionOpen()).isTrue();
      MeterRegistry meters = collector.bean(MeterRegistry.class);
      for (String type :
          new String[] {
            "event", "command_result", "config_apply_result", "hello_repeated", "unknown"
          }) {
        assertThat(
                meters.get("argos.collector.session.messages").tag("type", type).counter().count())
            .as(type)
            .isGreaterThanOrEqualTo(1);
      }
    }
  }

  @Test
  @DisplayName(
      "11 §2 a stream without AgentHello is closed after 10 s with SessionClose PROTOCOL_ERROR")
  @Tag("slow")
  @Tag("req:SEC-15")
  void helloTimeout() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    io.grpc.ManagedChannel channel = collector.channel();
    try {
      io.grpc.Metadata headers = new io.grpc.Metadata();
      headers.put(FakeAgent.AUTHORIZATION, "Bearer " + token);
      io.grpc.Channel withToken =
          io.grpc.ClientInterceptors.intercept(
              channel, io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
      java.util.concurrent.CompletableFuture<Status> end =
          new java.util.concurrent.CompletableFuture<>();
      List<io.argos.proto.v1.ServerMessage> received =
          java.util.Collections.synchronizedList(new java.util.ArrayList<>());
      long start = System.nanoTime();
      io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          withToken.newCall(
              io.argos.proto.v1.AgentServiceGrpc.getSessionMethod(), io.grpc.CallOptions.DEFAULT),
          new io.grpc.stub.StreamObserver<io.argos.proto.v1.ServerMessage>() {
            @Override
            public void onNext(io.argos.proto.v1.ServerMessage value) {
              received.add(value);
            }

            @Override
            public void onError(Throwable t) {
              end.complete(Status.fromThrowable(t));
            }

            @Override
            public void onCompleted() {
              end.complete(Status.OK);
            }
          });
      // Nothing is sent: no AgentHello.
      Status status = end.get(30, TimeUnit.SECONDS);
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(received).hasSize(1);
      assertThat(received.get(0).getClose().getReason()).isEqualTo(CloseReason.PROTOCOL_ERROR);
      assertThat(elapsedMs).as("closed after the 10 s Hello timeout").isBetween(9_000L, 20_000L);
    } finally {
      channel.shutdownNow();
    }
  }

  private static long xmin(int instanceId) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT xmin::text::bigint FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static String text(int instanceId, String column) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT " + column + " FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }
}
