/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.UserIdMode;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.CollectorState;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.collector.limits.CollectorLimits;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.testing.auth.MutableClock;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The session stream of {@link SessionHandler} on a recorded server stream: heartbeat answers
 * respect the transport's readiness (no buffering for an agent that stops reading, SEC-11), and an
 * unanswered heartbeat completes no clock round (11 §9, the contract vectors).
 */
@Tag("req:SEC-11")
@Tag("req:DSN-11-05")
class SessionHandlerTest {

  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CollectorMetrics metrics = new CollectorMetrics(meters);
  private final InMemorySessionStore store = new InMemorySessionStore(clock);

  /** The token's app (its home app, ADR-0010 C-155): it exists before any Hello of its token. */
  private final AppRef home = store.addApp("order-api", "prod");

  private final InMemorySessionRegistry registry = new InMemorySessionRegistry();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final TokenRecord token =
      new TokenRecord(
          UUID.randomUUID(),
          home.id(),
          "order-api",
          "prod",
          "argos_AbCdEfGh",
          TokenStatus.ACTIVE,
          null,
          null,
          null);

  @AfterEach
  void stopTimer() {
    timer.shutdownNow();
  }

  private SessionHandler handler() {
    LimitsChecker limits = new LimitsChecker(CollectorLimits.defaults());
    HelloService hellos =
        new HelloService(
            store,
            registry,
            new SessionSettings(
                false,
                Map.of("default", true),
                100,
                60,
                1_000,
                100,
                Duration.ofSeconds(30),
                CollectorLimits.defaults().toProto(),
                SessionSettings.HELLO_TIMEOUT),
            new AgentVersionRange("1.4.0"),
            limits,
            new CollectorState(),
            metrics,
            clock);
    return new SessionHandler(
        hellos,
        registry,
        store,
        limits,
        (appId, instanceId, entries) -> {},
        metrics,
        clock,
        timer,
        SessionSettings.HELLO_TIMEOUT);
  }

  private StreamObserver<Inbound<AgentMessage>> open(RecordingObserver<ServerMessage> out) {
    AuthResult auth =
        new AuthResult(AuthResult.Outcome.ACCEPTED, TokenVerdict.ACCEPTED, token, "argos_AbCdEfGh");
    Context context = Context.current().withValue(CollectorCalls.AUTH, auth);
    Context previous = context.attach();
    try {
      return handler().invoke(out);
    } finally {
      context.detach(previous);
    }
  }

  private static AgentMessage hello(long sentAtMs) {
    return AgentMessage.newBuilder()
        .setHello(
            AgentHello.newBuilder()
                .setAgentVersion("1.4.0")
                .setProtocolVersion(ProtocolVersion.CURRENT)
                .setAppName("order-api")
                .setEnv("prod")
                .setInstanceName("host-a:8080")
                .setHost("host-a")
                .setPid(42)
                .setIpMode(IpMode.IP_MASKED)
                .setUserIdMode(UserIdMode.USER_ID_OFF)
                .setQueryStringMode(QueryStringMode.QS_OFF)
                .setSqlParamMode(SqlParamMode.SQL_PARAM_MASKED)
                .setAgentEpoch(77)
                .setSentAtMs(sentAtMs))
        .build();
  }

  private static AgentMessage heartbeat(long t1, long prevT1, long prevT4) {
    return AgentMessage.newBuilder()
        .setHeartbeat(Heartbeat.newBuilder().setT1Ms(t1).setPrevT1Ms(prevT1).setPrevT4Ms(prevT4))
        .build();
  }

  private static List<HeartbeatAck> acks(RecordingObserver<ServerMessage> out) {
    return out.messages().stream()
        .filter(ServerMessage::hasHeartbeatAck)
        .map(ServerMessage::getHeartbeatAck)
        .toList();
  }

  private double skipped() {
    var c =
        meters
            .find("argos.collector.session.messages")
            .tag("type", "heartbeat_ack_skipped")
            .counter();
    return c == null ? 0 : c.count();
  }

  @Test
  @DisplayName(
      "SEC-11 a heartbeat on a stream that is not ready gets no queued HeartbeatAck (counted); the"
          + " next ready heartbeat is answered, and the skipped t1 completes no clock round")
  void heartbeatAckOnlyWhenReady() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = open(out);
    // Hello: the agent's clock is 2,000 ms behind the server (first estimate +2,000).
    call.onNext(Inbound.of(hello(clock.millis() - 2_000)));
    assertThat(out.messages()).hasSize(1);
    assertThat(out.messages().get(0).getHello().getAccepted()).isTrue();
    AgentSession session =
        registry.find(out.messages().get(0).getHello().getInstanceId()).orElseThrow();
    long helloEstimate = session.clock().estimateMs();
    assertThat(helloEstimate).isEqualTo(2_000);

