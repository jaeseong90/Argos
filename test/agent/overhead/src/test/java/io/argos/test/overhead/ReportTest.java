/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verdicts of the load and startup reports on synthetic runs. */
class ReportTest {

  /** One measured run as LoadSet writes it (only the fields the report reads). */
  static Map<String, Object> run(
      int index, String variant, double cpu, double p99, String gate, double nmtOtherKb) {
    Map<String, Object> measure = new LinkedHashMap<>();
    measure.put("appCpuSeconds", cpu);
    measure.put("wallSeconds", 600.0);
    measure.put("requests", 600000L);
    measure.put("appCpuMsPerRequest", cpu * 1000 / 600000);
    measure.put("dropped", 0L);
    measure.put("unexpectedRate", 0L);
    measure.put("latencyMs", Map.of("p50", 0.6, "p95", 20.0, "p99", p99, "p99.9", 305.0));
    measure.put(
        "routes",
        List.of(
            Map.of(
                "route",
                "orders.get",
                "latencyMs",
                Map.of("p99", variant.equals("on") ? 1.1 : 1.0))));
    measure.put("agentThreadCpuSeconds", variant.equals("on") ? 1.2 : 0.0);
    measure.put(
        "agentThreads",
        variant.equals("on") ? List.of("argos-logger", "argos-sender", "argos-watch") : List.of());
    measure.put("hostCpuPercentMean", 30.0);
    measure.put("loaderMachineCpuPercentMean", 30.0);
    List<Object> timeline = new ArrayList<>();
    for (int t = 0; t <= 600; t++) {
      timeline.add(List.of((double) t, cpu * t / 600));
    }
    measure.put("appCpuTimeline", timeline);
    measure.put("gate", Map.of("verdict", gate));
    Map<String, Object> memory = new LinkedHashMap<>();
    boolean on = variant.equals("on");
    memory.put("agentHeapBytes", on ? 2_000_000L : 0L);
    memory.put("metaspaceUsedKb", on ? 110_000L : 96_000L);
    memory.put("nmtCommittedKb", Map.of("Internal", on ? 2400.0 : 1300.0, "Other", nmtOtherKb));
    memory.put("nmtThreads", on ? 256L : 253L);
    memory.put("nmtTotalCommittedKb", on ? 1_400_000L : 1_360_000L);
    memory.put("classes", on ? 21_600L : 19_800L);
    memory.put("histogramTotalBytes", on ? 63L * 1024 * 1024 : 60L * 1024 * 1024);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("index", index);
    r.put("round", (index + 1) / 2);
    r.put("variant", variant);
    r.put("measure", measure);
    r.put("memory", memory);
    r.put("premainMs", on ? 420.0 : null);
    r.put("startup", Map.of("spawnToReadyMs", on ? 10_500L : 9_800L));
    r.put("before", Map.of("cpuPercent5s", 5.0));
    return r;
  }

