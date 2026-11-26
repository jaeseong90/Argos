/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * The instance state machine of 12 §4.2 over the whole server (gRPC session, the monitor, the
 * PostgreSQL writer, the query API) on real PostgreSQL and ClickHouse, with the server clock moved
 * by hand ({@link MutableClock}; the fake agents' clocks follow it, so no clock skew): {@code
 * instances.status}, {@code events} rows {@code type = 'state'}, the REST instance state, {@code
 * argos_instances{state}} and the {@code HeartbeatAck} state; the {@code AgentBye} grace (S-17).
 */
@Tag("integration")
@Tag("req:F-S28")
class InstanceStateIT {

  private static final MutableClock CLOCK = new MutableClock();
  private static final List<InstanceStateChange> CHANGES = new CopyOnWriteArrayList<>();
  private static StateStack stack;

  @BeforeAll
  static void start() {
    stack = StateStack.start(CLOCK);
    stack
        .server
        .context()
        .addApplicationListener(
            (ApplicationListener<PayloadApplicationEvent<InstanceStateChange>>)
                e -> CHANGES.add(e.getPayload()));
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  private static double gauge(String state) {
    return stack
        .server
        .bean(MeterRegistry.class)
        .get("argos.instances")
        .tag("state", state)
        .gauge()
        .value();
  }

  @Test
  @DisplayName(
      "T-SV-022 heartbeats stop: STALE at 60 s, DOWN at 180 s (instances.status, state events with"
          + " session_lost, REST state, argos_instances); a heartbeat makes it UP again")
  void silence() throws Exception {
    AgentTokens.IssuedToken token = stack.token("state-silence");
    try (FakeAgent a = stack.agent(token, "state-silence", "host-a:8080", Duration.ZERO)) {
      assertThat(a.connect().accepted()).isTrue();
      int id = a.instanceId();
      HeartbeatAck ack = a.heartbeatRound(Duration.ofSeconds(5)).orElseThrow();
      assertThat(ack.getState()).isEqualTo(io.argos.proto.v1.InstanceState.UP);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("UP");
      assertThat(stack.stored(id).lastSeenAt()).isNotNull();
      assertThat(stack.restState(token.appId(), id)).isEqualTo("UP");
      double upBefore = gauge("UP");
      assertThat(upBefore).isGreaterThanOrEqualTo(1);

      CLOCK.advance(Duration.ofSeconds(59));
      stack.tick();
      assertThat(stack.state(id)).isEqualTo(InstanceState.UP);
      CLOCK.advance(Duration.ofSeconds(1));
      stack.tick();
      assertThat(stack.state(id)).isEqualTo(InstanceState.STALE);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("STALE");
      assertThat(stack.restState(token.appId(), id)).isEqualTo("STALE");
      InstanceStateTracker tracker = stack.server.bean(InstanceStateTracker.class);
      assertThat(gauge("STALE"))
          .isGreaterThanOrEqualTo(1)
          .isEqualTo((double) tracker.counts().get(InstanceState.STALE));
      assertThat(gauge("UP")).isEqualTo((double) tracker.counts().get(InstanceState.UP));

      CLOCK.advance(Duration.ofSeconds(119));
      stack.tick();
      assertThat(stack.state(id)).isEqualTo(InstanceState.STALE);
      CLOCK.advance(Duration.ofSeconds(1));
      stack.tick();
      assertThat(stack.state(id)).isEqualTo(InstanceState.DOWN);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("DOWN");
      assertThat(stack.restState(token.appId(), id)).isEqualTo("DOWN");

      HeartbeatAck back = a.heartbeatRound(Duration.ofSeconds(5)).orElseThrow();
      assertThat(back.getState()).isEqualTo(io.argos.proto.v1.InstanceState.UP);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("UP");
      List<StateStack.Event> all = stack.events(id);
      assertThat(all)
          .as(
              "one agent start for the agent process (F-A26, ADR-0010 C-240 (1)), the rest state"
                  + " rows")
          .filteredOn(e -> !e.type().equals("state"))
          .extracting(StateStack.Event::type)
          .containsExactly("start");
      List<StateStack.Event> events = all.stream().filter(e -> e.type().equals("state")).toList();
      assertThat(events).allMatch(e -> e.type().equals("state"));
      assertThat(events)
          .extracting(e -> e.from() + ">" + e.to() + ":" + e.reason())
          .containsExactly(
              "DOWN>UP:recovered",
              "UP>STALE:session_lost",
              "STALE>DOWN:session_lost",
              "DOWN>UP:recovered");
      assertThat(events).allMatch(e -> e.appId() == token.appId() && "prod".equals(e.env()));
      assertThat(events.get(1).ts()).isEqualTo(events.get(0).ts().plusSeconds(60));
      assertThat(CHANGES)
          .filteredOn(c -> c.instanceId() == id)
          .extracting(InstanceStateChange::to)
          .containsExactly(
              InstanceState.UP, InstanceState.STALE, InstanceState.DOWN, InstanceState.UP);
    }
  }

  @Test
  @DisplayName(
      "T-SV-022 KILL_SWITCH_ON makes the instance WARN kill_switch (warn_flags, REST,"
          + " HeartbeatAck), KILL_SWITCH_OFF makes it UP")
  void warnFromAgentEvent() throws Exception {
    AgentTokens.IssuedToken token = stack.token("state-warn");
    try (FakeAgent a = stack.agent(token, "state-warn", "host-w:8080", Duration.ZERO)) {
      assertThat(a.connect().accepted()).isTrue();
      int id = a.instanceId();
      a.sendEvent(AgentEvent.newBuilder().setType(AgentEventType.KILL_SWITCH_ON).build());
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.state(id) == InstanceState.WARN);
      assertThat(a.heartbeatRound(Duration.ofSeconds(5)).orElseThrow().getState())
          .isEqualTo(io.argos.proto.v1.InstanceState.WARN);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("WARN");
      assertThat(stack.stored(id).warnFlags()).containsExactly("kill_switch");
      assertThat(stack.restState(token.appId(), id)).isEqualTo("WARN");
      assertThat(gauge("WARN")).isGreaterThanOrEqualTo(1);
      a.sendEvent(AgentEvent.newBuilder().setType(AgentEventType.KILL_SWITCH_OFF).build());
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.state(id) == InstanceState.UP);
      stack.flush();
      assertThat(stack.stored(id).status()).isEqualTo("UP");
      assertThat(stack.stored(id).warnFlags()).isEmpty();
      assertThat(stack.events(id))
          .filteredOn(e -> e.type().equals("state"))
          .extracting(e -> e.from() + ">" + e.to())
          .containsExactly("DOWN>UP", "UP>WARN", "WARN>UP");
      assertThat(stack.events(id))
          .filteredOn(e -> !e.type().equals("state"))
          .extracting(StateStack.Event::type)
          .containsExactly("start");
    }
  }

  @Test
  @DisplayName(
      "T-SV-023 AgentBye then a reconnect after 4:59: no DOWN (no state event to DOWN, no down"
          + " transition); without a reconnect DOWN at 5:00 (agent_bye)")
  @Tag("req:F-S25")
  @Tag("req:S-17")
  void byeGrace() throws Exception {
    AgentTokens.IssuedToken token = stack.token("state-bye");
    int id;
    try (FakeAgent a = stack.agent(token, "state-bye", "host-b:8080", Duration.ZERO)) {
      assertThat(a.connect().accepted()).isTrue();
      id = a.instanceId();
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      a.sendBye(ByeReason.SHUTDOWN);
      a.completeSession();
      Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> stopEvents(id) == 1);
      a.cancelSession();
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.server.bean(SessionRegistry.class).find(id).isEmpty());
    }
    for (int s = 0; s < 29; s++) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
    }
    CLOCK.advance(Duration.ofSeconds(9));
    stack.tick();
    assertThat(stack.state(id)).as("4:59 after the Bye").isEqualTo(InstanceState.UP);
    try (FakeAgent again = stack.agent(token, "state-bye", "host-b:8080", Duration.ZERO)) {
      assertThat(again.connect().accepted()).isTrue();
      assertThat(again.instanceId()).isEqualTo(id);
      for (int s = 0; s < 4; s++) {
        CLOCK.advance(Duration.ofSeconds(30));
        assertThat(again.heartbeatRound(Duration.ofSeconds(5))).isPresent();
        stack.tick();
      }
      assertThat(stack.state(id)).isEqualTo(InstanceState.UP);
      stack.flush();
      assertThat(stack.events(id))
          .as("no DOWN, no STALE across the restart")
          .noneMatch(e -> "DOWN".equals(e.to()) || "STALE".equals(e.to()));
      assertThat(CHANGES)
          .filteredOn(c -> c.instanceId() == id)
          .extracting(InstanceStateChange::to)
          .containsExactly(InstanceState.UP);

      again.sendBye(ByeReason.SHUTDOWN);
      Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> stopEvents(id) == 2);
      again.cancelSession();
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.server.bean(SessionRegistry.class).find(id).isEmpty());
    }
    CLOCK.advance(Duration.ofSeconds(299));
    stack.tick();
    assertThat(stack.state(id)).isEqualTo(InstanceState.UP);
    CLOCK.advance(Duration.ofSeconds(1));
    stack.tick();
    assertThat(stack.state(id)).isEqualTo(InstanceState.DOWN);
    stack.flush();
    assertThat(stack.stored(id).status()).isEqualTo("DOWN");
    assertThat(stack.events(id))
        .filteredOn(e -> e.type().equals("state"))
        .extracting(e -> e.from() + ">" + e.to() + ":" + e.reason())
        .containsExactly("DOWN>UP:recovered", "UP>DOWN:agent_bye");
  }

  private static int stopEvents(int instanceId) throws Exception {
    stack.flush();
    return (int) stack.events(instanceId).stream().filter(e -> e.type().equals("stop")).count();
  }
}
