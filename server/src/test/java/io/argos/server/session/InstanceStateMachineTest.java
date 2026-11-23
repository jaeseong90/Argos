/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.session.InstanceStateMachine.Decision;
import io.argos.server.session.InstanceStateMachine.Facts;
import io.argos.server.session.InstanceStateMachine.WarnFacts;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The 12 §4.2 rules of {@link InstanceStateMachine} on a fake clock: the 60/180 s silence edges,
 * the Bye grace and every WARN condition entering and clearing.
 */
@Tag("req:F-S28")
@Tag("req:C-8")
class InstanceStateMachineTest {

  private final MutableClock clock = new MutableClock();
  private final InstanceStateMachine machine = new InstanceStateMachine(clock);
  private final long t0 = clock.millis();

  private static Facts live(long lastHeartbeatMs, WarnFacts warn) {
    return new Facts(
        InstanceState.UP, Set.of(), lastHeartbeatMs, InstanceStateMachine.NEVER, true, warn);
  }

  private Decision after(Duration silence, WarnFacts warn) {
    return machine.next(live(t0, warn), t0 + silence.toMillis());
  }

  @ParameterizedTest(name = "T-SV-022 {0} ms without a heartbeat -> {1}")
  @CsvSource({
    "0, UP",
    "59999, UP",
    "60000, STALE",
    "179999, STALE",
    "180000, DOWN",
    "900000, DOWN"
  })
  void silenceEdges(long silenceMs, InstanceState expected) {
    Decision d = after(Duration.ofMillis(silenceMs), WarnFacts.NONE);
    assertThat(d.state()).isEqualTo(expected);
    if (expected != InstanceState.UP) {
      assertThat(d.reasons()).containsExactly(InstanceStateMachine.SESSION_LOST);
    } else {
      assertThat(d.reasons()).isEmpty();
    }
  }

  @Test
  @DisplayName("T-SV-022 the 59/60/179/180 s edges hold on the configured clock as well")
  void secondEdges() {
    assertThat(after(Duration.ofSeconds(59), WarnFacts.NONE).state()).isEqualTo(InstanceState.UP);
    assertThat(after(Duration.ofMinutes(1), WarnFacts.NONE).state()).isEqualTo(InstanceState.STALE);
    assertThat(after(Duration.ofSeconds(179), WarnFacts.NONE).state())
        .isEqualTo(InstanceState.STALE);
    assertThat(after(Duration.ofMinutes(3), WarnFacts.NONE).state()).isEqualTo(InstanceState.DOWN);
    clock.advance(Duration.ofSeconds(60));
    assertThat(machine.next(live(t0, WarnFacts.NONE)).state()).isEqualTo(InstanceState.STALE);
  }

  @Test
  @DisplayName(
      "T-SV-022 without a live session a fresh heartbeat holds the current state; silence still rules")
  void heldWithoutSession() {
    Facts stale =
        new Facts(
            InstanceState.WARN,
            Set.of(InstanceStateMachine.CLOCK_SKEW),
            t0,
            InstanceStateMachine.NEVER,
            false,
            WarnFacts.NONE);
    Decision held = machine.next(stale, t0 + 30_000);
    assertThat(held.state()).isEqualTo(InstanceState.WARN);
    assertThat(held.reasons()).containsExactly(InstanceStateMachine.CLOCK_SKEW);
    assertThat(machine.next(stale, t0 + 60_000).state()).isEqualTo(InstanceState.STALE);
    assertThat(machine.next(stale, t0 + 180_000).state()).isEqualTo(InstanceState.DOWN);
  }

  @Test
  @DisplayName(
      "T-SV-023 after an AgentBye: no STALE during the grace, the state is held; DOWN at exactly 5:00")
  void byeGrace() {
    long bye = t0 + 10_000;
    Facts f = new Facts(InstanceState.UP, Set.of(), t0, bye, false, WarnFacts.NONE);
    assertThat(machine.next(f, bye + Duration.ofMinutes(2).toMillis()).state())
        .as("no STALE at 2 minutes of silence after a Bye")
        .isEqualTo(InstanceState.UP);
    assertThat(machine.next(f, bye + Duration.ofSeconds(299).toMillis()).state())
        .isEqualTo(InstanceState.UP);
    assertThat(machine.next(f, bye + Duration.ofMillis(299_999).toMillis()).state())
        .isEqualTo(InstanceState.UP);
    Decision down = machine.next(f, bye + Duration.ofMinutes(5).toMillis());
    assertThat(down.state()).isEqualTo(InstanceState.DOWN);
    assertThat(down.reasons()).containsExactly(InstanceStateMachine.AGENT_BYE);
  }

  @ParameterizedTest(name = "T-SV-022 clock offset {0} ms -> WARN clock_skew: {1}")
  @CsvSource({"0, false", "999, false", "-999, false", "1000, true", "-1000, true", "3000, true"})
  void clockSkew(long offset, boolean warn) {
    Decision d = after(Duration.ZERO, warn(w -> with(w, offset, null, null, null, null, null)));
    assertThat(d.state()).isEqualTo(warn ? InstanceState.WARN : InstanceState.UP);
    assertThat(d.reasons().contains(InstanceStateMachine.CLOCK_SKEW)).isEqualTo(warn);
  }

