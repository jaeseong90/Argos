/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.session.api.ClockOffsetProvider;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D-15 with ADR-0009 C-84 as fixed by ADR-0010 C-129. */
@Tag("req:D-15")
@Tag("req:C-129")
class TimestampGuardTest {

  private static final long RECEIVED = 1_767_225_599_000L; // 2025-12-31T23:59:59Z

  @Test
  @DisplayName("T-ACC-15a ts = now + 10 s is clamped to the receive time and counted record_clamp")
  void futureClamp() {
    TimestampGuard.Result r =
        TimestampGuard.guard(RECEIVED + 10_000, 0, RECEIVED, TimestampGuard.GRID_MS);
    assertThat(r.clamped()).isTrue();
    assertThat(r.corrected()).isFalse();
    assertThat(r.rejected()).isFalse();
    assertThat(r.tsMs()).isEqualTo(RECEIVED);
    assertThat(r.late()).isFalse();
    // Exactly 5 s ahead is kept.
    assertThat(TimestampGuard.guard(RECEIVED + 5_000, 0, RECEIVED, 1).tsMs())
        .isEqualTo(RECEIVED + 5_000);
  }

  @Test
  @DisplayName("T-ACC-15b ts = now - 8 days is rejected per record; 7 days exactly is kept")
  void oldRejected() {
    assertThat(
            TimestampGuard.guard(RECEIVED - TimeUnit.DAYS.toMillis(8), 0, RECEIVED, 1).rejected())
        .isTrue();
    TimestampGuard.Result seven =
        TimestampGuard.guard(RECEIVED - TimeUnit.DAYS.toMillis(7), 0, RECEIVED, 1);
    assertThat(seven.rejected()).isFalse();
    assertThat(seven.late()).isTrue();
  }

  @Test
  @DisplayName(
      "T-ACC-15c ts = now - 90 s is stored unchanged with late = 1; 60 s exactly is not late")
  void late() {
    TimestampGuard.Result r = TimestampGuard.guard(RECEIVED - 90_000, 0, RECEIVED, 1);
    assertThat(r.late()).isTrue();
    assertThat(r.tsMs()).isEqualTo(RECEIVED - 90_000);
    assertThat(TimestampGuard.guard(RECEIVED - 60_000, 0, RECEIVED, 1).late()).isFalse();
  }

  @Test
  @DisplayName(
      "C-129 an agent 60 s ahead: offset -60,000 -> correction 60 s, ts lands where it belongs")
  void correctedSession() {
    long correction = ClockOffsetProvider.correctionMs(OptionalLong.of(-60_000));
    assertThat(correction).isEqualTo(60_000);
    // The agent clock reads 00:00:30 of the next day; true time 23:59:30 of the receive day.
    long agentTs = RECEIVED + 31_000;
    TimestampGuard.Result r = TimestampGuard.guard(agentTs, correction, RECEIVED, 1);
    assertThat(r.corrected()).isTrue();
    assertThat(r.clamped()).isFalse();
    assertThat(r.tsMs()).isEqualTo(agentTs - 60_000);
    assertThat(Math.floorDiv(r.tsMs(), TimeUnit.DAYS.toMillis(1)))
        .isEqualTo(Math.floorDiv(RECEIVED, TimeUnit.DAYS.toMillis(1)));
  }

  @Test
  @DisplayName("C-129 a clock behind or 5 s ahead is never corrected (offset > -5,000)")
  void behindNotCorrected() {
    assertThat(ClockOffsetProvider.correctionMs(OptionalLong.of(90_000))).isZero();
    assertThat(ClockOffsetProvider.correctionMs(OptionalLong.of(-5_000))).isZero();
    TimestampGuard.Result r = TimestampGuard.guard(RECEIVED - 90_000, 0, RECEIVED, 1);
    assertThat(r.corrected()).isFalse();
    assertThat(r.late()).isTrue();
  }

  @Test
  @DisplayName(
      "C-129 (4) a corrected value still beyond receive + 5 s is clamped to the table grid")
  void clampToGrid() {
    long received = 1_767_225_597_123L;
    TimestampGuard.Result stat =
        TimestampGuard.guard(received + 120_000, 20_000, received, TimestampGuard.GRID_STAT_MS);
    assertThat(stat.corrected()).isTrue();
    assertThat(stat.clamped()).isTrue();
    assertThat(stat.tsMs()).isEqualTo(1_767_225_590_000L);
    TimestampGuard.Result metric =
        TimestampGuard.guard(received + 120_000, 20_000, received, TimestampGuard.GRID_METRIC_MS);
    assertThat(metric.tsMs()).isEqualTo(1_767_225_595_000L);
    // A 10 s multiple keeps a 10 s window and a 5 s grid point aligned.
    TimestampGuard.Result window =
        TimestampGuard.guard(1_767_225_600_000L, 20_000, received, TimestampGuard.GRID_STAT_MS);
    assertThat(window.tsMs() % 10_000).isZero();
  }
}