    // The agent stops reading: nothing is queued for it, however many heartbeats arrive.
    out.ready = false;
    long agentNow = clock.millis() - 2_000;
    for (int i = 0; i < 50; i++) {
      call.onNext(Inbound.of(heartbeat(agentNow + i, 0, 0)));
    }
    assertThat(acks(out)).isEmpty();
    assertThat(skipped()).isEqualTo(50);

    // Ready again: a heartbeat whose prev_t1 was never acknowledged is answered but completes no
    // round (the estimate stays the Hello estimate even though its times would say +5,000).
    out.ready = true;
    clock.advance(Duration.ofSeconds(30));
    long t1 = clock.millis() - 5_000;
    call.onNext(Inbound.of(heartbeat(t1, agentNow + 49, agentNow + 60)));
    assertThat(acks(out)).hasSize(1);
    HeartbeatAck ack = acks(out).get(0);
    assertThat(ack.getT1Ms()).isEqualTo(t1);
    assertThat(ack.getClockOffsetMs()).isEqualTo(2_000);
    assertThat(session.clock().estimateMs()).isEqualTo(helloEstimate);

    // The acknowledged t1 completes the next round.
    clock.advance(Duration.ofSeconds(30));
    long t4 = ack.getT3Ms() - 5_000;
    call.onNext(Inbound.of(heartbeat(clock.millis() - 5_000, t1, t4)));
    assertThat(acks(out)).hasSize(2);
    assertThat(session.clock().estimateMs()).isEqualTo(5_000);
    assertThat(skipped()).isEqualTo(50);
  }

  private static AgentMessage update(int connectors, String appVersion) {
    InstanceUpdate.Builder u = InstanceUpdate.newBuilder().setAppVersion(appVersion);
    for (int i = 0; i < connectors; i++) {
      u.addConnectors("http-nio-" + i);
    }
    return AgentMessage.newBuilder().setInstanceUpdate(u).build();
  }

  private double rejected(String reason) {
    var c = meters.find("argos.collector.rejected").tag("reason", reason).counter();
    return c == null ? 0 : c.count();
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (2) an InstanceUpdate with 257 connectors is dropped and counted as repeated_length"
          + " (the session goes on); one with exactly 256 is applied")
  void instanceUpdateConnectorCap() {
    assertThat(LimitsChecker.MAX_CONNECTORS).isEqualTo(256);
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = open(out);
    call.onNext(Inbound.of(hello(clock.millis())));
    AgentSession session =
        registry.find(out.messages().get(0).getHello().getInstanceId()).orElseThrow();

    call.onNext(Inbound.of(update(257, "2.0.0")));
    assertThat(rejected("repeated_length")).isEqualTo(1);
    assertThat(store.updateWrites).as("the violating update is not applied").isZero();
    assertThat(session.appVersion()).isNotEqualTo("2.0.0");
    assertThat(out.status).as("the session stream stays open").isNull();

    call.onNext(Inbound.of(update(256, "2.0.0")));
    assertThat(rejected("repeated_length")).isEqualTo(1);
    assertThat(store.updateWrites).isEqualTo(1);
    assertThat(session.appVersion()).isEqualTo("2.0.0");
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (4) an InstanceUpdate rename to a name above 128 characters stores the 119-character"
          + " prefix, '~' and the XXH3 suffix, not a cut name")
  void instanceUpdateLongName() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = open(out);
    call.onNext(Inbound.of(hello(clock.millis())));
    AgentSession session =
        registry.find(out.messages().get(0).getHello().getInstanceId()).orElseThrow();
    String longName = "n".repeat(128) + "-tail";
    call.onNext(
        Inbound.of(
            AgentMessage.newBuilder()
                .setInstanceUpdate(InstanceUpdate.newBuilder().setInstanceName(longName))
                .build()));
    assertThat(session.instanceName())
        .hasSize(128)
        .isEqualTo(SessionStore.storedInstanceName(longName))
        .startsWith("n".repeat(119) + "~")
        .isNotEqualTo("n".repeat(128));
  }
}
