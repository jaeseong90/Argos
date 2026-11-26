/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.session.api.InstanceState;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A check-only Hello on the session stream ({@code AgentHello.check_only}, the agent's {@code
 * --check}, ADR-0010 C-240 (1)): answered, its stream completed at once, and nothing else happens:
 * no session in the registry, no instance row, no instance state, no state or start event, no idle
 * timer; a later Bye on the ended stream changes nothing.
 */
@Tag("req:C-240")
@Tag("req:F-A27")
class CheckOnlySessionTest {

  private final SessionTestKit kit = new SessionTestKit(SessionHandler.DEFAULT_IDLE_TIMEOUT);

  @AfterEach
  void close() {
    kit.close();
  }

  private static AgentMessage checkHello() {
    AgentMessage hello = SessionTestKit.hello("host-a-check", "host-a", 42);
    return hello.toBuilder()
        .setHello(hello.getHello().toBuilder().setCheckOnly(true).setProcessStartMs(1_000))
        .build();
  }

  @Test
  @DisplayName(
      "T-SV-022b the stream of a check-only Hello: ServerHello accepted (instance 0, no session"
          + " id), completed OK, counted; no registry entry, instance, state, event or idle timer")
  void answeredAndEnded() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    call.onNext(Inbound.of(checkHello()));
    assertThat(out.messages).hasSize(1);
    ServerMessage answer = out.messages.get(0);
    assertThat(answer.getHello().getAccepted()).isTrue();
    assertThat(answer.getHello().getInstanceId()).isZero();
    assertThat(answer.getHello().getSessionId()).isEmpty();
    assertThat(answer.getHello().getAppId()).isEqualTo(kit.home.id());
    assertThat(out.completed).as("the stream ends after the answer").isTrue();
    assertThat(kit.registry.size()).isZero();
    assertThat(kit.store.instances).isEmpty();
    assertThat(kit.timer.periodic).as("no idle timer").isEmpty();
    assertThat(kit.tracker.counts().values()).allMatch(n -> n == 0);
    kit.writer.flush();
    assertThat(kit.stateStore.events).isEmpty();
    assertThat(kit.stateStore.rows).isEmpty();
    assertThat(
            kit.meters
                .get("argos.collector.session.messages")
                .tag("type", "hello_check_only")
                .counter()
                .count())
        .isEqualTo(1);

    // The agent's half-close (and a Bye an agent might still send) on the ended stream: nothing.
    call.onNext(
        Inbound.of(
            AgentMessage.newBuilder()
                .setBye(io.argos.proto.v1.AgentBye.newBuilder().setReason(ByeReason.SHUTDOWN))
                .build()));
    call.onCompleted();
    kit.writer.flush();
    assertThat(kit.stateStore.events).isEmpty();
    assertThat(out.messages).hasSize(1);
  }

  @Test
  @DisplayName(
      "T-SV-022b a check-only Hello while the instance has a live session leaves that session and"
          + " its state alone (no REPLACED, no start event, no transition)")
  void liveSessionUntouched() {
    RecordingObserver<ServerMessage> agentOut = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> agent = kit.open(agentOut);
    agent.onNext(Inbound.of(SessionTestKit.hello("host-a-check", "host-a", 42)));
    int id = agentOut.messages.get(0).getHello().getInstanceId();
    assertThat(kit.tracker.state(id)).isEqualTo(InstanceState.UP);
    kit.writer.flush();
    int events = kit.stateStore.events.size();

    RecordingObserver<ServerMessage> checkOut = new RecordingObserver<>();
    kit.open(checkOut).onNext(Inbound.of(checkHello()));
    kit.advance(Duration.ofSeconds(1));
    assertThat(checkOut.completed).isTrue();
    assertThat(agentOut.messages).as("no SessionClose REPLACED").hasSize(1);
    assertThat(agentOut.completed).isFalse();
    assertThat(kit.registry.find(id)).isPresent();
    assertThat(kit.tracker.state(id)).isEqualTo(InstanceState.UP);
    kit.writer.flush();
    assertThat(kit.stateStore.events).hasSize(events);
  }
}