  static Map<String, Object> set(String mode) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("mode", mode);
    parameters.put("appEnv", Map.of());
    parameters.put("cpus", 2);
    return Map.of(
        "parameters", parameters, "environment", Map.of("logicalCpus", 12), "label", "test");
  }

  static List<Map<String, Object>> runs(
      double[] off, double[] on, double[] offP99, double[] onP99) {
    List<Map<String, Object>> runs = new ArrayList<>();
    for (int i = 0; i < off.length; i++) {
      runs.add(run(2 * i + 1, "off", off[i], offP99[i], "PASS", 118));
      runs.add(run(2 * i + 2, "on", on[i], onP99[i], "PASS", 300));
    }
    return runs;
  }

  @Test
  void passWhenOverheadAndLatencyAreWithinLimits() {
    Map<String, Object> result =
        LoadReport.analyze(
            set("process"),
            runs(
                new double[] {600, 606, 594},
                new double[] {618, 624, 612},
                new double[] {300.5, 300.6, 300.4},
                new double[] {300.7, 300.9, 300.5}));
    assertThat(result.get("verdict")).isEqualTo("PASS");
    Object on = Json.at(result, "comparisons", "on");
    assertThat(Json.number(on, "cpu", "relative")).isCloseTo(0.03, within(1e-9));
    assertThat(Json.at(on, "cpu", "check", "verdict")).isEqualTo("PASS");
    assertThat(Json.number(on, "peak", "value")).isCloseTo(0.03, within(1e-6));
    assertThat(Json.at(on, "latency", "p99", "check", "verdict")).isEqualTo("PASS");
    assertThat(Json.number(on, "latency", "p99", "diffMean")).isCloseTo(0.2, within(1e-9));
    // the limit is max(1 ms, 5% of the off p99)
    assertThat(Json.number(on, "latency", "p99", "check", "limit"))
        .isCloseTo(0.05 * 300.5, within(1e-9));
    assertThat(Json.number(on, "memory", "metaspaceIncreaseMb", "value"))
        .isCloseTo(14000 / 1024.0, within(1e-9));
    assertThat(Json.number(on, "memory", "agentThreads", "value")).isEqualTo(3.0);
    assertThat(Json.number(on, "memory", "liveHeapIncreaseMb")).isCloseTo(3.0, within(1e-9));
    assertThat(Json.number(on, "agentThreadCpu", "value"))
        .isCloseTo(100 * 1.2 / (600 * 12), within(1e-9));
    assertThat(Json.number(on, "routesP99", "orders.get", "diffMean")).isCloseTo(0.1, within(1e-9));
    assertThat(LoadReport.markdown(set("process"), List.of(), result))
        .contains("| app CPU s (mean) | 600.00 | 618.00 | +3.00%");
  }

  @Test
  void failWhenRelativeCpuExceedsFivePercent() {
    Map<String, Object> result =
        LoadReport.analyze(
            set("process"),
            runs(
                new double[] {600, 606, 594},
                new double[] {640, 646, 634},
                new double[] {300.5, 300.6, 300.4},
                new double[] {300.5, 300.6, 300.4}));
    assertThat(result.get("verdict")).isEqualTo("FAIL");
    // +6.67% mean; the 1-minute peak (+6.67%) stays below its 8% limit
    assertThat(Json.array(result.get("reasons")).toString()).contains("relative CPU +6.67% > 5%");
    assertThat(Json.at(result, "comparisons", "on", "peak", "verdict")).isEqualTo("PASS");
  }

  @Test
  void containerLimitIsEightPercentWithoutPeakLimit() {
    Map<String, Object> result =
        LoadReport.analyze(
            set("container"),
            runs(
                new double[] {600, 606, 594},
                new double[] {640, 646, 634},
                new double[] {300.5, 300.6, 300.4},
                new double[] {300.5, 300.6, 300.4}));
    assertThat(result.get("verdict")).isEqualTo("PASS"); // +6.7% <= 8% (N-29)
    assertThat(Json.at(result, "comparisons", "on", "peak", "verdict")).isNull();
    // agent thread CPU relative to the container's 2 CPUs
    assertThat(Json.number(result, "comparisons", "on", "agentThreadCpu", "value"))
        .isCloseTo(100 * 1.2 / (600 * 2), within(1e-9));
  }

  @Test
  void invalidWhenOffRunsVaryMoreThanTwoPercent() {
    Map<String, Object> result =
        LoadReport.analyze(
            set("process"),
            runs(
                new double[] {600, 640, 560},
                new double[] {610, 650, 570},
                new double[] {300.5, 300.6, 300.4},
                new double[] {300.5, 300.6, 300.4}));
    assertThat(result.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.number(result, "validity", "offCpuCv"))
        .isCloseTo(Stats.cv(new double[] {600, 640, 560}), within(1e-12));
  }

  @Test
  void gateProblemsMakeTheSetInvalidOrFailed() {
    List<Map<String, Object>> runs =
        runs(
            new double[] {600, 606, 594},
            new double[] {618, 624, 612},
            new double[] {300.5, 300.6, 300.4},
            new double[] {300.7, 300.9, 300.5});
    runs.set(3, run(4, "on", 624, 300.9, "FAIL", 300));
    assertThat(LoadReport.analyze(set("process"), runs).get("verdict")).isEqualTo("FAIL");
    runs.set(2, run(3, "off", 606, 300.6, "FAIL", 118));
    assertThat(LoadReport.analyze(set("process"), runs).get("verdict")).isEqualTo("INVALID");
  }

  @Test
  void directMemoryLimit() {
    List<Map<String, Object>> runs =
        runs(
            new double[] {600, 606, 594},
            new double[] {618, 624, 612},
            new double[] {300.5, 300.6, 300.4},
            new double[] {300.7, 300.9, 300.5});
    runs.set(1, run(2, "on", 618, 300.7, "PASS", 118 + 3 * 9 * 1024));
    Map<String, Object> result = LoadReport.analyze(set("process"), runs);
    assertThat(result.get("verdict")).isEqualTo("FAIL"); // mean NMT Other +9 MB > 8 MB
    assertThat(Json.at(result, "comparisons", "on", "memory", "directMemoryIncreaseMb", "verdict"))
        .isEqualTo("FAIL");
  }

  @Test
  void k6GateFollowsC46() {
    Map<String, Object> measure = new LinkedHashMap<>();
    measure.put("dropped", 6000L);
    measure.put("unexpectedRate", 0L);
    assertThat(LoadSet.gate(measure, 1000, Duration.ofMinutes(10), 50).get("verdict"))
        .isEqualTo("PASS");
    measure.put("dropped", 6001L);
    assertThat(LoadSet.gate(measure, 1000, Duration.ofMinutes(10), 50).get("verdict"))
        .isEqualTo("FAIL");
    measure.put("dropped", 0L);
    measure.put("unexpectedRate", 0.00001);
    assertThat(LoadSet.gate(measure, 1000, Duration.ofMinutes(10), 50).get("verdict"))
        .isEqualTo("FAIL");
    measure.put("unexpectedRate", 0L);
    assertThat(LoadSet.gate(measure, 1000, Duration.ofMinutes(10), 80).get("verdict"))
        .isEqualTo("INVALID");
    Map<String, String> env = LoadSet.k6Env(1000, Duration.ofMinutes(10), 300, 600, true);
    assertThat(env)
        .containsEntry("DURATION", "600s")
        .containsEntry("WARMUP", "0s")
        .containsEntry("GATE", "on");
    assertThat(env)
        .containsEntry("MAX_UNEXPECTED_RATE", "0")
        .containsEntry("MAX_DROPPED_RATE", "0.01");
    assertThat(LoadSet.k6Env(1000, Duration.ofMinutes(3), 300, 600, false))
        .containsEntry("GATE", "off");
  }

  @Test
  void k6ImageComesFromRunBench() throws IOException {
    // Single source of the pinned digest: sample-apps/load/run-bench.sh.
    Path repo = Path.of("../../..").toAbsolutePath().normalize();
    assertThat(LoadSet.pinnedK6Image(repo)).startsWith("grafana/k6:").contains("@sha256:");
    assertThat(LoadSet.keyValues("exporter=noop, log.level = info"))
        .containsEntry("exporter", "noop")
        .containsEntry("log.level", "info");
    assertThat(LoadSet.words("  -Xms2g   -Xmx2g ")).containsExactly("-Xms2g", "-Xmx2g");
  }

  static Map<String, Object> start(
      String app, String cds, String variant, long port, Double premain) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("app", app);
    r.put("cds", cds);
    r.put("variant", variant);
    r.put("spawnToPortMs", port);
    r.put("spawnToReadyMs", port + 300);
    r.put("springStartedSeconds", port / 1000.0 - 0.5);
    r.put("premainMs", premain);
    r.put("before", Map.of("cpuPercent5s", 10.0));
    return r;
  }

  @Test
  void startupVerdicts() {
    List<Map<String, Object>> runs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      runs.add(start("shop-boot3", "default", "off", 7000 + i, null));
      runs.add(start("shop-boot3", "default", "on", 8500 + i, 450.0));
      runs.add(start("shop-heavy", "default", "off", 30_000 + i, null));
      runs.add(start("shop-heavy", "default", "on", 31_800 + i, 480.0));
    }
    Map<String, Object> result = StartupReport.analyze(runs);
    assertThat(result.get("verdict")).isEqualTo("PASS");
    List<Object> rows = Json.array(result.get("rows"));
    assertThat(Json.number(rows.get(0), "portIncreaseMs")).isEqualTo(1500.0);
    assertThat(Json.at(rows.get(1), "plus10PercentVerdict")).isEqualTo("PASS"); // +6%
    for (int i = 0; i < 12; i++) {
      runs.add(start("shop-heavy", "default", "on", 34_000, 520.0));
    }
    Map<String, Object> worse = StartupReport.analyze(runs);
    assertThat(worse.get("verdict")).isEqualTo("FAIL"); // median 34,000: +4.0 s, +13%; premain 520
    assertThat(Json.array(worse.get("reasons")).toString())
        .contains("> 2,000 ms", "> 10%", "premain");
  }

  @Test
  void writesReportFilesFromRawRuns(@TempDir Path dir) throws IOException {
    FileTools.writeJson(dir.resolve("set.json"), set("process"));
    List<Map<String, Object>> runs =
        runs(
            new double[] {600, 606, 594},
            new double[] {618, 624, 612},
            new double[] {300.5, 300.6, 300.4},
            new double[] {300.7, 300.9, 300.5});
    for (Map<String, Object> r : runs) {
      FileTools.writeJson(
          dir.resolve("runs")
              .resolve(
                  String.format(Locale.ROOT, "%02d-%s", (Integer) r.get("index"), r.get("variant")))
              .resolve("run.json"),
          r);
    }
    assertThat(LoadReport.write(dir)).isZero();
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("# Agent overhead load set: PASS");
    assertThat(Json.at(FileTools.readJson(dir.resolve("result.json")), "verdict"))
        .isEqualTo("PASS");
  }
}
