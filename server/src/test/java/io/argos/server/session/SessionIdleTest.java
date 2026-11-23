/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.session.api.InstanceState;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The session idle timeout of ADR-0002 C-19 (11 open item 6): a session without any agent message
 * for 3 heartbeat intervals (90 s) is closed with {@code SessionClose{IDLE_TIMEOUT}} and removed,
 * on a fake monotonic clock; the instance state is left to the 12 §4.2 rules.
 */
@Tag("req:C-19")
@Tag("req:DSN-11-05")
class SessionIdleTest {

  private static final Duration IDLE = Duration.ofSeconds(90);

  private final SessionTestKit kit = new SessionTestKit(IDLE);

  @AfterEach
  void close() {
    kit.close();
  }

  private static AgentMessage heartbeat(long t1) {
    return AgentMessage.newBuilder().setHeartbeat(Heartbeat.newBuilder().setT1Ms(t1)).build();
  }

  private static Optional<SessionClose> closeOf(RecordingObserver<ServerMessage> out) {
    return out.messages.stream()
        .filter(ServerMessage::hasClose)
        .map(ServerMessage::getClose)
        .findFirst();
  }

  private int connect(
      StreamObserver<Inbound<AgentMessage>> call, RecordingObserver<ServerMessage> out) {
    call.onNext(Inbound.of(SessionTestKit.hello("host-a:8080", "host-a", 42)));
    ServerMessage hello = out.messages.get(0);
    assertThat(hello.getHello().getAccepted()).isTrue();
    return hello.getHello().getInstanceId();
  }

  @Test
  @DisplayName(
      "C-19 the idle timeout is 3 heartbeat intervals: 90 s with the default 30 s interval")
  void defaultTimeout() {
    assertThat(SessionHandler.DEFAULT_IDLE_TIMEOUT).isEqualTo(Duration.ofSeconds(90));
    assertThat(Duration.ofSeconds(30).multipliedBy(SessionSettings.IDLE_HEARTBEATS))
        .isEqualTo(SessionHandler.DEFAULT_IDLE_TIMEOUT);
    long last = 5_000_000_000L;
    assertThat(SessionHandler.idle(last, last + Duration.ofSeconds(89).toNanos(), IDLE)).isFalse();
    assertThat(SessionHandler.idle(last, last + IDLE.toNanos() - 1, IDLE)).isFalse();
    assertThat(SessionHandler.idle(last, last + IDLE.toNanos(), IDLE)).isTrue();
  }

  @Test
  @DisplayName(
      "C-19 no agent message for 89 s keeps the session; at 90 s it is closed with IDLE_TIMEOUT,"
          + " completed, removed from the registry and counted; the state rules take over")
  void idleBoundary() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    int id = connect(call, out);
    assertThat(kit.timer.periodic).hasSize(1);

    kit.advance(Duration.ofSeconds(89));
    kit.timer.runPeriodic();
    assertThat(closeOf(out)).isEmpty();
    assertThat(out.completed).isFalse();
    assertThat(kit.registry.find(id)).isPresent();

    kit.advance(Duration.ofSeconds(1));
    kit.timer.runPeriodic();
    SessionClose close = closeOf(out).orElseThrow();
    assertThat(close.getReason()).isEqualTo(CloseReason.IDLE_TIMEOUT);
    assertThat(close.getMessage()).contains("90 s");
    assertThat(out.completed).isTrue();
    assertThat(kit.registry.find(id)).isEmpty();
    assertThat(kit.closed("idle_timeout")).isEqualTo(1);
    assertThat(kit.tracker.state(id))
        .as("the server clock did not move: the 12 §4.2 silence rules decide, not the timeout")
        .isEqualTo(InstanceState.UP);

    // A later check of the ended stream does nothing more.
    kit.advance(Duration.ofSeconds(90));
    kit.timer.runPeriodic();
    assertThat(out.messages.stream().filter(ServerMessage::hasClose)).hasSize(1);
    assertThat(kit.closed("idle_timeout")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "C-19 every session message restarts the 90 s: a heartbeat at 60 s keeps the session until"
          + " 149 s and closes it at 150 s")
  void heartbeatRestarts() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    int id = connect(call, out);
    kit.advance(Duration.ofSeconds(60));
    call.onNext(Inbound.of(heartbeat(kit.clock.millis())));
    kit.advance(Duration.ofSeconds(89));
    kit.timer.runPeriodic();
    assertThat(closeOf(out)).isEmpty();
    assertThat(kit.registry.find(id)).isPresent();
    kit.advance(Duration.ofSeconds(1));
    kit.timer.runPeriodic();
    assertThat(closeOf(out).orElseThrow().getReason()).isEqualTo(CloseReason.IDLE_TIMEOUT);
    assertThat(kit.registry.find(id)).isEmpty();
  }

  @Test
  @DisplayName(
      "C-19 an accepted data call of the session is agent activity (the collector marks it): the"
          + " 90 s restart from the data call")
  void dataCallRestarts() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    int id = connect(call, out);
    kit.advance(Duration.ofSeconds(80));
    AgentSession session = kit.registry.find(id).orElseThrow();
    session.markActive(kit.ticker.get());
    kit.advance(Duration.ofSeconds(89));
    kit.timer.runPeriodic();
    assertThat(closeOf(out)).isEmpty();
    kit.advance(Duration.ofSeconds(1));
    kit.timer.runPeriodic();
    assertThat(closeOf(out).orElseThrow().getReason()).isEqualTo(CloseReason.IDLE_TIMEOUT);
  }

  @Test
  @DisplayName(
      "C-19 the timeout runs on a monotonic clock: a wall clock step of an hour closes nothing")
  void wallClockStepIgnored() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    int id = connect(call, out);
    kit.clock.advance(Duration.ofHours(1));
    kit.advance(Duration.ofSeconds(10));
    kit.timer.runPeriodic();
    assertThat(closeOf(out)).isEmpty();
    assertThat(kit.registry.find(id)).isPresent();
  }

  @Test
  @DisplayName("C-19 a session cancelled by the agent stops its idle check (nothing sent later)")
  void cancelledSessionNotClosedAgain() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    connect(call, out);
    out.cancelled = true;
    out.onCancel.run();
    kit.advance(Duration.ofMinutes(2));
    kit.timer.runPeriodic();
    assertThat(closeOf(out)).isEmpty();
    assertThat(kit.closed("idle_timeout")).isZero();
  }
}
