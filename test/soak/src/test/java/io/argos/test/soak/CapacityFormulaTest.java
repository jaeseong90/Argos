/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-19")
class CapacityFormulaTest {

  @Test
  @DisplayName(
      "T-SOAK-001a N-19 bytes per row of 13 §14: xlog 70, profile 25, metric 4, stats 30,"
          + " rollups 60 (app and service 300), other tables outside the formula")
  void bytesPerRow() {
    assertThat(CapacityFormula.bytesPerRow("xlog")).hasValue(70);
    assertThat(CapacityFormula.bytesPerRow("profile_step")).hasValue(25);
    assertThat(CapacityFormula.bytesPerRow("metric")).hasValue(4);
    assertThat(CapacityFormula.bytesPerRow("sql_stat_raw")).hasValue(30);
    assertThat(CapacityFormula.bytesPerRow("ext_stat_raw")).hasValue(30);
    assertThat(CapacityFormula.bytesPerRow("sql_stat_1m")).hasValue(60);
    assertThat(CapacityFormula.bytesPerRow("metric_1h")).hasValue(60);
    assertThat(CapacityFormula.bytesPerRow("service_stat_1m")).hasValue(300);
    assertThat(CapacityFormula.bytesPerRow("app_stat_1h")).hasValue(300);
    assertThat(CapacityFormula.bytesPerRow("sql_dict")).isEmpty();
    assertThat(CapacityFormula.bytesPerRow("app_stat_1m_rebuild")).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the formula reproduces 17 §7.4: 500 TPS for a day of xlog is 3.0 GB; growth"
          + " and per-day scaling")
  void formula() {
    long rows = 500L * 86_400;
    double expected = CapacityFormula.expectedBytes(Map.of("xlog", rows, "sql_dict", 99L));
    assertThat(expected / 1e9).isCloseTo(3.024, within(1e-9));
    assertThat(CapacityFormula.perDay(1_000, 3_600)).isEqualTo(24_000);
    Map<String, Long> growth =
        CapacityFormula.growth(Map.of("xlog", 10L, "metric", 5L), Map.of("xlog", 30L));
    assertThat(growth).containsEntry("xlog", 20L).containsEntry("metric", -5L);
    // A shrinking table (merges dropped rows) never adds negative expected bytes.
    assertThat(CapacityFormula.expectedBytes(growth)).isEqualTo(20 * 70.0);
    assertThat(CapacityFormula.formulaBytes(Map.of("xlog", 100L, "sql_dict", 900L))).isEqualTo(100);
  }

  @Test
  @DisplayName("T-SOAK-001a the verdict is actual / expected within 0.70..1.30 (±30%)")
  void tolerance() {
    assertThat(CapacityFormula.withinTolerance(CapacityFormula.ratio(130, 100))).isTrue();
    assertThat(CapacityFormula.withinTolerance(CapacityFormula.ratio(70, 100))).isTrue();
    assertThat(CapacityFormula.withinTolerance(CapacityFormula.ratio(131, 100))).isFalse();
    assertThat(CapacityFormula.withinTolerance(CapacityFormula.ratio(69, 100))).isFalse();
    assertThat(CapacityFormula.ratio(10, 0)).isNaN();
    assertThat(CapacityFormula.withinTolerance(Double.NaN)).isFalse();
  }
}
