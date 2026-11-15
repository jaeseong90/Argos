/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The verdict of a query API bench case (ADR-0010 C-180): warm median ≤ target and cold ≤ 2 ×
 * target, both required. C-180 adds the cold condition decided by the PM; before it enforce judged
 * the warm median alone.
 */
@Tag("req:N-07")
@Tag("req:N-24")
class ApiBenchVerdictTest {

  @Test
  @DisplayName("T-NFR-07/T-NFR-24 C-180 warm within and cold within twice the target: PASS")
  void bothPass() {
    ApiBenchVerdict v = ApiBenchVerdict.of(1_500, 900, 1_000);
    assertThat(v.warmWithin()).isTrue();
    assertThat(v.coldWithin()).isTrue();
    assertThat(v.pass()).isTrue();
    assertThat(v.overall(true)).isEqualTo("PASS");
    assertThat(v.warm(true)).isEqualTo("PASS");
    assertThat(v.cold(true)).isEqualTo("PASS");
    assertThat(v.overall(false)).isEqualTo("reference (within)");
    // The bounds are inclusive: warm = target, cold = 2 × target.
    assertThat(ApiBenchVerdict.of(2_000, 1_000, 1_000).pass()).isTrue();
  }

  @Test
  @DisplayName(
      "T-NFR-07/T-NFR-24 C-180 warm within but cold above twice the target: FAIL (a slow first"
          + " screen after a restart)")
  void warmPassColdFail() {
    ApiBenchVerdict v = ApiBenchVerdict.of(2_000.1, 360, 1_000);
    assertThat(v.warmWithin()).isTrue();
    assertThat(v.coldWithin()).isFalse();
    assertThat(v.pass()).isFalse();
    assertThat(v.overall(true)).isEqualTo("FAIL");
    assertThat(v.warm(true)).isEqualTo("PASS");
    assertThat(v.cold(true)).isEqualTo("FAIL");
    assertThat(v.overall(false)).isEqualTo("reference (above)");
    assertThat(v.cold(false)).isEqualTo("above");
    assertThat(v.warm(false)).isEqualTo("within");
  }

  @Test
  @DisplayName("T-NFR-07/T-NFR-24 C-180 warm median above the target: FAIL even with a fast cold")
  void warmFail() {
    ApiBenchVerdict v = ApiBenchVerdict.of(500, 1_000.5, 1_000);
    assertThat(v.warmWithin()).isFalse();
    assertThat(v.coldWithin()).isTrue();
    assertThat(v.pass()).isFalse();
    assertThat(v.overall(true)).isEqualTo("FAIL");
    assertThat(v.warm(true)).isEqualTo("FAIL");
    assertThat(v.cold(true)).isEqualTo("PASS");
  }
}
