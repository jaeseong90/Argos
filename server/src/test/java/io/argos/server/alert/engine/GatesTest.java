/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.metric.MetricRequest;
import io.argos.server.alert.metric.MetricValue;
import io.argos.server.alert.metric.WindowProvider;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gates of 12 §5.4 before the state machine: active time slots, recurring mutes ({@code
 * mute_schedules}), one-off mutes ({@code mutes}), the warm-up grace and the minimum samples of
 * request metrics (S-15).
 */
@Tag("req:F-S25")
class GatesTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  @Test
  @DisplayName(
      "T-SV-032 active time: weekdays 09:00-18:00 in the server time zone evaluate, Saturday and"
          + " 18:00 do not; no slot means always")
  void activeTime() {
    RuleSpec r =
        Rules.p95(
            "\"active_time\":[{\"days\":[\"Mon\",\"Tue\",\"Wed\",\"Thu\",\"Fri\"],\"from\":\"09:00\",\"to\":\"18:00\"}]");
    ZonedDateTime monday = ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, SEOUL);
    assertThat(Gates.active(r, monday)).isTrue();
    assertThat(Gates.active(r, monday.withHour(17).withMinute(59))).isTrue();
    assertThat(Gates.active(r, monday.withHour(18))).isFalse();
    assertThat(Gates.active(r, monday.withHour(8).withMinute(59))).isFalse();
    assertThat(Gates.active(r, monday.plusDays(5))).as("Saturday").isFalse();
    assertThat(Gates.active(Rules.p95(""), monday.plusDays(5))).isTrue();
    RuleSpec allDay =
        Rules.p95("\"active_time\":[{\"days\":[\"Sun\"],\"from\":\"00:00\",\"to\":\"24:00\"}]");
    assertThat(Gates.active(allDay, monday.plusDays(6).withHour(23).withMinute(59))).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-032 mute schedule: cron 0 2 * * * for 2h mutes 02:00 to 04:00 (end exclusive) every"
          + " day")
  void muteSchedule() {
    RuleSpec r =
        Rules.p95(
            "\"mute_schedules\":[{\"cron\":\"0 2 * *"
                + " *\",\"duration\":\"2h\",\"reason\":\"batch\"}]");
    ZonedDateTime day = ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, SEOUL);
    assertThat(Gates.scheduleMuted(r, day.withHour(1).withMinute(59))).isFalse();
    assertThat(Gates.scheduleMuted(r, day.withHour(2))).isTrue();
    assertThat(Gates.scheduleMuted(r, day.withHour(3).withMinute(59))).isTrue();
    assertThat(Gates.scheduleMuted(r, day.withHour(4))).isFalse();
    assertThat(Gates.scheduleMuted(Rules.p95(""), day.withHour(2))).isFalse();
  }

  @Test
  @DisplayName(
      "T-SV-032 one-off mutes match by rule, application, environment and instance (null matches"
          + " any) between from and to")
  void mutes() {
    UUID rule = UUID.randomUUID();
    Instant from = Instant.parse("2026-11-23T00:00:00Z");
    Instant to = from.plus(Duration.ofHours(1));
    MuteRow app =
        new MuteRow(UUID.randomUUID(), 7, null, null, null, from, to, "deploy", null, null);
    MuteRow inst = new MuteRow(UUID.randomUUID(), 7, "prod", 42, null, from, to, null, null, null);
    MuteRow other =
        new MuteRow(
            UUID.randomUUID(), null, null, null, UUID.randomUUID(), from, to, null, null, null);
    Instant mid = from.plusSeconds(600);
    assertThat(Gates.muted(List.of(app), rule, 7, "prod", null, mid)).isTrue();
    assertThat(Gates.muted(List.of(app), rule, 8, "prod", null, mid)).isFalse();
    assertThat(Gates.muted(List.of(app), rule, 7, "prod", null, to)).as("end exclusive").isFalse();
    assertThat(Gates.muted(List.of(app), rule, 7, "prod", null, from.minusSeconds(1))).isFalse();
    assertThat(Gates.muted(List.of(inst), rule, 7, "prod", 42, mid)).isTrue();
    assertThat(Gates.muted(List.of(inst), rule, 7, "prod", 43, mid)).isFalse();
    assertThat(Gates.muted(List.of(inst), rule, 7, "dev", 42, mid)).isFalse();
    assertThat(Gates.muted(List.of(other), rule, 7, "prod", null, mid))
        .as("another rule")
        .isFalse();
  }

  @Test
  @DisplayName(
      "T-SV-032 warm-up: an instance is skipped for warmup_grace (3m) after its agent epoch"
          + " started; unknown start is not warming")
  void warmup() {
    RuleSpec r = Rules.p95("");
    long start = 1_800_000_000_000L;
    assertThat(Gates.warming(start, r, start + 179_999)).isTrue();
    assertThat(Gates.warming(start, r, start + 180_000)).isFalse();
    assertThat(Gates.warming(-1, r, start)).isFalse();
  }

  @Test
  @Tag("req:S-15")
  @DisplayName(
      "T-SV-032 (S-15) low traffic: 19 slow requests in the 1m window are below min_samples 20, so"
          + " the p95 rule is not evaluated (no PENDING, no FIRING); 20 are evaluated")
  void lowTrafficDoesNotFire() {
    long now = 1_800_000_000_000L;
    RuleSpec r = Rules.p95("\"for\":\"0s\"");
    WindowProvider w = new WindowProvider(Duration.ofMinutes(15));
    for (int i = 0; i < 19; i++) {
      w.recordTransaction(7, 1, RuleSpec.Kind.HTTP, 100, 20_000, false, 500, now - i * 3_000L);
    }
    MetricRequest q =
        new MetricRequest(
            AlertMetric.RESPONSE_TIME_P95,
            7,
            RuleSpec.Kind.HTTP,
            RuleSpec.TargetType.APP,
            null,
            Duration.ofMinutes(1),
            now,
            Set.of());
    MetricValue v = w.read(q);
    assertThat(v.present()).isTrue();
    assertThat(v.samples()).isEqualTo(19);
    assertThat(v.value()).isGreaterThan(10_000.0);
    UnitState s = new UnitState(UUID.randomUUID(), "app");
    for (int tick = 0; tick < 60; tick++) {
      assertThat(
              UnitEvaluation.evaluate(r, AlertMetric.RESPONSE_TIME_P95, s, v, now + tick * 10_000L))
          .isEmpty();
    }
    assertThat(s.state()).isEqualTo(AlertState.OK);

    w.recordTransaction(7, 1, RuleSpec.Kind.HTTP, 100, 20_000, false, 500, now);
    MetricValue twenty = w.read(q);
    assertThat(twenty.samples()).isEqualTo(20);
    assertThat(UnitEvaluation.evaluate(r, AlertMetric.RESPONSE_TIME_P95, s, twenty, now))
        .extracting(StateMachine.Change::to)
        .containsExactly(AlertState.PENDING, AlertState.FIRING);
  }

  @Test
  @Tag("req:S-17")
  @DisplayName(
      "T-SV-032 warm-up of an application value: the samples of an instance in its grace are left"
          + " out, so its slow start cannot fire the app p95 rule")
  void warmupExcludedFromAppValue() {
    long now = 1_800_000_000_000L;
    WindowProvider w = new WindowProvider(Duration.ofMinutes(15));
    for (int i = 0; i < 30; i++) {
      w.recordTransaction(7, 1, RuleSpec.Kind.HTTP, 100, 50, false, 500, now - i * 1_000L);
      w.recordTransaction(7, 2, RuleSpec.Kind.HTTP, 100, 30_000, false, 500, now - i * 1_000L);
    }
    MetricRequest all =
        new MetricRequest(
            AlertMetric.RESPONSE_TIME_P95,
            7,
            RuleSpec.Kind.HTTP,
            RuleSpec.TargetType.APP,
            null,
            Duration.ofMinutes(1),
            now,
            Set.of());
    MetricRequest warm =
        new MetricRequest(
            AlertMetric.RESPONSE_TIME_P95,
            7,
            RuleSpec.Kind.HTTP,
            RuleSpec.TargetType.APP,
            null,
            Duration.ofMinutes(1),
            now,
            Set.of(2));
    assertThat(w.read(all).value()).isGreaterThan(10_000.0);
    assertThat(w.read(warm).value()).isLessThan(100.0);
    assertThat(w.read(warm).samples()).isEqualTo(30);
  }

  @Test
  @DisplayName("T-SV-032 an unavailable metric is never evaluated, whatever the no_data policy")
  void unavailableSkipped() {
    RuleSpec r = Rules.p95("\"no_data\":\"critical\",\"min_samples\":0");
    UnitState s = new UnitState(UUID.randomUUID(), "app");
    assertThat(
            UnitEvaluation.evaluate(
                r, AlertMetric.RESPONSE_TIME_P95, s, MetricValue.unavailable(), 1_000))
        .isEmpty();
    assertThat(
            UnitEvaluation.evaluate(
                r, AlertMetric.RESPONSE_TIME_P95, s, MetricValue.noData(0), 1_000))
        .extracting(StateMachine.Change::to)
        .containsExactly(AlertState.NO_DATA);
  }
}