  @Test
  @DisplayName("T-SV-022 drops: WARN while the last drop is under 5 minutes old, UP at 5:00")
  void drops() {
    long now = t0 + 30_000;
    WarnFacts recent = new WarnFacts(0L, now - 299_999, false, Set.of(), false, false, never());
    WarnFacts old = new WarnFacts(0L, now - 300_000, false, Set.of(), false, false, never());
    Decision warn = machine.next(live(now, recent), now);
    assertThat(warn.state()).isEqualTo(InstanceState.WARN);
    assertThat(warn.reasons()).containsExactly(InstanceStateMachine.DROPS);
    assertThat(machine.next(live(now, old), now).state()).isEqualTo(InstanceState.UP);
  }

  @Test
  @DisplayName("T-SV-022 context leak: WARN while the last growth is under 5 minutes old")
  void contextLeak() {
    long now = t0 + 30_000;
    WarnFacts recent = new WarnFacts(0L, never(), false, Set.of(), false, false, now - 1);
    WarnFacts old = new WarnFacts(0L, never(), false, Set.of(), false, false, now - 300_000);
    Decision warn = machine.next(live(now, recent), now);
    assertThat(warn.reasons()).containsExactly(InstanceStateMachine.CONTEXT_LEAK);
    assertThat(machine.next(live(now, old), now).state()).isEqualTo(InstanceState.UP);
  }

  @Test
  @DisplayName(
      "T-SV-022 old version, disabled module, kill switch, config drift each enter WARN and clear to UP")
  void flagConditions() {
    long now = t0;
    WarnFacts none = new WarnFacts(0L, never(), false, Set.of(), false, false, never());
    assertThat(machine.next(live(now, none), now).state()).isEqualTo(InstanceState.UP);
    assertWarn(
        new WarnFacts(0L, never(), true, Set.of(), false, false, never()),
        InstanceStateMachine.OLD_VERSION);
    assertWarn(
        new WarnFacts(0L, never(), false, Set.of("jdbc"), false, false, never()),
        InstanceStateMachine.MODULE_DISABLED);
    assertWarn(
        new WarnFacts(0L, never(), false, Set.of(), true, false, never()),
        InstanceStateMachine.KILL_SWITCH);
    assertWarn(
        new WarnFacts(0L, never(), false, Set.of(), false, true, never()),
        InstanceStateMachine.CONFIG_DRIFT);
    WarnFacts all = new WarnFacts(5_000L, now, true, Set.of("jdbc"), true, true, now);
    Decision d = machine.next(live(now, all), now);
    assertThat(d.state()).isEqualTo(InstanceState.WARN);
    assertThat(d.reasons()).containsExactlyInAnyOrderElementsOf(InstanceStateMachine.WARN_FLAGS);
  }

  @Test
  @DisplayName("T-SV-022 WARN needs UP: a stale instance with a WARN condition is STALE")
  void warnOnlyWhileUp() {
    WarnFacts skewed = new WarnFacts(5_000L, never(), false, Set.of(), false, false, never());
    assertThat(after(Duration.ofSeconds(61), skewed).state()).isEqualTo(InstanceState.STALE);
  }

  @Test
  @DisplayName(
      "12 §4.2 old_version: only the lowest accepted minor of a server that accepts several")
  void lowerBound() {
    AgentVersionRange r = new AgentVersionRange("1.4.0");
    assertThat(r.atLowerBound("1.2.7")).isTrue();
    assertThat(r.atLowerBound("1.3.0")).isFalse();
    assertThat(r.atLowerBound("1.4.0")).isFalse();
    assertThat(r.atLowerBound("1.1.0")).as("refused, not WARN").isFalse();
    assertThat(r.atLowerBound("garbage")).isFalse();
    assertThat(new AgentVersionRange("0.0.0-SNAPSHOT").atLowerBound("0.0.0")).isFalse();
    assertThat(new AgentVersionRange("unknown").atLowerBound("1.0.0")).isFalse();
  }

  private void assertWarn(WarnFacts w, String reason) {
    Decision d = machine.next(live(t0, w), t0);
    assertThat(d.state()).isEqualTo(InstanceState.WARN);
    assertThat(d.reasons()).containsExactly(reason);
  }

  private static long never() {
    return InstanceStateMachine.NEVER;
  }

  private static WarnFacts warn(UnaryOperator<WarnFacts> f) {
    return f.apply(WarnFacts.NONE);
  }

  private static WarnFacts with(
      WarnFacts w,
      Long offset,
      Long lastDrop,
      Boolean old,
      Set<String> modules,
      Boolean kill,
      Boolean drift) {
    return new WarnFacts(
        offset != null ? offset : w.clockOffsetMs(),
        lastDrop != null ? lastDrop : w.lastDropMs(),
        old != null ? old : w.oldVersion(),
        modules != null ? modules : w.modulesDisabled(),
        kill != null ? kill : w.killSwitch(),
        drift != null ? drift : w.configDrift(),
        w.lastLeakMs());
  }
}
