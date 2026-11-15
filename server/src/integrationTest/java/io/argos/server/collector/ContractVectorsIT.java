/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.MessageLite;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.session.SessionRegistry;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.collector.ContractVectors;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.Batches;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The WO-11 contract vectors (11 §16: {@code argos-v1-vectors.json}, the file the agent's
 * FakeCollector is driven with) replayed against the real collector over TLS with real PostgreSQL:
 * the {@code session} and {@code dedup} sections step by step (a {@code fault} step there is set up
 * as the real state it names), and real-server equivalents of the FakeCollector-only {@code
 * status_codes} entries. The {@code clock} section is arithmetic and is replayed by {@code
 * ClockSyncTest}.
 *
 * <p>Every step is asserted as the vector states it: the vectors follow the design values (ADR-0010
 * C-159 (2): instance id reused across a restart, dedup window restored from the WAL, {@code
 * retry_after_s} 300 on {@code TOKEN_*}, C-140). The only translation is the instance id: a vector
 * numbers the instances of a scenario, the server issues its own ids, so equal numbers must be the
 * same server id and different numbers different ones ({@code Replay.expectInstance}).
 */
@Tag("integration")
@Tag("req:F-S01")
@Tag("req:D-13")
class ContractVectorsIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness tls;
  private final List<AutoCloseable> closing = new ArrayList<>();

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    tls =
        CollectorHarness.builder(env)
            .property("argos.agent.auto-register.unregistered", false)
            .start();
  }

  @AfterAll
  static void stop() {
    if (tls != null) {
      tls.close();
    }
    if (env != null) {
      env.close();
    }
  }

  @AfterEach
  void closeAgents() throws Exception {
    for (AutoCloseable c : closing) {
      c.close();
    }
    closing.clear();
  }

  private <T extends AutoCloseable> T closing(T c) {
    closing.add(c);
    return c;
  }

  private static JsonNode vector(String section, int index) {
    return ContractVectors.section(section).get(index);
  }

  private static String app() {
    return "vec-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** The replay state of one vector. */
  private final class Replay {
    final String name;
    final String app = app();
    final AgentTokens.IssuedToken token;
    final String otherToken;
    CollectorHarness harness;
    FakeAgent agent;
    String helloApp;
    String helloEnv = "prod";
    String instanceName = "vec-instance";
    int currentInstance;
    String currentSession;
    int previousInstance;
    String previousSession;
    long epoch = 7;
    final Map<String, FakeAgent> streams = new HashMap<>();

    /** The server's instance id of each {@code expected_instance_id} number of the vector. */
    final Map<Long, Integer> instanceIds = new HashMap<>();

    Replay(String name, CollectorHarness harness) {
      this.name = name;
      this.harness = harness;
      this.token = harness.token(app, "prod");
      this.otherToken = harness.token(app, "prod").token();
      this.helloApp = app;
    }

    FakeAgent newAgent(String tokenText, long agentEpoch) {
      return closing(
          harness
              .agent(tokenText)
              .appName(helloApp)
              .env(helloEnv)
              .instanceName(instanceName)
              .agentEpoch(agentEpoch)
              .build());
    }

    String tokenOf(JsonNode step) {
      return switch (step.get("token").asString()) {
        case "valid" -> token.token();
        case "other" -> otherToken;
        case "missing" -> null;
        case "malformed" -> "not-an-argos-token";
        default -> throw new IllegalArgumentException(step.toString());
      };
    }

    HelloResult hello(JsonNode step) {
      long agentEpoch = step.has("agent_epoch") ? step.get("agent_epoch").asLong() : epoch;
      if (step.has("instance_name")) {
        instanceName = step.get("instance_name").asString();
      }
      FakeAgent a = newAgent(tokenOf(step), agentEpoch);
      HelloResult r =
          step.has("protocol_version")
              ? a.connect(
                  a.helloTemplate().toBuilder()
                      .setProtocolVersion(step.get("protocol_version").asInt())
                      .build())
              : a.connect();
      if (r.accepted()) {
        previousInstance = currentInstance;
        previousSession = currentSession;
        currentInstance = r.hello().getInstanceId();
        currentSession = r.hello().getSessionId();
        agent = a;
        epoch = agentEpoch;
        if (step.has("stream")) {
          streams.put(step.get("stream").asString(), a);
        }
      }
      return r;
    }

    CallResult data(JsonNode step) {
      FakeAgent a = agent != null ? agent : newAgent(token.token(), epoch);
      long seq = step.get("seq").asLong();
      FakeAgent.CallSpec spec = FakeAgent.CallSpec.defaults();
      String t = step.has("token") ? tokenOf(step) : token.token();
      spec = t == null ? spec.withoutToken() : spec.withToken(t);
      String session = step.has("session") ? step.get("session").asString() : "current";
      spec =
          switch (session) {
            case "none" -> spec.withoutSession();
            case "current" -> spec.withSession(currentInstance, currentSession);
            case "unknown" -> spec.withSession(currentInstance, UUID.randomUUID().toString());
            case "current_other_instance" ->
                spec.withSession(currentInstance + 1_000, currentSession);
            case "previous" -> spec.withSession(previousInstance, previousSession);
            default -> throw new IllegalArgumentException(step.toString());
          };
      BatchHeader header =
          a.batches().header(BatchKind.TRANSACTIONS, seq, epoch).toBuilder()
              .setInstanceId(0)
              .build();
      TransactionBatch batch =
          Batches.transactions(header, List.of(a.records().transaction().build()));
      return a.send(BatchKind.TRANSACTIONS, batch, seq, spec);
    }

    /**
     * A hello step: the answer ({@code expectHello}) and the instance ({@link #expectInstance}).
     */
    void checkedHello(JsonNode step) {
      HelloResult r = hello(step);
      expectHello(r, step);
      expectInstance(step, r);
    }

    /**
     * {@code expected_instance_id} of an accepted hello: the first time a number appears it names
     * an id no other number of this vector has; afterwards the server must answer that id again.
     */
    void expectInstance(JsonNode step, HelloResult r) {
      if (!r.accepted() || !step.has("expected_instance_id")) {
        return;
      }
      long number = step.get("expected_instance_id").asLong();
      int id = r.hello().getInstanceId();
      Integer known = instanceIds.get(number);
      if (known == null) {
        assertThat(instanceIds.values())
            .as("%s: instance %d is new, not one of %s (%s)", name, number, instanceIds, step)
            .doesNotContain(id);
        instanceIds.put(number, id);
      } else {
        assertThat(id).as("%s: instance %d (%s)", name, number, step).isEqualTo(known);
      }
    }
  }

  private static void expectData(CallResult r, JsonNode step) {
    assertThat(r.code().name()).as(step.toString()).isEqualTo(step.get("expected_code").asString());
    if (step.has("expected_description")) {
      assertThat(r.status().getDescription())
          .isEqualTo(step.get("expected_description").asString());
    }
    if (step.has("expected_status")) {
      assertThat(r.ackStatus().name()).isEqualTo(step.get("expected_status").asString());
    }
    if (step.has("expected_last_seq")) {
      assertThat(r.ack().getLastSeq()).isEqualTo(step.get("expected_last_seq").asLong());
    }
    if (step.has("expected_trailers")) {
      step.get("expected_trailers")
          .properties()
          .forEach(
              e ->
                  assertThat(
                          r.trailers()
                              .get(
                                  io.grpc.Metadata.Key.of(
                                      e.getKey(), io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
                      .as(e.getKey())
                      .isEqualTo(e.getValue().asString()));
    }
  }

  private static void expectHello(HelloResult r, JsonNode step) {
    assertThat(r.accepted())
        .as(step + " -> " + r)
        .isEqualTo(step.get("expected_accepted").asBoolean());
    if (step.has("expected_reject_reason")) {
      assertThat(r.rejectReason().name()).isEqualTo(step.get("expected_reject_reason").asString());
    }
    if (step.has("expected_retry_after_s")) {
      assertThat(r.retryAfterS())
          .as(step.toString())
          .isEqualTo(step.get("expected_retry_after_s").asInt());
    }
    if (step.has("expected_code")) {
      assertThat(r.code().name()).isEqualTo(step.get("expected_code").asString());
    }
    if (step.has("expected_trailers")) {
      step.get("expected_trailers")
          .properties()
          .forEach(
              e ->
                  assertThat(
                          r.trailers()
                              .get(
                                  io.grpc.Metadata.Key.of(
                                      e.getKey(), io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
                      .as(e.getKey())
                      .isEqualTo(e.getValue().asString()));
    }
  }

  @Test
  @DisplayName(
      "11 §16 vector 'session' #1: data calls need the token and an accepted session; a restart"
          + " keeps the instance id and its dedup window (11 §5, 12 §3.4, D-13)")
  void session1() {
    JsonNode v = vector("session", 0);
    Replay replay = new Replay(v.get("name").asString(), tls);
    for (JsonNode step : v.get("steps")) {
      switch (step.get("action").asString()) {
        case "data" -> expectData(replay.data(step), step);
        case "hello" -> replay.checkedHello(step);
        case "restart" -> {
          replay.agent.close();
          replay.agent = null;
          // The server forgets the session: "previous" is the session from before the restart. It
          // keeps the instance (11 §5) and rebuilds its dedup window from the WAL (12 §3.4, D-13).
          replay.previousInstance = replay.currentInstance;
          replay.previousSession = replay.currentSession;
          tls.restart();
        }
        default -> throw new IllegalArgumentException(step.toString());
      }
    }
  }

  @Test
  @DisplayName(
      "11 §16 vector 'session' #2: Hello checks in 11 §5 order with the fault states set up for real")
  void session2() {
    JsonNode v = vector("session", 1);
    Replay replay = new Replay(v.get("name").asString(), tls);
    CollectorHarness plaintext = closing(CollectorHarness.builder(env).plaintext(false).start());
    CollectorHarness limited =
        closing(
            CollectorHarness.builder(env)
                .property("argos.collector.session.instances-per-app", 1)
                .start());
    // The app already has one instance: a new name hits INSTANCE_LIMIT on the limited collector.
    try (FakeAgent first =
        limited.agent(replay.token.token()).appName(replay.app).instanceName("existing").build()) {
      assertThat(first.connect().accepted()).isTrue();
    }
    for (JsonNode step : v.get("steps")) {
      String action = step.get("action").asString();
      if (action.equals("fault")) {
        String fault = step.get("fault").asString();
        String reason = step.has("reason") ? step.get("reason").asString() : "";
        switch (fault + ":" + reason) {
          case "plaintextRejected:" -> replay.harness = plaintext;
          case "rejectHello:APP_MISMATCH" -> replay.helloApp = "another-" + replay.app;
          case "rejectHello:APP_NOT_REGISTERED" -> {
            replay.harness = tls;
            replay.helloApp = replay.app;
            replay.helloEnv = "unregistered";
          }
          case "rejectHello:INSTANCE_LIMIT" -> {
            replay.harness = limited;
            replay.helloEnv = "prod";
            replay.instanceName = "brand-new";
          }
          case "clear:" -> {
            replay.harness = tls;
            replay.helloApp = replay.app;
            replay.helloEnv = "prod";
            replay.instanceName = "vec-instance";
          }
          default -> throw new IllegalArgumentException(step.toString());
        }
      } else {
        replay.checkedHello(step);
      }
    }
  }

  @Test
  @DisplayName(
      "11 §16 vector 'session' #3: one session per instance, REPLACED whatever the epoch, a half-closed stream ends without a message")
  void session3() {
    JsonNode v = vector("session", 2);
    Replay replay = new Replay(v.get("name").asString(), tls);
    for (JsonNode step : v.get("steps")) {
      switch (step.get("action").asString()) {
        case "hello" -> replay.checkedHello(step);
        case "data" -> expectData(replay.data(step), step);
        case "complete" -> replay.streams.get(step.get("stream").asString()).completeSession();
        case "expect_stream" -> {
          FakeAgent stream = replay.streams.get(step.get("stream").asString());
          HelloResult end = stream.awaitSessionEnd(Duration.ofSeconds(10));
          assertThat(end.code().name()).isEqualTo(step.get("expected_code").asString());
          List<ServerMessage> closes =
              stream.serverMessages().stream().filter(ServerMessage::hasClose).toList();
          JsonNode expected = step.get("expected_messages");
          assertThat(closes).hasSize(expected.size());
          for (int i = 0; i < expected.size(); i++) {
            assertThat(closes.get(i).getClose().getReason().name())
                .isEqualTo(expected.get(i).get("close").get("reason").asString());
          }
        }
        default -> throw new IllegalArgumentException(step.toString());
      }
    }
  }

  @Test
  @DisplayName(
      "11 §16 vector 'session' #4: InstanceUpdate.instance_name renames the instance and keeps its id;"
          + " after a restart the new name gets the same id and dedup window, the old name is a new"
          + " instance (12 §4.3, ADR-0010 C-185 (1))")
  void session4() throws Exception {
    JsonNode v = vector("session", 3);
    Replay replay = new Replay(v.get("name").asString(), tls);
    for (JsonNode step : v.get("steps")) {
      switch (step.get("action").asString()) {
        case "hello" -> replay.checkedHello(step);
        case "data" -> expectData(replay.data(step), step);
        case "instance_update" -> {
          String name = step.get("instance_name").asString();
          replay.agent.sendInstanceUpdate(
              InstanceUpdate.newBuilder().setInstanceName(name).build());
          // Not acknowledged on the wire (see the vectors' interpretation): the session takes the
          // new name after the store has written it (SessionHandler.instanceUpdate).
          SessionRegistry registry = tls.bean(SessionRegistry.class);
          int instance = replay.currentInstance;
          long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
          while (!registry.find(instance).map(s -> s.instanceName().equals(name)).orElse(false)
              && System.nanoTime() < deadline) {
            Thread.sleep(20);
          }
          assertThat(registry.find(instance).map(s -> s.instanceName()))
              .as("%s: the instance renamed (%s)", replay.name, step)
              .contains(name);
        }
        case "restart" -> {
          replay.agent.close();
          replay.agent = null;
          replay.previousInstance = replay.currentInstance;
          replay.previousSession = replay.currentSession;
          tls.restart();
        }
        default -> throw new IllegalArgumentException(step.toString());
      }
    }
  }

  @Test
  @DisplayName(
      "11 §16 vector 'dedup': key (instance, epoch, kind, seq), last_seq per kind, window 1,000, retry after an error")
  void dedup() {
    JsonNode v = vector("dedup", 0);
    Replay replay = new Replay(v.get("name").asString(), tls);
    FakeAgent a = replay.newAgent(replay.token.token(), 7);
    assertThat(a.connect().accepted()).isTrue();
    for (JsonNode step : v.get("steps")) {
      String kind = step.get("kind").asString();
      long epoch = step.get("agent_epoch").asLong();
      if (step.has("seq_from")) {
        for (long seq = step.get("seq_from").asLong(); seq <= step.get("seq_to").asLong(); seq++) {
          CallResult r = send(a, kind, epoch, seq);
          assertThat(r.ackStatus()).as("seq %d", seq).isEqualTo(AckStatus.ACK_OK);
          assertThat(r.ack().getLastSeq()).isEqualTo(seq);
        }
        continue;
      }
      long seq = step.get("seq").asLong();
      if (step.has("fault")) {
        tls.wal().failing(true);
        try {
          CallResult r = send(a, kind, epoch, seq);
          assertThat(r.code().name()).isEqualTo(step.get("expected_code").asString());
        } finally {
          tls.wal().failing(false);
        }
        continue;
      }
      CallResult r = send(a, kind, epoch, seq);
      assertThat(r.ackStatus().name())
          .as(step.toString())
          .isEqualTo(step.get("expected_status").asString());
      assertThat(r.ack().getLastSeq())
          .as(step.toString())
          .isEqualTo(step.get("expected_last_seq").asLong());
    }
  }

  private static CallResult send(FakeAgent a, String kind, long epoch, long seq) {
    BatchKind batchKind =
        switch (kind) {
          case "TRANSACTIONS" -> BatchKind.TRANSACTIONS;
          case "PROFILES" -> BatchKind.PROFILES;
          case "METRICS" -> BatchKind.METRICS;
          case "STATS" -> BatchKind.STATS;
          case "ACTIVE_SNAPSHOT" -> BatchKind.ACTIVE;
          default -> throw new IllegalArgumentException(kind);
        };
    BatchHeader header = a.batches().header(batchKind, seq, epoch);
    MessageLite batch =
        switch (batchKind) {
          case TRANSACTIONS ->
              Batches.transactions(header, List.of(a.records().transaction().build()));
          case PROFILES -> ProfileBatch.newBuilder().setHeader(header).build();
          case METRICS -> MetricBatch.newBuilder().setHeader(header).build();
          case STATS -> StatBatch.newBuilder().setHeader(header).setWindowLenS(10).build();
          case ACTIVE -> ActiveTransactionSnapshot.newBuilder().setHeader(header).build();
        };
    return a.send(batchKind, batch, seq, FakeAgent.CallSpec.defaults());
  }

  @Test
  @DisplayName(
      "11 §16 'status_codes' real equivalents: WAL full (DSN-11-09), back pressure, undurable write")
  void statusCodeEquivalents() {
    Replay replay = new Replay("status_codes", tls);
    FakeAgent a = replay.newAgent(replay.token.token(), 31);
    assertThat(a.connect().accepted()).isTrue();
    TransactionBatch three =
        a.batches()
            .transactions(
                List.of(
                    a.records().transaction().build(),
                    a.records().transaction().build(),
                    a.records().transaction().build()));
    tls.wal().full(true);
    CallResult full = a.sendTransactions(three);
    tls.wal().full(false);
    assertThat(full.ackStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(full.ack().getLastSeq()).isZero();
    assertThat(full.ack().getDroppedItems()).isEqualTo(3);
    CallResult resend = a.sendTransactions(three);
    assertThat(resend.ackStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack().getLastSeq()).isEqualTo(three.getHeader().getSeq());
    assertThat(resend.ack().getDroppedItems()).isZero();

    tls.wal().usage(0.95);
    CallResult hinted =
        a.sendTransactions(a.batches().transactions(List.of(a.records().transaction().build())));
    tls.wal().usage(0);
    assertThat(hinted.ackStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(hinted.ack().getThrottle().hasSendBackoffMs()).isTrue();
    assertThat(hinted.ack().getThrottle().hasProfileNormalRate()).isFalse();

    tls.wal().failing(true);
    CallResult down =
        a.sendTransactions(a.batches().transactions(List.of(a.records().transaction().build())));
    tls.wal().failing(false);
    assertThat(down.code()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  @DisplayName(
      "11 §16 'session revoked token' for real: WO-26's hook (cache invalidate + closeByToken) closes with TOKEN_REVOKED_CLOSE")
  @Tag("req:SEC-04")
  void revokedToken() throws Exception {
    Replay replay = new Replay("session revoked token", tls);
    FakeAgent a = replay.newAgent(replay.token.token(), 41);
    assertThat(a.connect().accepted()).isTrue();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE argos.agent_tokens SET status = 'REVOKED', revoked_at = now() WHERE id = ?")) {
      ps.setObject(1, replay.token.id());
      ps.executeUpdate();
    }
    tls.bean(TokenLookup.class).invalidate(TokenHash.of(replay.token.token()));
    assertThat(tls.bean(SessionRegistry.class).closeByToken(replay.token.id())).isEqualTo(1);
    HelloResult end = a.awaitSessionEnd(Duration.ofSeconds(10));
    assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(a.sessionClose().map(SessionClose::getReason))
        .contains(CloseReason.TOKEN_REVOKED_CLOSE);
    CallResult after =
        a.sendTransactions(a.batches().transactions(List.of(a.records().transaction().build())));
    assertThat(after.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(after.rejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED.name());
  }

  @Test
  @DisplayName(
      "11 §16 'session server shutdown' for real: SessionClose SERVER_SHUTDOWN retry 30, UNAVAILABLE, argos-retry-after-ms 30000 (12 §3.9)")
  void serverShutdown() {
    try (CollectorHarness own = CollectorHarness.start(env)) {
      String app = app();
      FakeAgent a = closing(own.agent(own.token(app, "prod").token()).appName(app).build());
      assertThat(a.connect().accepted()).isTrue();
      own.stop();
      HelloResult end = a.awaitSessionEnd(Duration.ofSeconds(15));
      assertThat(end.code()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(end.trailers().get(CallResult.RETRY_AFTER_MS)).isEqualTo("30000");
      SessionClose close = a.sessionClose().orElseThrow();
      assertThat(close.getReason()).isEqualTo(CloseReason.SERVER_SHUTDOWN);
      assertThat(close.getRetryAfterS()).isEqualTo(30);
    }
  }
}
