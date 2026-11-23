/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.argos.server.alert.api.AlertNotification.Kind;
import io.argos.server.alert.api.AlertNotification.Severity;
import io.argos.server.alert.engine.StateMachine.Change;
import io.argos.server.alert.rule.RuleSpec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The alert state machine of 12 §5.4 with explicit evaluation times (the engine's clock is an
 * injected {@code Clock}; here the times are passed directly): {@code for}, recovery threshold and
 * {@code for / 2}, NO_DATA, two levels, renotify, ACK.
 */
@Tag("req:F-S17")
@Tag("req:F-S25")
@Tag("req:F-S26")
class StateMachineTest {

  private static final long T0 = 1_800_000_000_000L;
  private static final long S = 1_000;
  private static final long M = 60_000;

  private static UnitState unit() {
    return new UnitState(UUID.randomUUID(), "app");
  }

  private static List<AlertState> states(List<Change> changes) {
    return changes.stream().map(Change::to).toList();
  }

  @Test
  @DisplayName(
      "T-SV-032 for: OK -> PENDING when the condition is first true, FIRING only after 5m of"
          + " continuous truth; a false evaluation returns PENDING to OK")
  void forDuration() {
    RuleSpec r = Rules.p95("");
    UnitState s = unit();
    assertThat(states(StateMachine.step(r, s, 3500.0, T0))).containsExactly(AlertState.PENDING);
    assertThat(s.severity()).isEqualTo(Severity.WARN);
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 4 * M + 59 * S)).isEmpty();
    assertThat(s.state()).isEqualTo(AlertState.PENDING);
    List<Change> fire = StateMachine.step(r, s, 3600.0, T0 + 5 * M);
    assertThat(states(fire)).containsExactly(AlertState.FIRING);
    assertThat(fire.get(0).notification()).isEqualTo(Kind.FIRING);
    assertThat(fire.get(0).durationS()).isEqualTo(300);
    assertThat(fire.get(0).eventId()).isEqualTo(s.eventId());
    assertThat(s.threshold()).isEqualTo(3000.0);

    UnitState t = unit();
    StateMachine.step(r, t, 3500.0, T0);
    List<Change> back = StateMachine.step(r, t, 2500.0, T0 + 2 * M);
    assertThat(states(back)).containsExactly(AlertState.OK);
    assertThat(back.get(0).notification()).isNull();
    assertThat(StateMachine.step(r, t, 3500.0, T0 + 6 * M))
        .extracting(Change::to)
        .containsExactly(AlertState.PENDING);
  }

  @Test
  @DisplayName(
      "T-SV-032 for = 0 fires at the first true evaluation (PENDING then FIRING in one step)")
  void forZero() {
    RuleSpec r = Rules.p95("\"for\":\"0s\"");
    UnitState s = unit();
    assertThat(states(StateMachine.step(r, s, 12_000.0, T0)))
        .containsExactly(AlertState.PENDING, AlertState.FIRING);
    assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(s.threshold()).isEqualTo(10_000.0);
  }

  @Test
  @DisplayName(
      "T-SV-032 recovery threshold: below warn but above recover.threshold keeps FIRING; at or"
          + " below it for recover.for (2m) resolves, RESOLVED is notified")
  void recoveryThreshold() {
    RuleSpec r = Rules.p95("\"for\":\"0s\",\"recover\":{\"threshold\":2000,\"for\":\"2m\"}");
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    assertThat(StateMachine.step(r, s, 2500.0, T0 + 10 * S)).isEmpty();
    assertThat(StateMachine.step(r, s, 2500.0, T0 + 5 * M)).isEmpty();
    assertThat(s.state()).as("between recovery and warn").isEqualTo(AlertState.FIRING);
    assertThat(StateMachine.step(r, s, 1900.0, T0 + 6 * M)).isEmpty();
    assertThat(StateMachine.step(r, s, 2100.0, T0 + 7 * M)).as("back above: timer reset").isEmpty();
    assertThat(StateMachine.step(r, s, 1900.0, T0 + 8 * M)).isEmpty();
    assertThat(StateMachine.step(r, s, 1900.0, T0 + 9 * M + 59 * S)).isEmpty();
    List<Change> resolved = StateMachine.step(r, s, 1900.0, T0 + 10 * M);
    assertThat(states(resolved)).containsExactly(AlertState.RESOLVED);
    assertThat(resolved.get(0).notification()).isEqualTo(Kind.RESOLVED);
    assertThat(resolved.get(0).durationS()).isEqualTo(600);
    assertThat(StateMachine.step(r, s, 1000.0, T0 + 11 * M)).isEmpty();
    assertThat(s.state()).isEqualTo(AlertState.OK);
  }

  @Test
  @DisplayName(
      "T-SV-032 without a recovery threshold the condition must be false for for / 2 (5m -> 2m30s)")
  void recoveryHalfFor() {
    RuleSpec r = Rules.p95("");
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    StateMachine.step(r, s, 3500.0, T0 + 5 * M);
    assertThat(s.state()).isEqualTo(AlertState.FIRING);
    assertThat(StateMachine.step(r, s, 2999.0, T0 + 6 * M)).isEmpty();
    assertThat(StateMachine.step(r, s, 2999.0, T0 + 8 * M + 29 * S)).isEmpty();
    assertThat(states(StateMachine.step(r, s, 2999.0, T0 + 8 * M + 30 * S)))
        .containsExactly(AlertState.RESOLVED);
  }

  @Test
  @DisplayName(
      "T-SV-032 NO_DATA: with no_data warn a missing value moves FIRING to NO_DATA (notified); the"
          + " returning value restores FIRING with the same event and severity, without a new"
          + " FIRING notification")
  void noData() {
    RuleSpec r = Rules.p95("\"for\":\"0s\",\"no_data\":\"warn\",\"renotify_interval\":\"0s\"");
    UnitState s = unit();
    StateMachine.step(r, s, 12_000.0, T0);
    UUID firing = s.eventId();
    List<Change> gone = StateMachine.step(r, s, null, T0 + 10 * S);
    assertThat(states(gone)).containsExactly(AlertState.NO_DATA);
    assertThat(gone.get(0).severity()).isEqualTo(Severity.WARN);
    assertThat(gone.get(0).notification()).isEqualTo(Kind.FIRING);
    assertThat(StateMachine.step(r, s, null, T0 + 20 * S)).as("stays NO_DATA").isEmpty();
    List<Change> back = StateMachine.step(r, s, 12_000.0, T0 + 30 * S);
    assertThat(states(back)).containsExactly(AlertState.FIRING);
    assertThat(back).allMatch(c -> c.notification() == null);
    assertThat(s.eventId()).isEqualTo(firing);
    assertThat(s.severity()).isEqualTo(Severity.CRITICAL);

    UnitState ok = unit();
    StateMachine.step(r, ok, 100.0, T0);
    assertThat(states(StateMachine.step(r, ok, null, T0 + 10 * S)))
        .containsExactly(AlertState.NO_DATA);
    assertThat(states(StateMachine.step(r, ok, 100.0, T0 + 20 * S))).containsExactly(AlertState.OK);
  }

  @Test
  @DisplayName(
      "T-SV-032 no_data ignore (request metric default): a missing value keeps FIRING, and PENDING"
          + " returns to OK")
  void noDataIgnore() {
    RuleSpec r = Rules.p95("");
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    assertThat(states(StateMachine.step(r, s, null, T0 + M))).containsExactly(AlertState.OK);
    StateMachine.step(r, s, 3500.0, T0 + 2 * M);
    StateMachine.step(r, s, 3500.0, T0 + 7 * M);
    assertThat(s.state()).isEqualTo(AlertState.FIRING);
    assertThat(StateMachine.step(r, s, null, T0 + 8 * M)).isEmpty();
    assertThat(s.state()).isEqualTo(AlertState.FIRING);
  }

  @Test
  @DisplayName(
      "T-SV-032 two levels: warn FIRING rising to critical is notified at once (ESCALATED); falling"
          + " back to warn applies at the next renotify")
  void twoLevels() {
    RuleSpec r = Rules.p95("\"for\":\"0s\",\"renotify_interval\":\"30m\"");
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    assertThat(s.severity()).isEqualTo(Severity.WARN);
    List<Change> up = StateMachine.step(r, s, 11_000.0, T0 + M);
    assertThat(up)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.notification()).isEqualTo(Kind.ESCALATED);
              assertThat(c.severity()).isEqualTo(Severity.CRITICAL);
              assertThat(c.persist()).isTrue();
            });
    assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(StateMachine.step(r, s, 4000.0, T0 + 2 * M)).as("fall: no notification").isEmpty();
    assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(s.deescalateTo()).isEqualTo(Severity.WARN);
    List<Change> renotify = StateMachine.step(r, s, 4000.0, T0 + 31 * M);
    assertThat(renotify)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.notification()).isEqualTo(Kind.RENOTIFY);
              assertThat(c.severity()).isEqualTo(Severity.WARN);
              assertThat(c.persist()).isFalse();
            });
    assertThat(s.severity()).isEqualTo(Severity.WARN);
    assertThat(s.threshold()).isEqualTo(3000.0);
  }

  @Test
  @DisplayName(
      "T-SV-032 renotify every renotify_interval while FIRING; ACK (FIRING only) stops it; RESOLVED"
          + " is still notified after ACK")
  void renotifyAndAck() {
    RuleSpec r = Rules.p95("\"for\":\"0s\",\"renotify_interval\":\"30m\"");
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 29 * M)).isEmpty();
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 30 * M))
        .extracting(Change::notification)
        .containsExactly(Kind.RENOTIFY);
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 59 * M)).isEmpty();
    UUID user = UUID.randomUUID();
    Change ack = StateMachine.ack(s, user, "alice", "looking", T0 + 40 * M);
    assertThat(ack).isNotNull();
    assertThat(ack.to()).isEqualTo(AlertState.ACKED);
    assertThat(ack.notification()).isNull();
    assertThat(StateMachine.ack(s, user, "alice", "again", T0 + 41 * M))
        .as("ACK of a non-FIRING alert")
        .isNull();
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 61 * M))
        .as("no renotify while ACKED")
        .isEmpty();
    assertThat(StateMachine.step(r, s, 3500.0, T0 + 120 * M)).isEmpty();
    List<Change> resolved = StateMachine.step(r, s, 100.0, T0 + 121 * M);
    assertThat(resolved).extracting(Change::notification).containsExactly(Kind.RESOLVED);
    assertThat(resolved.get(0).from()).isEqualTo(AlertState.ACKED);

    UnitState pending = unit();
    StateMachine.step(Rules.p95(""), pending, 3500.0, T0);
    assertThat(StateMachine.ack(pending, user, "alice", null, T0)).as("PENDING").isNull();
  }

  @Test
  @DisplayName(
      "T-SV-032 event-driven units fire and resolve directly (instance DOWN, channel failure) and"
          + " never fire twice")
  void eventDriven() {
    UnitState s = unit();
    assertThat(StateMachine.fireNow(s, Severity.CRITICAL, 1, 1.0, Kind.FIRING, T0))
        .extracting(Change::to)
        .containsExactly(AlertState.FIRING);
    assertThat(StateMachine.fireNow(s, Severity.CRITICAL, 1, 1.0, Kind.FIRING, T0 + S)).isEmpty();
    assertThat(StateMachine.resolveNow(s, 0, Kind.RESOLVED, T0 + M))
        .singleElement()
        .satisfies(c -> assertThat(c.durationS()).isEqualTo(60));
    assertThat(StateMachine.resolveNow(s, 0, Kind.RESOLVED, T0 + 2 * M)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-032 no_data ignore: a missing value keeps FIRING; a gone target (instance DOWN,"
          + " archived) resolves FIRING or ACKED at once with no value (C-226 (12)), and nothing"
          + " else")
  void targetGone() {
    RuleSpec r = Rules.p95("\"for\":\"0s\"");
    assertThat(r.noData()).isEqualTo(RuleSpec.NoData.IGNORE);
    UnitState s = unit();
    StateMachine.step(r, s, 3500.0, T0);
    assertThat(s.state()).isEqualTo(AlertState.FIRING);
    assertThat(StateMachine.step(r, s, null, T0 + 10 * M)).as("ignore keeps FIRING").isEmpty();
    assertThat(s.state()).isEqualTo(AlertState.FIRING);
    List<Change> gone = StateMachine.resolveGone(s, T0 + 11 * M);
    assertThat(gone)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.from()).isEqualTo(AlertState.FIRING);
              assertThat(c.to()).isEqualTo(AlertState.RESOLVED);
              assertThat(c.notification()).isEqualTo(Kind.RESOLVED);
              assertThat(c.persist()).isTrue();
              assertThat(c.severity()).isEqualTo(Severity.WARN);
              assertThat(c.durationS()).isEqualTo(660);
            });
    assertThat(s.value()).isNull();
    assertThat(StateMachine.resolveGone(s, T0 + 12 * M)).as("already resolved").isEmpty();

    UnitState acked = unit();
    StateMachine.step(r, acked, 3500.0, T0);
    assertThat(StateMachine.ack(acked, UUID.randomUUID(), "alice", null, T0 + M)).isNotNull();
    assertThat(StateMachine.resolveGone(acked, T0 + 2 * M))
        .extracting(Change::from, Change::to)
        .containsExactly(tuple(AlertState.ACKED, AlertState.RESOLVED));

    UnitState pending = unit();
    StateMachine.step(Rules.p95(""), pending, 3500.0, T0);
    assertThat(StateMachine.resolveGone(pending, T0 + M)).as("PENDING").isEmpty();
    assertThat(StateMachine.resolveGone(unit(), T0)).as("OK").isEmpty();
  }

  private static List<AlertReason> reasons(List<Change> changes) {
    return changes.stream().map(Change::reason).toList();
  }

  @Test
  @DisplayName(
      "T-SV-032 every persisted transition carries its reason (C-227 (3), WO-38): condition,"
          + " cleared, escalated, acked, resolved, target_gone, rule_disabled, rule_deleted,"
          + " no_data, data_returned; a renotify (not persisted) has none")
  void reasonPerTransition() {
    RuleSpec p95 = Rules.p95("\"renotify_interval\":\"30m\"");
    UnitState s = unit();
    assertThat(reasons(StateMachine.step(p95, s, 3500.0, T0)))
        .as("OK -> PENDING")
        .containsExactly(AlertReason.CONDITION);
    assertThat(reasons(StateMachine.step(p95, s, 2500.0, T0 + M)))
        .as("PENDING -> OK, condition false")
        .containsExactly(AlertReason.CLEARED);
    StateMachine.step(p95, s, 3500.0, T0 + 2 * M);
    assertThat(reasons(StateMachine.step(p95, s, null, T0 + 3 * M)))
        .as("PENDING -> OK, no value with no_data ignore")
        .containsExactly(AlertReason.CLEARED);
    StateMachine.step(p95, s, 3500.0, T0 + 4 * M);
    List<Change> fire = StateMachine.step(p95, s, 3500.0, T0 + 9 * M);
    assertThat(fire)
        .extracting(Change::to, Change::reason)
        .containsExactly(tuple(AlertState.FIRING, AlertReason.CONDITION));
    assertThat(StateMachine.step(p95, s, 11_000.0, T0 + 10 * M))
        .extracting(Change::notification, Change::reason)
        .containsExactly(tuple(Kind.ESCALATED, AlertReason.ESCALATED));
    assertThat(StateMachine.step(p95, s, 11_000.0, T0 + 40 * M))
        .extracting(Change::notification, Change::persist, Change::reason)
        .containsExactly(tuple(Kind.RENOTIFY, false, null));
    assertThat(StateMachine.ack(s, UUID.randomUUID(), "alice", null, T0 + 41 * M).reason())
        .isEqualTo(AlertReason.ACKED);
    StateMachine.step(p95, s, 100.0, T0 + 42 * M);
    assertThat(StateMachine.step(p95, s, 100.0, T0 + 45 * M))
        .extracting(Change::to, Change::reason)
        .containsExactly(tuple(AlertState.RESOLVED, AlertReason.RESOLVED));

    RuleSpec noData = Rules.p95("\"for\":\"0s\",\"no_data\":\"critical\"");
    UnitState n = unit();
    assertThat(reasons(StateMachine.step(noData, n, 3500.0, T0)))
        .containsExactly(AlertReason.CONDITION, AlertReason.CONDITION);
    assertThat(StateMachine.step(noData, n, null, T0 + M))
        .extracting(Change::to, Change::reason)
        .containsExactly(tuple(AlertState.NO_DATA, AlertReason.NO_DATA));
    assertThat(StateMachine.step(noData, n, 3500.0, T0 + 2 * M))
        .extracting(Change::to, Change::reason)
        .containsExactly(tuple(AlertState.FIRING, AlertReason.DATA_RETURNED));

    UnitState event = unit();
    assertThat(reasons(StateMachine.fireNow(event, Severity.CRITICAL, 1, 1.0, Kind.FIRING, T0)))
        .containsExactly(AlertReason.CONDITION);
    assertThat(reasons(StateMachine.resolveNow(event, 0, Kind.RESOLVED, T0 + M)))
        .as("event-driven condition ended")
        .containsExactly(AlertReason.RESOLVED);

    RuleSpec now = Rules.p95("\"for\":\"0s\"");
    UnitState gone = unit();
    StateMachine.step(now, gone, 3500.0, T0);
    assertThat(reasons(StateMachine.resolveGone(gone, T0 + M)))
        .containsExactly(AlertReason.TARGET_GONE);
    for (AlertReason rule : List.of(AlertReason.RULE_DISABLED, AlertReason.RULE_DELETED)) {
      UnitState u = unit();
      StateMachine.step(now, u, 3500.0, T0);
      assertThat(StateMachine.resolveNow(u, 3500.0, Kind.RESOLVED, rule, T0 + M))
          .extracting(Change::to, Change::reason)
          .containsExactly(tuple(AlertState.RESOLVED, rule));
    }
  }

  @Test
  @DisplayName(
      "T-SV-032 the reason wire names are the closed list of V009's CHECK, each at most 16"
          + " characters (alert_events.reason VARCHAR(16))")
  void reasonWireNames() {
    assertThat(java.util.Arrays.stream(AlertReason.values()).map(AlertReason::wire))
        .containsExactly(
            "condition",
            "cleared",
            "escalated",
            "acked",
            "resolved",
            "target_gone",
            "rule_disabled",
            "rule_deleted",
            "no_data",
            "data_returned")
        .allMatch(w -> w.length() <= 16);
  }
}
