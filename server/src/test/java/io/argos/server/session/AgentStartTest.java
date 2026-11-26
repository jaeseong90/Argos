/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.InstanceStateStore.OpenInstance;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The agent start event of {@link InstanceStateTracker} (F-A26, S-17, ADR-0010 C-240 (1)) on a fake
 * clock: one {@code events} row {@code type = 'start'} per agent process ({@code agent_epoch}),
 * none for a reconnect of the same process, none for the same process after a server restart (the
 * stored {@code started_at} equals the Hello's {@code process_start_ms}), and no state transition
 * from it.
 */
@Tag("req:F-A26")
@Tag("req:S-17")
@Tag("req:C-240")
class AgentStartTest {

  private TrackerFixture f = new TrackerFixture();

  @AfterEach
  void close() {
    f.close();
  }

  private AgentSession session(int instance, long epoch, long processStart, long storedStart) {
    AgentSession s = f.session(instance, epoch, "1.4.0", "0123456789abcdef");
    s.startFacts(processStart, storedStart);
    return s;
  }

  private List<EventRow> starts(int instance) {
    return f.events().stream()
        .filter(e -> e.type().equals("start") && e.instanceId() == instance)
        .toList();
  }

  @Test
  @DisplayName(
      "T-SV-023b a new instance writes one start event (title, agent_version, app, env, time);"
          + " heartbeats and a reconnect of the same process write none")
  void newProcessOnce() {
    AgentSession s = f.connect(session(31, 7, 1_000, AgentSession.STARTED_NEW_INSTANCE));
    f.tracker.onHeartbeat(s, f.clock.millis(), 0);
    f.tracker.tick();
    List<EventRow> starts = starts(31);
    assertThat(starts).hasSize(1);
    EventRow start = starts.get(0);
    assertThat(start.title()).isEqualTo("Agent start");
    assertThat(start.detailJson()).isEqualTo("{\"agent_version\":\"1.4.0\"}");
    assertThat(start.appId()).isEqualTo(7);
    assertThat(start.env()).isEqualTo("prod");
    assertThat(start.ts().toEpochMilli()).isEqualTo(f.clock.millis());

    // The same process reconnects (network blip): same epoch, the stored start is its own.
    f.registry.unregister(s);
    f.tracker.onSessionEnd(s);
    f.clock.advance(Duration.ofSeconds(20));
    AgentSession again = f.connect(session(31, 7, 1_000, 1_000));
    assertThat(starts(31)).as("a reconnect is no start").hasSize(1);
    // Even when the instance went DOWN and stopped being tracked meanwhile.
    f.registry.unregister(again);
    f.tracker.onSessionEnd(again);
    f.clock.advance(Duration.ofMinutes(5));
    f.tracker.tick();
    assertThat(f.tracker.state(31)).isEqualTo(InstanceState.DOWN);
    assertThat(f.tracker.counts().get(InstanceState.DOWN)).as("no longer tracked").isZero();
    f.connect(session(31, 7, 1_000, 1_000));
    assertThat(starts(31)).hasSize(1);
    assertThat(f.events())
        .filteredOn(e -> e.type().equals("state"))
        .as("the start rows are no state rows")
        .allMatch(e -> e.detailJson().contains("\"to\""));
  }

  @Test
  @DisplayName(
      "T-SV-023b Bye then a restarted process: stop then start; the new epoch inside the Bye grace"
          + " is no DOWN")
  void byeThenRestart() {
    AgentSession first = f.connect(session(32, 1, 1_000, AgentSession.STARTED_NEW_INSTANCE));
    f.tracker.onBye(first, ByeReason.SHUTDOWN);
    f.registry.unregister(first);
    f.tracker.onSessionEnd(first);
    f.clock.advance(Duration.ofSeconds(30));
    f.tracker.tick();
    f.connect(session(32, 2, 31_000, 1_000));
    assertThat(f.events())
        .filteredOn(e -> e.instanceId() == 32 && !e.type().equals("state"))
        .extracting(EventRow::type)
        .containsExactly("start", "stop", "start");
    assertThat(f.tracker.state(32)).isEqualTo(InstanceState.UP);
    assertThat(f.changes)
        .filteredOn(c -> c.instanceId() == 32)
        .extracting(InstanceStateChange::to)
        .containsExactly(InstanceState.UP);
  }

  @Test
  @DisplayName(
      "T-SV-023b a tracked instance whose Hello has another epoch is a new process, whatever the"
          + " stored start says")
  void trackedNewEpoch() {
    f.connect(session(33, 1, 1_000, AgentSession.STARTED_NEW_INSTANCE));
    f.connect(session(33, 2, 0, AgentSession.STARTED_UNKNOWN));
    assertThat(starts(33)).hasSize(2);
  }

  @Test
  @DisplayName(
      "T-SV-023b after a server restart the same process reconnecting writes no start (stored"
          + " start = process start); a process that restarted meanwhile writes one; unknown stored"
          + " start or no process start writes none")
  void serverRestart() {
    f.connect(session(34, 5, 1_000, AgentSession.STARTED_NEW_INSTANCE));
    f.connect(session(35, 6, 2_000, AgentSession.STARTED_NEW_INSTANCE));
    assertThat(starts(34)).hasSize(1);
    f.close();

    // A new server: nothing tracked, no epoch seen; the instances it left UP are restored.
    f = new TrackerFixture();
    f.tracker.restore(
        List.of(
            new OpenInstance(34, 7, "prod", "UP", List.of(), null),
            new OpenInstance(35, 7, "prod", "UP", List.of(), null)),
        f.clock.millis());
    f.connect(session(34, 5, 1_000, 1_000));
    assertThat(starts(34)).as("the same process after a server restart").isEmpty();
    f.connect(session(35, 9, 7_000, 2_000));
    assertThat(starts(35)).as("restarted while the server was down").hasSize(1);
    f.connect(session(36, 10, 3_000, AgentSession.STARTED_UNKNOWN));
    f.connect(session(37, 11, 0, 2_000));
    assertThat(starts(36)).as("stored start unknown (PostgreSQL down)").isEmpty();
    assertThat(starts(37)).as("no process start reported").isEmpty();
    f.connect(session(38, 12, 4_000, AgentSession.STARTED_NONE));
    assertThat(starts(38)).as("an instance row without a stored start").hasSize(1);
    assertThat(f.changes)
        .filteredOn(c -> c.instanceId() == 34)
        .as("a restored UP instance reconnecting makes no transition")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-023b the rule: new instance, tracked epoch, stored start against process start")
  void rule() {
    AgentSession s = session(40, 3, 5_000, AgentSession.STARTED_NEW_INSTANCE);
    assertThat(InstanceStateTracker.newProcess(Long.MIN_VALUE, s)).isTrue();
    assertThat(InstanceStateTracker.newProcess(3, session(40, 3, 5_000, 5_000))).isFalse();
    assertThat(InstanceStateTracker.newProcess(2, session(40, 3, 5_000, 5_000))).isTrue();
    assertThat(InstanceStateTracker.newProcess(Long.MIN_VALUE, session(40, 3, 5_000, 5_000)))
        .isFalse();
    assertThat(InstanceStateTracker.newProcess(Long.MIN_VALUE, session(40, 3, 6_000, 5_000)))
        .isTrue();
    assertThat(
            InstanceStateTracker.newProcess(
                Long.MIN_VALUE, session(40, 3, 6_000, AgentSession.STARTED_NONE)))
        .isTrue();
    assertThat(
            InstanceStateTracker.newProcess(
                Long.MIN_VALUE, session(40, 3, 6_000, AgentSession.STARTED_UNKNOWN)))
        .isFalse();
    assertThat(InstanceStateTracker.newProcess(Long.MIN_VALUE, session(40, 3, 0, 5_000))).isFalse();
  }
}
