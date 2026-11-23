/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The {@code AgentBye} grace of 12 §4.2 (S-17) on a fake clock: a reconnect after 4:59 never makes
 * the instance DOWN (no DOWN transition, so no down notification), 5:00 without one does; the Bye
 * itself is an instance {@code stop} event.
 */
@Tag("req:F-S28")
@Tag("req:F-S25")
@Tag("req:S-17")
class ByeGraceTest {

  private final TrackerFixture f = new TrackerFixture();

  @AfterEach
  void close() {
    f.close();
  }

  @Test
  @DisplayName(
      "T-SV-023 Bye, reconnect after 4:59: no DOWN transition and no down event, the instance stays UP")
  void reconnectInsideGrace() {
    AgentSession first = f.connect(f.session(11, 1, "1.4.0", "0123456789abcdef"));
    assertThat(f.tracker.state(11)).isEqualTo(InstanceState.UP);
    f.tracker.onBye(first, ByeReason.SHUTDOWN);
    f.registry.unregister(first);
    f.tracker.onSessionEnd(first);
    for (int s = 10; s <= 290; s += 10) {
      f.clock.advance(Duration.ofSeconds(10));
      f.tracker.tick();
    }
    f.clock.advance(Duration.ofSeconds(9));
    f.tracker.tick();
    assertThat(f.tracker.state(11)).as("4:59 after the Bye").isEqualTo(InstanceState.UP);
    AgentSession second = f.connect(f.session(11, 2, "1.4.0", "0123456789abcdef"));
    f.tracker.onHeartbeat(second, f.clock.millis(), 0);
    for (int s = 0; s < 6; s++) {
      f.clock.advance(Duration.ofSeconds(10));
      f.tracker.onHeartbeat(second, f.clock.millis(), 0);
      f.tracker.tick();
    }
    assertThat(f.tracker.state(11)).isEqualTo(InstanceState.UP);
    assertThat(f.changes)
        .extracting(InstanceStateChange::to)
        .as("only the first Hello's DOWN -> UP")
        .containsExactly(InstanceState.UP);
    List<EventRow> events = f.events();
    assertThat(events).filteredOn(e -> e.type().equals("stop")).hasSize(1);
    EventRow stop = events.stream().filter(e -> e.type().equals("stop")).findFirst().orElseThrow();
    assertThat(stop.instanceId()).isEqualTo(11);
    assertThat(stop.appId()).isEqualTo(7);
    assertThat(stop.detailJson()).contains("\"reason\":\"shutdown\"");
    assertThat(events)
        .filteredOn(e -> e.type().equals("state"))
        .extracting(EventRow::detailJson)
        .noneMatch(d -> d.contains("\"to\":\"DOWN\""))
        .noneMatch(d -> d.contains("\"to\":\"STALE\""));
  }

  @Test
  @DisplayName("T-SV-023 Bye and no reconnect: DOWN at 5:00 with reason agent_bye, never STALE")
  void downAtFiveMinutes() {
    AgentSession first = f.connect(f.session(12, 1, "1.4.0", "0123456789abcdef"));
    f.tracker.onBye(first, ByeReason.SHUTDOWN);
    f.registry.unregister(first);
    f.clock.advance(Duration.ofSeconds(299));
    f.tracker.tick();
    assertThat(f.tracker.state(12)).isEqualTo(InstanceState.UP);
    f.clock.advance(Duration.ofSeconds(1));
    f.tracker.tick();
    assertThat(f.tracker.state(12)).isEqualTo(InstanceState.DOWN);
    InstanceStateChange down = f.changes.get(f.changes.size() - 1);
    assertThat(down.from()).isEqualTo(InstanceState.UP);
    assertThat(down.to()).isEqualTo(InstanceState.DOWN);
    assertThat(down.reasons()).containsExactly(InstanceStateMachine.AGENT_BYE);
    assertThat(down.instanceId()).isEqualTo(12);
    assertThat(down.appId()).isEqualTo(7);
    assertThat(down.env()).isEqualTo("prod");
    assertThat(f.changes).extracting(InstanceStateChange::to).doesNotContain(InstanceState.STALE);
    assertThat(f.events())
        .filteredOn(e -> e.type().equals("state"))
        .extracting(EventRow::detailJson)
        .anyMatch(
            d ->
                d.contains("\"from\":\"UP\"")
                    && d.contains("\"to\":\"DOWN\"")
                    && d.contains("\"reason\":\"agent_bye\""));
  }

  @Test
  @DisplayName(
      "T-SV-023 a heartbeat after the Bye ends the grace: the silence rules apply again (STALE at 60 s)")
  void heartbeatEndsGrace() {
    AgentSession s = f.connect(f.session(13, 1, "1.4.0", "0123456789abcdef"));
    f.tracker.onBye(s, ByeReason.SHUTDOWN);
    f.tracker.onHeartbeat(s, f.clock.millis(), 0);
    f.registry.unregister(s);
    f.clock.advance(Duration.ofMinutes(1));
    f.tracker.tick();
    assertThat(f.tracker.state(13)).isEqualTo(InstanceState.STALE);
  }
}
