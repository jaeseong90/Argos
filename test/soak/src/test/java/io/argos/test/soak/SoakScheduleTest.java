/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-02")
class SoakScheduleTest {

  @Test
  @DisplayName(
      "T-SOAK-001a the 72-hour run keeps the intervals of 18 §11.2: 1 h, 10 min, batch every"
          + " hour, 72 hourly points")
  void full() {
    SoakSchedule s = SoakSchedule.of(Duration.ofDays(3));
    assertThat(s.scaledDown()).isFalse();
    assertThat(s.hourly()).isEqualTo(Duration.ofHours(1));
    assertThat(s.tenMinute()).isEqualTo(Duration.ofMinutes(10));
    assertThat(s.batch()).isEqualTo(Duration.ofHours(1));
    assertThat(s.warmUp()).isEqualTo(Duration.ofMinutes(10));
    assertThat(s.hourlyPoints()).isEqualTo(72);
    assertThat(s.tenMinutePoints()).isEqualTo(432);
    assertThat(s.describe()).contains("18 §11.2 intervals");
    // Longer than 72 hours: never above the nominal intervals.
    SoakSchedule longer = SoakSchedule.of(Duration.ofHours(100));
    assertThat(longer.hourly()).isEqualTo(Duration.ofHours(1));
    assertThat(longer.tenMinute()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  @DisplayName(
      "T-SOAK-001a a short run scales the intervals by duration / 72 h with lower bounds (15 min:"
          + " 60 s, 15 s, batch 120 s, warm-up 45 s) and says so")
  void scaled() {
    SoakSchedule s = SoakSchedule.of(Duration.ofMinutes(15));
    assertThat(s.scaledDown()).isTrue();
    assertThat(s.scale()).isEqualTo(15.0 / (72 * 60));
    assertThat(s.hourly()).isEqualTo(SoakSchedule.HOURLY_FLOOR);
    assertThat(s.tenMinute()).isEqualTo(SoakSchedule.TEN_MINUTE_FLOOR);
    assertThat(s.batch()).isEqualTo(SoakSchedule.BATCH_FLOOR);
    assertThat(s.warmUp()).isEqualTo(Duration.ofSeconds(45));
    assertThat(s.hourlyPoints()).isEqualTo(15);
    assertThat(s.tenMinutePoints()).isEqualTo(60);
    assertThat(s.describe()).contains("scaled by 0.00347 = 15m / 72h");
    // 12 hours: proportional, above the floors (10 min and 100 s).
    SoakSchedule half = SoakSchedule.of(Duration.ofHours(12));
    assertThat(half.hourly()).isEqualTo(Duration.ofMinutes(10));
    assertThat(half.tenMinute()).isEqualTo(Duration.ofSeconds(100));
    assertThat(half.warmUp()).isEqualTo(Duration.ofMinutes(2));
    assertThat(SoakSchedule.of(Duration.ofMinutes(5)).warmUp()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the p99 baseline is the first 6 hourly points, the first half of a run with"
          + " fewer than 12")
  void baseline() {
    assertThat(SoakSchedule.p99BaselinePoints(72)).isEqualTo(6);
    assertThat(SoakSchedule.p99BaselinePoints(12)).isEqualTo(6);
    assertThat(SoakSchedule.p99BaselinePoints(11)).isEqualTo(5);
    assertThat(SoakSchedule.p99BaselinePoints(2)).isEqualTo(1);
    assertThat(SoakSchedule.p99BaselinePoints(1)).isEqualTo(1);
  }
}
