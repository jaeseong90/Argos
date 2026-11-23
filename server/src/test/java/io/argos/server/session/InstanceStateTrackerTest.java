/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.InstanceStateStore.OpenInstance;
import io.argos.server.session.InstanceStateStore.StateRow;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The {@link InstanceStateTracker} on a fake clock: transitions from the session hooks and the
 * monitor tick, each persisted (state row and {@code events} row per the {@code Outages} contract)
 * and published; the WARN inputs (heartbeat {@code dropped_total}, server drops, agent events,
 * configuration drift, context leaks) enter and clear.
 */
@Tag("req:F-S28")
class InstanceStateTrackerTest {

  private final TrackerFixture f = new TrackerFixture();

  @AfterEach
  void close() {
    f.close();
  }

  private AgentSession up(int instance) {
    return f.connect(f.session(instance, 1, "1.4.0", "0123456789abcdef"));
  }

  /**
   * The newest state row written for {@code instance}, after flushing the writer on this thread.
   *
   * <p>The writer coalesces the pending state rows of an instance (the newest wins, {@link
   * InstanceStateWriter}): the row of a transition reaches the store only if a flush runs before
   * the next row of the instance is queued. The writer thread usually does, but not always under
   * load (C-224 (4): "T-SV-022 drops" failed once in a full build when the WARN row was coalesced
   * into the UP row of the recovery). A test that checks the row of a transition therefore flushes
   * right after it, and checks the state column of the store at that point.
   */
  private StateRow lastRow(int instance) {
    List<StateRow> rows = f.rows().stream().filter(r -> r.instanceId() == instance).toList();
    assertThat(rows).as("state rows of instance %d", instance).isNotEmpty();
    return rows.get(rows.size() - 1);
  }

  @Test
  @DisplayName(
      "T-SV-022 Hello UP, no heartbeat: STALE at 60 s, DOWN at 180 s, each a state event with session_lost; a reconnect is UP")
  void silenceTransitions() {
    AgentSession s = up(21);
    f.tracker.onHeartbeat(s, f.clock.millis(), 0);
    assertThat(lastRow(21).status()).isEqualTo("UP");
    f.clock.advance(Duration.ofSeconds(59));
    f.tracker.tick();
    assertThat(f.tracker.state(21)).isEqualTo(InstanceState.UP);
    f.clock.advance(Duration.ofSeconds(1));
    f.tracker.tick();
    assertThat(f.tracker.state(21)).isEqualTo(InstanceState.STALE);
    assertThat(lastRow(21).status()).isEqualTo("STALE");
    f.clock.advance(Duration.ofMinutes(2));
    f.tracker.tick();
    assertThat(f.tracker.state(21)).isEqualTo(InstanceState.DOWN);
    assertThat(lastRow(21).status()).isEqualTo("DOWN");
    f.registry.unregister(s);
    f.tracker.tick();
    assertThat(f.tracker.counts().get(InstanceState.DOWN)).as("no longer tracked").isZero();
    up(21);
    assertThat(f.changes)
        .extracting(c -> c.from() + ">" + c.to())
        .containsExactly("DOWN>UP", "UP>STALE", "STALE>DOWN", "DOWN>UP");
    List<EventRow> events = f.events();
    assertThat(events).extracting(EventRow::type).containsOnly("state");
    assertThat(events.get(1).detailJson())
        .isEqualTo(
            "{\"from\":\"UP\",\"to\":\"STALE\",\"reason\":\"session_lost\",\"reasons\":[\"session_lost\"]}");
    assertThat(events.get(1).instanceId()).isEqualTo(21);
    assertThat(events.get(1).appId()).isEqualTo(7);
    assertThat(events.get(1).env()).isEqualTo("prod");
    assertThat(events.get(1).ts().toEpochMilli()).isEqualTo(f.changes.get(1).at().toEpochMilli());
    assertThat(f.rows()).extracting(StateRow::status).contains("UP", "STALE", "DOWN");
  }

  @Test
  @DisplayName("T-SV-022 a heartbeat of a STALE instance makes it UP at once")
  void heartbeatRecovers() {
    AgentSession s = up(22);
    f.clock.advance(Duration.ofSeconds(70));
    f.tracker.tick();
    assertThat(f.tracker.state(22)).isEqualTo(InstanceState.STALE);
    InstanceState ack = f.tracker.onHeartbeat(s, f.clock.millis(), 0);
    assertThat(ack).isEqualTo(InstanceState.UP);
    assertThat(f.changes.get(f.changes.size() - 1).from()).isEqualTo(InstanceState.STALE);
  }

