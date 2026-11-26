/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    assertThat(Json.array(result.get("reasons")).toString())
        .contains("off-run CPU CV not available (fewer than 2 off runs")
        .doesNotContain("NaN");
    assertThat(LoadReport.markdown(Fixtures.loadSet("process", false, "default"), runs, result))
        .contains("Off-run CPU CV: not available (fewer than 2 off runs) (limit 2%).")
        .doesNotContain("NaN");
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

  private static Map<String, Object> window(long requests, double unexpectedRate) {
    Map<String, Object> measure = new LinkedHashMap<>();
    measure.put("requests", requests);
    measure.put("dropped", 0L);
    measure.put("unexpectedRate", unexpectedRate);
    return measure;
  }

  @Test
  @DisplayName(
      "T-BLD-015h loader socket errors make the run INVALID, not FAIL (H-2, ADR-0009 C-80, C-46)")
  void loaderSocketErrorsAreInvalid() {
    Duration tenMinutes = Duration.ofMinutes(10);
    // 600,000 requests, 30 failed, all of them k6's own "cannot assign requested address".
    Map<String, Object> gate =
        LoadSet.gate(window(600_000, 30 / 600_000.0), 1000, tenMinutes, 50, 30);
    assertThat(gate.get("verdict")).isEqualTo("INVALID");
    assertThat(gate.get("unexpectedOk")).isEqualTo(true);
    assertThat(gate.get("unexpectedRateBeyondLoader")).isEqualTo(0.0);
    assertThat(gate.get("loaderSocketErrors")).isEqualTo(30L);
    assertThat(String.valueOf(gate.get("reason")))
        .contains("30 loader-side socket errors at dial (H-2 provisional rule, ADR-0009 C-80)");

    // Failures beyond the loader's own: still not a FAIL of the agent, the window is not valid.
    gate = LoadSet.gate(window(600_000, 36 / 600_000.0), 1000, tenMinutes, 50, 30);
    assertThat(gate.get("verdict")).isEqualTo("INVALID");
    assertThat(gate.get("unexpectedOk")).isEqualTo(false);
    assertThat(Json.number(gate.get("unexpectedRateBeyondLoader")) * 600_000)
        .isCloseTo(6, within(1e-6));

    // No loader errors: C-46 unchanged (unexpected outcomes FAIL), and -1 (unknown) changes
    // nothing.
    assertThat(LoadSet.gate(window(600_000, 1e-5), 1000, tenMinutes, 50, 0).get("verdict"))
        .isEqualTo("FAIL");
    assertThat(LoadSet.gate(window(600_000, 1e-5), 1000, tenMinutes, 50, -1))
        .containsEntry("verdict", "FAIL")
        .doesNotContainKey("loaderSocketErrors");
    assertThat(LoadSet.gate(window(600_000, 0), 1000, tenMinutes, 50, 0).get("verdict"))
        .isEqualTo("PASS");
    // A saturated loader stays INVALID with its reason.
    assertThat(
            String.valueOf(LoadSet.gate(window(600_000, 0), 1000, tenMinutes, 85, 0).get("reason")))
        .contains("load generator saturated (CPU 85%)");

    // The set report carries the gate's reason, so the H-2 cause is visible in report.md.
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    Map<String, Object> measure = new LinkedHashMap<>(Json.object(runs.get(1).get("measure")));
    measure.put("gate", LoadSet.gate(window(600_000, 30 / 600_000.0), 1000, tenMinutes, 50, 30));
    runs.get(1).put("measure", measure);
    Map<String, Object> result =
        LoadReport.analyze(Fixtures.loadSet("process", false, "default"), runs, 42);
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(result.get("reasons")).toString())
        .contains("k6 gate INVALID (C-46): 30 loader-side socket errors at dial (H-2");
  }

  @Test
  @DisplayName(
      "T-BLD-015h loader errors come from the raw re-aggregation, else from the k6 log, else unknown")
  void loaderErrorSources(@TempDir Path dir) throws IOException {
    Map<String, Object> hdr = new LinkedHashMap<>();
    hdr.put("failedPoints", 5L);
    hdr.put("loaderSocketErrors", 4L);
    hdr.put("failureSamples", Map.of("dial tcp: connect: cannot assign requested address", 4L));
    LoadSet.LoaderErrors fromRaw = LoadSet.loaderErrors(dir, "k6", hdr);
    assertThat(fromRaw.count()).isEqualTo(4);
    assertThat(fromRaw.failed()).isEqualTo(5);
    assertThat(fromRaw.source()).startsWith(K6Raw.FILE_NAME);
    assertThat(fromRaw.toJson()).containsEntry("loaderSocketErrors", 4L);

    LoadSet.LoaderErrors none = LoadSet.loaderErrors(dir, "k6", null);
    assertThat(none.count()).isEqualTo(-1);
    assertThat(none.toJson()).containsEntry("loaderSocketErrors", null);

    Files.writeString(
        dir.resolve("k6.log"),
        "level=warning msg=\"Request Failed\" error=\"dial tcp 127.0.0.1:1: connect: cannot assign"
            + " requested address\"\n",
        StandardCharsets.UTF_8);
    // A raw re-aggregation that failed (no raw file) falls back to the log.
    LoadSet.LoaderErrors fromLog =
        LoadSet.loaderErrors(dir, "k6", Map.of("error", "k6 wrote no raw output"));
    assertThat(fromLog.count()).isEqualTo(1);
    assertThat(fromLog.source()).isEqualTo("k6.log (k6 'Request Failed' warnings)");
  }
}
