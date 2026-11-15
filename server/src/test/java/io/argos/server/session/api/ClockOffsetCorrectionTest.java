/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The C-84 correction rule of ADR-0010 C-129 as defined once in {@link ClockOffsetProvider}. */
@Tag("req:C-84")
@Tag("req:C-129")
@Tag("req:D-02")
@Tag("req:D-15")
class ClockOffsetCorrectionTest {

  private static long correction(long offsetMs) {
    return ClockOffsetProvider.correctionMs(OptionalLong.of(offsetMs));
  }

  @Test
  @DisplayName(
      "offset is server - agent: an agent more than 5 s ahead (offset < -5,000) is corrected")
  void threshold() {
    assertThat(ClockOffsetProvider.correctionMs(OptionalLong.empty())).isZero();
    assertThat(correction(0)).isZero();
    assertThat(correction(-5_000)).isZero();
    assertThat(correction(-5_001)).isEqualTo(10_000);
    assertThat(correction(-3_000)).isZero();
  }

  @Test
  @DisplayName("the lead is rounded half up to 10 s: +60 s, +25 h and the 5 s boundaries")
  void rounding() {
    assertThat(correction(-60_000)).isEqualTo(60_000);
    assertThat(correction(-64_999)).isEqualTo(60_000);
    assertThat(correction(-65_000)).isEqualTo(70_000);
    assertThat(correction(-25L * 3_600_000)).isEqualTo(90_000_000);
    assertThat(correction(-25L * 3_600_000 - 4_999)).isEqualTo(90_000_000);
    assertThat(correction(Long.MIN_VALUE) % ClockOffsetProvider.CORRECTION_STEP_MS).isZero();
    assertThat(correction(Long.MIN_VALUE)).isPositive();
  }

  @Test
  @DisplayName("a clock behind the server (offset > 0) is never corrected, however far (C-129 (5))")
  void behindIsNeverCorrected() {
    assertThat(correction(1)).isZero();
    assertThat(correction(60_000)).isZero();
    assertThat(correction(8L * 24 * 3_600_000)).isZero();
    assertThat(correction(Long.MAX_VALUE)).isZero();
  }

  @Test
  @DisplayName("tsCorrectionMs applies the rule to the instance's current median")
  void perInstance() {
    ClockOffsetProvider provider =
        instanceId -> instanceId == 1 ? OptionalLong.of(-61_000) : OptionalLong.empty();
    assertThat(provider.tsCorrectionMs(1)).isEqualTo(60_000);
    assertThat(provider.tsCorrectionMs(2)).isZero();
  }
}