  @Test
  @DisplayName(
      "T-SV-022 drops: a growth of dropped_total is WARN drops for 5 minutes; the first heartbeat is the baseline")
  void heartbeatDrops() {
    AgentSession s = up(23);
    assertThat(f.tracker.onHeartbeat(s, f.clock.millis(), 40)).isEqualTo(InstanceState.UP);
    f.clock.advance(Duration.ofSeconds(30));
    assertThat(f.tracker.onHeartbeat(s, f.clock.millis(), 40)).isEqualTo(InstanceState.UP);
    f.clock.advance(Duration.ofSeconds(30));
    assertThat(f.tracker.onHeartbeat(s, f.clock.millis(), 41)).isEqualTo(InstanceState.WARN);
    assertThat(f.tracker.reasons(23)).containsExactly(InstanceStateMachine.DROPS);
    StateRow warn = lastRow(23);
    assertThat(warn.status()).isEqualTo("WARN");
    assertThat(warn.warnFlags()).isEqualTo(List.of("drops"));
    for (int i = 0; i < 9; i++) {
      f.clock.advance(Duration.ofSeconds(30));
      f.tracker.onHeartbeat(s, f.clock.millis(), 41);
    }
    assertThat(f.tracker.state(23)).as("4:30 after the drop").isEqualTo(InstanceState.WARN);
    f.clock.advance(Duration.ofSeconds(30));
    assertThat(f.tracker.onHeartbeat(s, f.clock.millis(), 41)).isEqualTo(InstanceState.UP);
    StateRow recovered = lastRow(23);
    assertThat(recovered.status()).isEqualTo("UP");
    assertThat(recovered.warnFlags()).as("the WARN flags are cleared").isEmpty();
    assertThat(f.rows())
        .filteredOn(r -> r.instanceId() == 23 && r.status().equals("WARN"))
        .first()
        .extracting(StateRow::warnFlags)
        .isEqualTo(List.of("drops"));
  }

  @Test
  @DisplayName("T-SV-022 server drops (ACK_PARTIAL_DROP, full WAL) make the instance WARN drops")
  void serverDrops() {
    up(24);
    f.tracker.onServerDrop(24);
    assertThat(f.tracker.state(24)).isEqualTo(InstanceState.WARN);
    f.clock.advance(Duration.ofMinutes(5));
    f.tracker.onHeartbeat(f.registry.find(24).orElseThrow(), f.clock.millis(), 0);
    assertThat(f.tracker.state(24)).isEqualTo(InstanceState.UP);
  }

  @Test
  @DisplayName(
      "T-SV-022 MODULE_DISABLED and KILL_SWITCH_ON enter WARN; KILL_SWITCH_OFF clears; a new agent epoch clears the module")
  void agentEvents() {
    AgentSession s = up(25);
    f.tracker.onEvent(
        s,
        AgentEvent.newBuilder().setType(AgentEventType.MODULE_DISABLED).setModule("jdbc").build());
    assertThat(f.tracker.state(25)).isEqualTo(InstanceState.WARN);
    assertThat(f.tracker.modulesDisabled(25)).containsExactly("jdbc");
    f.tracker.onEvent(s, AgentEvent.newBuilder().setType(AgentEventType.KILL_SWITCH_ON).build());
    assertThat(f.tracker.reasons(25))
        .containsExactlyInAnyOrder(
            InstanceStateMachine.MODULE_DISABLED, InstanceStateMachine.KILL_SWITCH);
    f.tracker.onEvent(s, AgentEvent.newBuilder().setType(AgentEventType.KILL_SWITCH_OFF).build());
    assertThat(f.tracker.reasons(25)).containsExactly(InstanceStateMachine.MODULE_DISABLED);
    // same agent process reconnecting keeps the module disabled
    AgentSession same = f.connect(f.session(25, 1, "1.4.0", "0123456789abcdef"));
    assertThat(f.tracker.state(25)).isEqualTo(InstanceState.WARN);
    f.registry.unregister(same);
    f.connect(f.session(25, 2, "1.4.0", "0123456789abcdef"));
    assertThat(f.tracker.state(25)).as("a restarted agent").isEqualTo(InstanceState.UP);
    assertThat(f.tracker.modulesDisabled(25)).isEmpty();
  }

