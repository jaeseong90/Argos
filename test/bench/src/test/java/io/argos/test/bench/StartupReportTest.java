/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Startup set rules added by WO-05: median of 10 (C-49) and no PASS without starts (WO-02 H-1). */
@Tag("req:DSN-18-07")
class StartupReportTest {

  @Test
  @Tag("req:N-04")
  @DisplayName("WO-02 H-1: a startup set without starts is INVALID, not PASS")
  void emptySetIsInvalid() {
    Map<String, Object> result = StartupReport.analyze(List.of());
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString()).contains("no starts in the set");
  }

  @Test
  @Tag("req:N-04")
  @DisplayName("off starts only (no agent starts) is INVALID")
  void noAgentStartsIsInvalid() {
    List<Map<String, Object>> runs = new ArrayList<>();
    runs.add(ReportTest.start("shop-boot3", "default", "off", 7000, null));
    assertThat(StartupReport.analyze(runs).get("verdict")).isEqualTo("INVALID");
  }

  @Test
  @Tag("req:N-04")
  @DisplayName("an agent cell without off starts has no number: INVALID")
  void cellWithoutOffStartsIsInvalid() {
    List<Map<String, Object>> runs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      runs.add(ReportTest.start("shop-boot3", "default", "on", 8000 + i, 420.0));
    }
    Map<String, Object> result = StartupReport.analyze(runs);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString())
        .contains("no data (port times off 0, on 10)");
  }

  @Test
  @DisplayName("agent starts without a premain time in the agent log are INVALID for N-30")
  void missingPremainIsInvalid() {
    List<Map<String, Object>> runs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      runs.add(ReportTest.start("shop-boot3", "default", "off", 7000 + i, null));
      runs.add(ReportTest.start("shop-boot3", "default", "on", 8000 + i, null));
    }
    Map<String, Object> result = StartupReport.analyze(runs);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.at(Json.array(result.get("rows")).get(0), "premainVerdict"))
        .isEqualTo("NO DATA");
  }

  @Test
  @Tag("req:N-04")
  @DisplayName("C-49: the increase is the difference of the medians of 10 starts")
  void medianOfTenStarts() {
    List<Map<String, Object>> runs = new ArrayList<>();
    long[] off = {7200, 7010, 7090, 7400, 7050, 7120, 7060, 9000, 7080, 7100};
    long[] on = {8700, 8510, 8590, 8900, 8550, 8620, 8560, 12000, 8580, 8600};
    for (int i = 0; i < 10; i++) {
      runs.add(ReportTest.start("shop-boot3", "default", "off", off[i], null));
      runs.add(ReportTest.start("shop-boot3", "default", "on", on[i], 410.0 + i));
    }
    Object row = Json.array(StartupReport.analyze(runs).get("rows")).get(0);
    // sorted off: ... 7090, 7100 | ... median (7090 + 7100) / 2 = 7095; on: (8590 + 8600) / 2
    assertThat(Json.number(row, "offPortMedianMs")).isEqualTo(7095.0);
    assertThat(Json.number(row, "onPortMedianMs")).isEqualTo(8595.0);
    assertThat(Json.number(row, "portIncreaseMs")).isEqualTo(1500.0);
    assertThat(Json.number(row, "premainMedianMs")).isEqualTo(414.5);
    assertThat(Json.at(row, "n")).isEqualTo(10);
    assertThat(Stats.median(new double[] {5, 1, 4, 2, 3, 6, 8, 7, 10, 9})).isEqualTo(5.5);
  }
}
