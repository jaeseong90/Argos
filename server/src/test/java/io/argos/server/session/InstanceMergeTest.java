/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.session.api.InstanceState;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The instance merge of 12 §4.3 / ADR-0010 C-158 (2) on the session stream: a temporary {@code
 * host:pid} instance renamed by {@code InstanceUpdate} to the name of an existing instance without
 * a live session is closed and archived, and the agent's reconnect (its Hello carries the new name)
 * continues as the existing instance.
 */
@Tag("req:C-158")
@Tag("req:F-S28")
class InstanceMergeTest {

  private final SessionTestKit kit = new SessionTestKit(Duration.ofSeconds(90));

  @AfterEach
  void close() {
    kit.close();
  }

  private static AgentMessage rename(String name) {
    return AgentMessage.newBuilder()
        .setInstanceUpdate(InstanceUpdate.newBuilder().setInstanceName(name))
        .build();
  }

  private static int accepted(RecordingObserver<ServerMessage> out) {
    ServerMessage first = out.messages.get(0);
    assertThat(first.getHello().getAccepted()).as(first.toString()).isTrue();
    return first.getHello().getInstanceId();
  }

  private static boolean closed(RecordingObserver<ServerMessage> out) {
    return out.messages.stream().anyMatch(ServerMessage::hasClose);
  }

  @Test
  @DisplayName(
      "C-158 (2) restart before the port is known: host:pid renamed to the old host:port (no live"
          + " session) closes the session, archives the temporary instance without a state event,"
          + " and the reconnect continues as the old instance id")
  void temporaryInstanceMerged() {
    kit.store.addInstance(kit.home.id(), "host-a:8080", Instant.parse("2026-11-01T00:00:00Z"));
    int old = kit.store.findInstance(kit.home.id(), "host-a:8080").getAsInt();

    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    call.onNext(Inbound.of(SessionTestKit.hello("host-a:4242", "host-a", 4242)));
    int temporary = accepted(out);
    assertThat(temporary).isNotEqualTo(old);
    kit.writer.flush();
    int eventsBefore = kit.stateStore.events.size();

    call.onNext(Inbound.of(rename("host-a:8080")));
    assertThat(closed(out)).isTrue();
    ServerMessage close = out.messages.stream().filter(ServerMessage::hasClose).findFirst().get();
    assertThat(close.getClose().getReason()).isEqualTo(CloseReason.CLOSE_REASON_UNSPECIFIED);
    assertThat(close.getClose().getRetryAfterS()).isZero();
    assertThat(out.completed).as("status OK: the agent reconnects after its backoff").isTrue();
    assertThat(kit.registry.find(temporary)).isEmpty();
    assertThat(kit.closed("instance_merged")).isEqualTo(1);
    assertThat(kit.tracker.state(temporary)).isEqualTo(InstanceState.DOWN);
    assertThat(kit.tracker.counts().values().stream().mapToInt(Integer::intValue).sum())
        .as("the temporary instance is no longer tracked")
        .isZero();
    kit.writer.flush();
    assertThat(kit.stateStore.archived).containsOnlyKeys(temporary);
    assertThat(kit.stateStore.events)
        .as("no state event: the temporary instance never went down")
        .hasSize(eventsBefore);

    // The agent reconnects: its Hello carries its current name.
    RecordingObserver<ServerMessage> again = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> next = kit.open(again);
    next.onNext(Inbound.of(SessionTestKit.hello("host-a:8080", "host-a", 4242)));
    assertThat(accepted(again)).isEqualTo(old);
    assertThat(kit.tracker.state(old)).isEqualTo(InstanceState.UP);
  }

  @Test
  @DisplayName(
      "C-158 (2) the old instance still has a live session (two JVMs of one name): no merge, the"
          + " name is kept off the row and both sessions stay")
  void liveOldInstanceNotMerged() {
    RecordingObserver<ServerMessage> first = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> a = kit.open(first);
    a.onNext(Inbound.of(SessionTestKit.hello("host-a:8080", "host-a", 1)));
    int old = accepted(first);

    RecordingObserver<ServerMessage> second = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> b = kit.open(second);
    b.onNext(Inbound.of(SessionTestKit.hello("host-a:4242", "host-a", 4242)));
    int temporary = accepted(second);
    b.onNext(Inbound.of(rename("host-a:8080")));

    assertThat(closed(second)).isFalse();
    assertThat(closed(first)).isFalse();
    assertThat(kit.registry.find(temporary)).isPresent();
    assertThat(kit.registry.find(old)).isPresent();
    assertThat(kit.closed("instance_merged")).isZero();
    kit.writer.flush();
    assertThat(kit.stateStore.archived).isEmpty();
  }

  @Test
  @DisplayName(
      "C-158 (2) a configured (not host:pid) instance name renamed to a taken name is not merged")
  void configuredNameNotMerged() {
    kit.store.addInstance(kit.home.id(), "host-a:8080", Instant.parse("2026-11-01T00:00:00Z"));
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    call.onNext(Inbound.of(SessionTestKit.hello("order-1", "host-a", 4242)));
    int id = accepted(out);
    call.onNext(Inbound.of(rename("host-a:8080")));
    assertThat(closed(out)).isFalse();
    assertThat(kit.registry.find(id)).isPresent();
  }

  @Test
  @DisplayName(
      "C-158 (2) a rename to a free name is an ordinary rename: same instance, no close (12 §4.3)")
  void freeNameIsRenamed() {
    RecordingObserver<ServerMessage> out = new RecordingObserver<>();
    StreamObserver<Inbound<AgentMessage>> call = kit.open(out);
    call.onNext(Inbound.of(SessionTestKit.hello("host-a:4242", "host-a", 4242)));
    int id = accepted(out);
    call.onNext(Inbound.of(rename("host-a:8080")));
    assertThat(closed(out)).isFalse();
    assertThat(kit.registry.find(id)).isPresent();
    assertThat(kit.store.findInstance(kit.home.id(), "host-a:8080")).hasValue(id);
  }
}