  @Test
  @DisplayName("T-SV-022 the agent's leaked-context counter growth is WARN context_leak")
  void contextLeak() {
    up(26);
    f.tracker.onContextLeak(26);
    assertThat(f.tracker.reasons(26)).containsExactly(InstanceStateMachine.CONTEXT_LEAK);
  }

  @Test
  @DisplayName(
      "T-SV-022 config drift: the instance whose hash differs from the app's majority is WARN on the next tick")
  void configDrift() {
    up(31);
    up(32);
    f.connect(f.session(33, 1, "1.4.0", "ffffffffffffffff"));
    f.tracker.tick();
    assertThat(f.tracker.state(31)).isEqualTo(InstanceState.UP);
    assertThat(f.tracker.state(32)).isEqualTo(InstanceState.UP);
    assertThat(f.tracker.state(33)).isEqualTo(InstanceState.WARN);
    assertThat(f.tracker.reasons(33)).containsExactly(InstanceStateMachine.CONFIG_DRIFT);
  }

  @Test
  @DisplayName("T-SV-022 the lowest accepted agent version is WARN old_version from the Hello")
  void oldVersion() {
    f.connect(f.session(34, 1, "1.2.3", "0123456789abcdef"));
    assertThat(f.tracker.state(34)).isEqualTo(InstanceState.WARN);
    assertThat(f.tracker.reasons(34)).containsExactly(InstanceStateMachine.OLD_VERSION);
  }

  @Test
  @DisplayName(
      "WO-22 restore: instances left UP by the previous run count as last seen at the start, STALE 60 s and DOWN 180 s later")
  void restore() {
    long start = f.clock.millis();
    f.tracker.restore(
        List.of(
            new OpenInstance(41, 7, "prod", "UP", List.of(), 12),
            new OpenInstance(42, 7, "prod", "WARN", List.of("clock_skew"), 2_000),
            new OpenInstance(43, 7, "prod", "DOWN", List.of(), null)),
        start);
    assertThat(f.tracker.state(41)).isEqualTo(InstanceState.UP);
    assertThat(f.tracker.state(42)).isEqualTo(InstanceState.WARN);
    assertThat(f.tracker.state(43)).isEqualTo(InstanceState.DOWN);
    f.clock.advance(Duration.ofMinutes(1));
    f.tracker.tick();
    assertThat(f.tracker.state(41)).isEqualTo(InstanceState.STALE);
    f.connect(f.session(42, 1, "1.4.0", "0123456789abcdef"));
    assertThat(f.tracker.state(42)).isEqualTo(InstanceState.UP);
    f.clock.advance(Duration.ofMinutes(2));
    f.tracker.tick();
    assertThat(f.tracker.state(41)).isEqualTo(InstanceState.DOWN);
    assertThat(f.rows())
        .filteredOn(r -> r.instanceId() == 41)
        .extracting(StateRow::lastSeenAt)
        .as("no heartbeat was seen: last_seen_at is kept")
        .containsOnlyNulls();
  }

  @Test
  @DisplayName(
      "12 §3.9 a PostgreSQL outage never blocks the hooks: rows and events are kept and written once it is back")
  void storeOutage() {
    f.store.failing = true;
    AgentSession s = up(51);
    f.clock.advance(Duration.ofMinutes(1));
    f.tracker.tick();
    assertThat(f.writer.flush()).isFalse();
    assertThat(f.tracker.state(51)).isEqualTo(InstanceState.STALE);
    f.store.failing = false;
    assertThat(f.events())
        .extracting(EventRow::detailJson)
        .anyMatch(d -> d.contains("\"to\":\"UP\""))
        .anyMatch(d -> d.contains("\"to\":\"STALE\""));
    assertThat(f.rows()).extracting(StateRow::status).contains("STALE");
    assertThat(s).isNotNull();
  }

  @Test
  @DisplayName("T-SV-022 every transition is published once with its instance, app and env")
  void published() {
    up(61);
    InstanceStateChange c = f.changes.get(0);
    assertThat(c.instanceId()).isEqualTo(61);
    assertThat(c.appId()).isEqualTo(7);
    assertThat(c.env()).isEqualTo("prod");
    assertThat(c.from()).isEqualTo(InstanceState.DOWN);
    assertThat(c.to()).isEqualTo(InstanceState.UP);
    assertThat(c.reasons()).isEmpty();
    assertThat(c.at().toEpochMilli()).isEqualTo(f.clock.millis());
  }
}
