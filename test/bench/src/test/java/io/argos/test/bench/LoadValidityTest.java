/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validity rules of a load set (WO-05 acceptance: off CV above 2% is INVALID, 01 §7.3 item 4; no
 * PASS without numbers, 18 §7.6; the agent ran with the requested exporter, 18 §5.4).
 */
@Tag("req:DSN-18-07")
class LoadValidityTest {

  private static Map<String, Object> analyze(double[] off, double[] on) {
    return LoadReport.analyze(
        Fixtures.loadSet("process", false, "default"),
        Fixtures.loadRuns(off, on),
        Bootstrap.DEFAULT_SEED);
  }

  @Test
  @DisplayName("off-run CPU CV of exactly 2.0% is valid (the limit is 'above 2%')")
  void cvAtTheLimitIsValid() {
    // mean 100, sample sd 2 (n - 1): CV = 0.02 exactly
    Map<String, Object> result = analyze(new double[] {98, 100, 102}, new double[] {101, 103, 105});
    assertThat(Json.number(result, "validity", "offCpuCv")).isEqualTo(0.02);
    assertThat(Json.at(result, "validity", "offCpuCvOk")).isEqualTo(true);
    assertThat(result.get("verdict")).isEqualTo("PASS");
  }

  @Test
  @DisplayName("off-run CPU CV just above 2% makes the set INVALID")
  void cvJustAboveTheLimitIsInvalid() {
    // sample sd 2.0002 around 100: CV 2.0002%
    Map<String, Object> result =
        analyze(new double[] {97.9998, 100, 102.0002}, new double[] {101, 103, 105});
    assertThat(Json.number(result, "validity", "offCpuCv")).isCloseTo(0.020002, within(1e-12));
    assertThat(Json.at(result, "validity", "offCpuCvOk")).isEqualTo(false);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString())
        .contains("off-run CPU CV 2.00% > 2% (01 7.3 item 4)");
  }

  @Test
  @DisplayName("one off run has no CV: INVALID, never PASS")
  void oneOffRunIsInvalid() {
    List<Map<String, Object>> runs =
        ReportTest.runs(
            new double[] {600}, new double[] {610}, new double[] {300.5}, new double[] {300.6});
    Map<String, Object> result =
        LoadReport.analyze(Fixtures.loadSet("process", false, "default"), runs, 42);
    assertThat(Json.number(result, "validity", "offCpuCv")).isNaN();
    assertThat(result.get("verdict")).isEqualTo("INVALID");
  }

  @Test
  @Tag("req:N-02")
  @DisplayName("a missing number (agent heap) makes the set INVALID instead of PASS")
  void missingNumberIsInvalid() {
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    for (Map<String, Object> r : runs) {
      if ("on".equals(r.get("variant"))) {
        Map<String, Object> memory = new LinkedHashMap<>(Json.object(r.get("memory")));
        memory.remove("agentHeapBytes");
        r.put("memory", memory);
      }
    }
    Map<String, Object> result =
        LoadReport.analyze(Fixtures.loadSet("process", false, "default"), runs, 42);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString()).contains("no data: N-02 agent heap");
    assertThat(Json.at(result, "comparisons", "on", "memory", "agentHeapMb", "verdict"))
        .isEqualTo("NO DATA");
  }

  @Test
  @Tag("req:N-01")
  @DisplayName("missing CPU seconds make the set INVALID")
  void missingCpuIsInvalid() {
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    Map<String, Object> measure = new LinkedHashMap<>(Json.object(runs.get(1).get("measure")));
    measure.put("appCpuSeconds", null);
    runs.get(1).put("measure", measure);
    Map<String, Object> result =
        LoadReport.analyze(Fixtures.loadSet("process", false, "default"), runs, 42);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString()).contains("no data: relative CPU");
  }

  @Test
  @DisplayName("an agent that started with another exporter than requested invalidates the set")
  void exporterMismatchIsInvalid() {
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    for (Map<String, Object> r : runs) {
      if ("on".equals(r.get("variant"))) {
        r.put("expectedExporter", "noop");
        r.put("agentExporter", "noop");
      }
    }
    Map<String, Object> set = Fixtures.loadSet("process", false, "default");
    assertThat(LoadReport.analyze(set, runs, 42).get("verdict")).isEqualTo("PASS");
    runs.get(3).put("agentExporter", "grpc");
    Map<String, Object> result = LoadReport.analyze(set, runs, 42);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString())
        .contains("run 4 (on): agent banner exporter=grpc, expected noop");
  }

  @Test
  @Tag("req:N-03")
  @DisplayName("HDR histograms replace k6 percentiles and add a bootstrap interval")
  void histogramsDriveLatencyWithBootstrap() {
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    for (Map<String, Object> r : runs) {
      LatencyHistogram h = new LatencyHistogram();
      // 1..20,000 µs uniformly (p99 = 19.8 ms), the agent runs 0.5 ms slower
      long shift = "on".equals(r.get("variant")) ? 500 : 0;
      for (long v = 1; v <= 20_000; v++) {
        h.recordMicros(v + shift);
      }
      r.put(LoadReport.HDR_ALL, h);
    }
    Map<String, Object> result =
        LoadReport.analyze(Fixtures.loadSet("process", false, "default"), runs, 42);
    Object p99 = Json.at(result, "comparisons", "on", "latency", "p99");
    assertThat(Json.at(result, "comparisons", "on", "latency", "source"))
        .isEqualTo("hdr (k6 raw output)");
    assertThat(Json.number(p99, "offMean")).isCloseTo(19.8, within(0.02));
    assertThat(Json.number(p99, "diffMean")).isCloseTo(0.5, within(0.02));
    List<Object> ci = Json.array(Json.at(p99, "diffBootstrapCi95"));
    assertThat(Json.number(ci.get(0))).isGreaterThan(0).isLessThan(0.5);
    assertThat(Json.number(ci.get(1))).isGreaterThan(0.5).isLessThan(1.0);
    assertThat(Json.at(p99, "noDifference")).isEqualTo(false);
    assertThat(Json.at(p99, "bootstrapSeed")).isEqualTo(42L);
    // the per-run table shows the HDR numbers
    Object first = Json.array(result.get("runs")).get(0);
    assertThat(Json.at(first, "latencySource")).isEqualTo("hdr");
    assertThat(Json.number(first, "p99")).isCloseTo(19.8, within(0.02));
  }
}
