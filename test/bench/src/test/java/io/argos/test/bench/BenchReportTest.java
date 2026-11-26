/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 18 §7.6 report: verdicts per requirement, template structure, files. */
@Tag("req:DSN-18-07")
class BenchReportTest {

  private static Map<String, Object> evaluate(List<BenchReport.SetData> sets) {
    return BenchReport.evaluate(sets, "2026-11-03-4a43e77", null, null);
  }

  private static Object verdict(Map<String, Object> model, String requirement) {
    return Json.at(model, "requirements", requirement, "verdict");
  }

  @Test
  @DisplayName("a complete valid run within every limit is PASS for all nine requirements")
  void completeRunPasses() {
    Map<String, Object> model = evaluate(Fixtures.completeSets(Fixtures.envRecord()));
    for (String n : BenchReport.REQUIREMENTS) {
      assertThat(verdict(model, n)).as(n).isEqualTo("PASS");
    }
    assertThat(model.get("verdict")).isEqualTo("PASS");
    assertThat(model.get("measuredRequirements")).isEqualTo(9);
    // N-01 numbers: +3% mean (exact fixture values)
    Object n01 = Json.at(model, "requirements", "N-01");
    assertThat(Json.number(Json.array(Json.at(n01, "checks")).get(0), "value"))
        .isEqualTo(618.0 / 600 - 1);
    assertThat(Json.at(model, "regression", "verdict")).isEqualTo("NOT COMPARED");
  }

  @Test
  @DisplayName("an invalid environment (governor override) makes every verdict INVALID")
  void invalidEnvironmentInvalidatesAll() {
    Map<String, Object> model =
        evaluate(
            Fixtures.completeSets(
                Fixtures.envRecord(
                    "CPU governor powersave is not performance (--allow-governor)")));
    for (String n : BenchReport.REQUIREMENTS) {
      assertThat(verdict(model, n)).as(n).isEqualTo("INVALID");
      assertThat(Json.at(model, "requirements", n, "outcome")).as(n).isEqualTo("within the limits");
    }
    assertThat(model.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(model.get("environmentProblems")).toString()).contains("governor");
  }

  @Test
  @DisplayName("a requirement without its set is INVALID (not measured), never PASS")
  void missingSetIsNotMeasured() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    sets.removeIf(s -> s.role().equals("vt"));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-28")).isEqualTo("INVALID");
    assertThat(Json.at(model, "requirements", "N-28", "outcome")).isEqualTo("no numbers");
    assertThat(Json.array(Json.at(model, "requirements", "N-28", "invalidReasons")).toString())
        .contains("not measured: no vt set");
    assertThat(verdict(model, "N-01")).isEqualTo("PASS");
    assertThat(model.get("verdict")).isEqualTo("INVALID");
    assertThat(model.get("measuredRequirements")).isEqualTo(8);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName("no PASS without numbers: a missing value invalidates the requirement")
  void noPassWithoutNumbers() {
    BenchReport.Requirement empty = new BenchReport.Requirement("N-99", "T", "c");
    assertThat(empty.verdict()).isEqualTo("INVALID");
    assertThat(empty.outcome()).isEqualTo("no numbers");
    BenchReport.Requirement r = new BenchReport.Requirement("N-99", "T", "c");
    r.checks.add(new BenchReport.Check("a", 1, 2, "ms"));
    assertThat(r.verdict()).isEqualTo("PASS");
    r.checks.add(new BenchReport.Check("b", Double.NaN, 2, "ms"));
    assertThat(r.verdict()).isEqualTo("INVALID");
    BenchReport.Requirement over = new BenchReport.Requirement("N-99", "T", "c");
    over.checks.add(new BenchReport.Check("a", 3, 2, "ms"));
    assertThat(over.verdict()).isEqualTo("FAIL");
    over.invalid.add("set x: protocol deviation");
    assertThat(over.verdict()).isEqualTo("INVALID");
    assertThat(over.outcome()).isEqualTo("over the limit");

    // A main set whose agent heap is missing: N-02 has no number, and the set is INVALID.
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    for (Map<String, Object> run : runs) {
      Map<String, Object> memory = new LinkedHashMap<>(Json.object(run.get("memory")));
      memory.remove("agentHeapBytes");
      run.put("memory", memory);
    }
    sets.set(
        0,
        Fixtures.setData(
            "main", Fixtures.loadSet("process", false, "default"), runs, Fixtures.envRecord()));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-02")).isEqualTo("INVALID");
    assertThat(Json.at(model, "requirements", "N-02", "outcome")).isEqualTo("no numbers");
    assertThat(verdict(model, "N-01")).isEqualTo("INVALID");
    assertThat(model.get("verdict")).isEqualTo("INVALID");
  }

  @Test
  @Tag("req:N-01")
  @DisplayName("a valid run over a limit is FAIL (and FAIL wins over INVALID overall)")
  void overTheLimitFails() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    sets.set(
        0,
        Fixtures.setData(
            "main",
            Fixtures.loadSet("process", false, "default"),
            Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {640, 646, 634}),
            Fixtures.envRecord()));
    sets.removeIf(s -> s.role().equals("vt"));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-01")).isEqualTo("FAIL");
    assertThat(verdict(model, "N-28")).isEqualTo("INVALID");
    assertThat(model.get("verdict")).isEqualTo("FAIL");
    // 18 §7.7: the N-xx FAIL is the regression verdict too, before any WARN
    assertThat(Json.at(model, "regression", "verdict")).isEqualTo("FAIL");
  }

  @Test
  @Tag("req:N-01")
  @DisplayName("a protocol deviation (smoke windows) makes the set's requirements INVALID")
  void protocolDeviationInvalidates() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    Map<String, Object> smoke = Fixtures.loadSet("process", false, "default");
    Map<String, Object> p = new LinkedHashMap<>(Json.object(smoke.get("parameters")));
    p.put("durationSeconds", 60);
    smoke.put("parameters", p);
    sets.set(
        0,
        Fixtures.setData(
            "main",
            smoke,
            Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612}),
            Fixtures.envRecord()));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-01")).isEqualTo("INVALID");
    assertThat(Json.at(model, "requirements", "N-01", "outcome")).isEqualTo("within the limits");
    assertThat(Json.array(Json.at(model, "requirements", "N-01", "invalidReasons")).toString())
        .contains("protocol deviation: window 60 s (protocol 600 s)");
    assertThat(verdict(model, "N-04")).isEqualTo("PASS"); // the startup set is fine
  }

  @Test
  @Tag("req:N-04")
  @DisplayName("startup: heavy app +10% and premain decide N-04, N-27 and N-30")
  void startupRequirements() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    // shop-heavy +3,300 ms on 30,000: +11%
    sets.set(
        3,
        Fixtures.setData(
            "startup",
            Fixtures.startupSet(),
            Fixtures.startupRuns(1500, 3300),
            Fixtures.envRecord()));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-04")).isEqualTo("FAIL");
    assertThat(verdict(model, "N-27")).isEqualTo("FAIL");
    assertThat(verdict(model, "N-30")).isEqualTo("PASS");
    assertThat(Json.at(Json.array(Json.at(model, "requirements", "N-27", "checks")).get(0), "what"))
        .isEqualTo("shop-heavy/default increase");
  }

  @Test
  @DisplayName("the Markdown follows the 8 parts of 18 §7.6 and ends with a readable summary")
  void markdownTemplate(@TempDir Path dir) throws IOException {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    Map<String, Object> model = evaluate(sets);
    List<String> graphs = BenchReport.writeGraphs(dir.resolve("r"), "r", sets);
    String md =
        BenchReport.markdown(
            model,
            sets,
            graphs,
            "test/bench/bench-protocol.sh --machine-profile x",
            List.of("a", "b"),
            dir,
            null);
    List<String> parts =
        List.of(
            "# Agent overhead benchmark 2026-11-03-4a43e77: PASS",
            "## 1. Environment (18 §7.1)",
            "## 2. Run parameters",
            "## 3. Raw results per run",
            "## 4. Statistics",
            "## 5. Memory, threads and startup",
            "## 6. Verdicts",
            "## 7. Graphs",
            "## 8. Reproduction",
            Regression.SUMMARY_START);
    int at = -1;
    for (String part : parts) {
      int next = md.indexOf(part);
      assertThat(next).as(part).isGreaterThan(at);
      at = next;
    }
    for (String n : BenchReport.REQUIREMENTS) {
      assertThat(md).contains("| " + n + " | ");
    }
    assertThat(md)
        .contains("| machine profile | argos-bench-app |")
        .contains("| 1 | 1 | off | 600.00 | 600000 |")
        .contains("relative CPU +3.00% (limit +5.00%)")
        .contains("test/bench/bench-protocol.sh --machine-profile x")
        .contains("![CPU time series of main](r/main-cpu-timeline.svg)");
    Path file = dir.resolve("report.md");
    Files.writeString(file, md, StandardCharsets.UTF_8);
    Map<String, Object> summary = Regression.readSummary(file);
    assertThat(summary.get("verdict")).isEqualTo("PASS");
    assertThat(summary.get("approved")).isEqualTo(false);
    assertThat(Json.at(summary, "requirements", "N-29")).isEqualTo("PASS");
    assertThat(Json.number(summary, "metrics", "relativeCpu")).isEqualTo(618.0 / 600 - 1);
    // graphs: SVG with both series and a TSV table view per load set
    String svg = Files.readString(dir.resolve("r/main-cpu-timeline.svg"), StandardCharsets.UTF_8);
    assertThat(svg)
        .startsWith("<svg")
        .contains("<polyline class=\"s0\"")
        .contains("<polyline class=\"s1\"");
    assertThat(dir.resolve("r/main-cpu-timeline.tsv"))
        .content()
        .startsWith("run\tvariant\tseconds\tcores\n");
    assertThat(dir.resolve("r/main-latency-cdf.svg")).content().contains("no data");
  }

  @Test
  @Tag("req:N-01")
  @Tag("req:N-26")
  @Tag("req:N-30")
  @DisplayName(
      "T-BLD-015f report: agent thread CPU, direct memory and premain come from the"
          + " self-diagnostics when the runs have them, with the source named")
  void selfDiagnosticsAreReadWithTheirSource() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    List<Map<String, Object>> runs =
        Fixtures.withSelfDiag(
            Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612}),
            2.4,
            3L * 1024 * 1024,
            310.0);
    sets.set(
        0,
        Fixtures.setData(
            "main", Fixtures.loadSet("process", false, "default"), runs, Fixtures.envRecord()));
    Object on = Json.at(sets.get(0).result(), "comparisons", "on");
    // N-01 auxiliary from agent_threads_cpu_seconds_total; jcmd (1.2 s) stays as a cross-check
    assertThat(Json.at(on, "agentThreadCpu", "source")).isEqualTo(LoadReport.AGENT_CPU_SELF_DIAG);
    assertThat(Json.number(on, "agentThreadCpu", "value"))
        .isCloseTo(100 * 2.4 / (600 * 12), within(1e-12));
    assertThat(Json.number(on, "agentThreadCpu", "jcmdHostPercentMax"))
        .isCloseTo(100 * 1.2 / (600 * 12), within(1e-12));
    assertThat(Json.number(on, "memory", "selfDiagDirectMemoryMb")).isEqualTo(3.0);
    assertThat(Json.number(on, "startup", "premainMedianMs")).isEqualTo(310.0);
    assertThat(Json.at(on, "startup", "premainSource")).isEqualTo(LoadReport.PREMAIN_SELF_DIAG);

    // startup starts that carry premainSource (written from the self-diagnostics)
    List<Map<String, Object>> starts = Fixtures.startupRuns(1500, 1800);
    for (Map<String, Object> s : starts) {
      if ("on".equals(s.get("variant"))) {
        s.put("premainSource", "selfdiag-file");
      }
    }
    sets.set(3, Fixtures.setData("startup", Fixtures.startupSet(), starts, Fixtures.envRecord()));
    Map<String, Object> model = evaluate(sets);
    assertThat(verdict(model, "N-01")).isEqualTo("PASS");
    assertThat(Json.array(Json.at(model, "requirements", "N-01", "notes")).toString())
        .contains("main: agent thread CPU from self-diagnostics agent_threads_cpu_seconds_total")
        .contains("jcmd cross-check 0.017% of host");
    assertThat(Json.array(Json.at(model, "requirements", "N-26", "notes")).toString())
        .contains("NMT Other increase (0.18 MB)")
        .contains("self-diagnostics direct_memory_bytes 3.00 MB (JVM-wide direct buffer pool");
    assertThat(Json.array(Json.at(model, "requirements", "N-30", "notes")).toString())
        .contains("premain source: self-diagnostics premain_ms")
        .contains("C-240 (7)");
    String md =
        BenchReport.markdown(model, sets, List.of(), "", List.of("main"), Path.of("out"), null);
    assertThat(md)
        .contains("| direct memory (self-diagnostics, max of runs) | 3.00 MB | reported |")
        .contains(
            "| premain median (load runs) | 310.0 ms | 500 ms | self-diagnostics premain_ms |")
        .contains("420.0 (self-diagnostics premain_ms)");

    // fallback: no self-diagnostics in the runs -> jcmd and the banner, named as such
    Map<String, Object> plain = evaluate(Fixtures.completeSets(Fixtures.envRecord()));
    assertThat(Json.array(Json.at(plain, "requirements", "N-01", "notes")).toString())
        .contains("agent thread CPU from jcmd Thread.print cpu= of the argos-* threads");
    assertThat(Json.array(Json.at(plain, "requirements", "N-26", "notes")).toString())
        .contains("no self-diagnostics direct_memory_bytes in this set");
    assertThat(Json.array(Json.at(plain, "requirements", "N-30", "notes")).toString())
        .contains("premain source: agent log banner");
  }

  @Test
  @Tag("req:N-01")
  @Tag("req:N-03")
  @DisplayName(
      "T-BLD-015e a grpc set is the transport cost section (no verdict) and never decides N-01")
  void grpcSetIsTransportCost() {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    // grpc: +5% (would FAIL N-01 if it were judged) against the noop main set's +3%
    sets.add(
        Fixtures.setData(
            "grpc",
            Fixtures.loadSet("grpc"),
            Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {630, 636.3, 623.7}),
            Fixtures.envRecord()));
    assertThat(sets.get(4).role()).isEqualTo("grpc");
    Map<String, Object> model = evaluate(sets);
    assertThat(model.get("verdict")).isEqualTo("PASS");
    assertThat(Json.array(Json.at(model, "requirements", "N-01", "checks"))).hasSize(3);
    Object tc = Json.at(model, "summary", "transportCost");
    assertThat(Json.number(tc, "relativeCpuGrpc")).isCloseTo(0.05, within(1e-9));
    assertThat(Json.number(tc, "relativeCpuNoop")).isCloseTo(0.03, within(1e-9));
    assertThat(Json.number(tc, "relativeCpuDeltaPoints")).isCloseTo(2.0, within(1e-9));
    assertThat(Json.at(tc, "verdict").toString()).contains("C-80");
    String md =
        BenchReport.markdown(model, sets, List.of(), "", List.of("main"), Path.of("out"), null);
    assertThat(md)
        .contains("### Transport cost (grpc - noop)")
        .contains("| relative CPU | +5.00% | +3.00% | +2.00 percentage points |")
        .contains("no verdict");
    // without a grpc set there is no section and no summary field
    Map<String, Object> plain = evaluate(Fixtures.completeSets(Fixtures.envRecord()));
    assertThat(Json.object(plain.get("summary"))).doesNotContainKey("transportCost");
    assertThat(BenchReport.transportCostSection(Map.of())).isEmpty();
  }

  @Test
  @DisplayName(
      "T-BLD-015g report: the OpenTelemetry reference shows what the OTLP black-hole receiver got")
  void otelReferenceSection() {
    List<Map<String, Object>> runs =
        Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {618, 624, 612});
    for (int i = 0; i < 3; i++) {
      Map<String, Object> otel = ReportTest.run(7 + i, "otel", 640 + i, 301.5, "PASS", 118);
      Map<String, Object> measure = new LinkedHashMap<>(Json.object(otel.get("measure")));
      measure.put("otlp", Map.of("requests", 120L, "bytes", 48_000L));
      otel.put("measure", measure);
      runs.add(otel);
    }
    Map<String, Object> set = Fixtures.loadSet("process", false, "default");
    Map<String, Object> p = new LinkedHashMap<>(Json.object(set.get("parameters")));
    p.put("variants", List.of("off", "on", "otel"));
    set.put("parameters", p);
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    sets.set(0, Fixtures.setData("main", set, runs, Fixtures.envRecord()));
    Map<String, Object> model = evaluate(sets);
    Object ref = Json.at(model, "summary", "reference");
    assertThat(Json.number(ref, "otlpRequests")).isEqualTo(360.0);
    assertThat(Json.number(ref, "otlpBytes")).isEqualTo(144_000.0);
    assertThat(Json.number(ref, "relativeCpu")).isCloseTo(641.0 / 600 - 1, within(1e-9));
    String section = BenchReport.referenceSection(Json.object(ref));
    assertThat(section)
        .contains("Reference: OpenTelemetry Java agent (18 §7.5, no verdict)")
        .contains("| main | +6.83% |")
        .contains("| 360 | 144000 |")
        .doesNotContain("got no OTLP request");
    Map<String, Object> silent = new LinkedHashMap<>(Json.object(ref));
    silent.put("otlpRequests", 0.0);
    assertThat(BenchReport.referenceSection(silent)).contains("got no OTLP request");
  }

  @Test
  @DisplayName(
      "T-BLD-015c the report states C-145 (4) in part 2, records encoding and the C-186 (2)"
          + " driver footnote against a baseline with other shop-boot3 drivers")
  void encodingAndDriverNotes(@TempDir Path dir) throws IOException {
    List<BenchReport.SetData> sets = Fixtures.completeSets(Fixtures.envRecord());
    Map<String, Object> first = Json.object(evaluate(sets).get("summary"));
    assertThat(first.get("encoding")).isEqualTo("protobuf");
    assertThat(first.get("exporter")).isEqualTo("noop");
    assertThat(first.get("machineProfile")).isEqualTo("argos-bench-app");
    assertThat(first.get("app")).isEqualTo("shop-boot3");
    assertThat(first.get("startedAt")).isEqualTo("2026-11-03T01:02:03Z");
    assertThat(Json.at(first, "requirementDetails", "N-01", "checks")).isNotNull();

    // the current shop-boot3 jar holds the WO-13 drivers; the approved baseline did not
    Path jar = dir.resolve("shop-boot3.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      for (String entry :
          List.of(
              "BOOT-INF/lib/h2-2.3.232.jar",
              "BOOT-INF/lib/mariadb-java-client-3.5.8.jar",
              "BOOT-INF/lib/ojdbc11-23.7.0.25.01.jar",
              "BOOT-INF/lib/httpcore5-h2-5.3.6.jar",
              "BOOT-INF/lib/spring-jdbc-6.2.19.jar",
              "BOOT-INF/lib/tomcat-jdbc-10.1.55.jar")) {
        zip.putNextEntry(new ZipEntry(entry));
        zip.closeEntry();
      }
    }
    assertThat(BenchReport.driversInJar(jar))
        .containsExactly(
            "h2-2.3.232.jar", "mariadb-java-client-3.5.8.jar", "ojdbc11-23.7.0.25.01.jar");
    Map<String, Object> startupSet = Fixtures.startupSet();
    startupSet.put(
        "inputs", Map.of("shop-boot3.jar", Map.of("path", jar.toString(), "sha256", "x")));
    sets.set(
        3,
        Fixtures.setData(
            "startup", startupSet, Fixtures.startupRuns(1500, 1800), Fixtures.envRecord()));
    Map<String, Object> baseline = new LinkedHashMap<>(first);
    baseline.put("approved", true);
    baseline.put("sampleAppDrivers", List.of("h2-2.3.232.jar"));
    Map<String, Object> model =
        BenchReport.evaluate(sets, "2026-11-10-4a43e77", baseline, "2026-11-03-4a43e77.md");
    assertThat(Json.at(model, "summary", "sampleAppDrivers"))
        .isEqualTo(
            List.of("h2-2.3.232.jar", "mariadb-java-client-3.5.8.jar", "ojdbc11-23.7.0.25.01.jar"));
    assertThat(Json.at(model, "regression", "verdict")).isEqualTo("OK");
    String md =
        BenchReport.markdown(model, sets, List.of(), "", List.of("main"), Path.of("out"), null);
    int part2 = md.indexOf("## 2. Run parameters");
    assertThat(md.indexOf(Regression.C145_NOTE)).isGreaterThan(part2);
    assertThat(md)
        .contains("Footnote (startup and transformed-class numbers across reports)")
        .contains("this report [h2, mariadb-java-client, ojdbc11]")
        .contains("C-186 (2)");
  }

  @Test
  @DisplayName("JDK feature version of java -version texts (comparability key)")
  void jdkVersions() {
    assertThat(BenchReport.jdkMajor("openjdk version \"21.0.10\" 2026-01-20 LTS\nOpenJDK ..."))
        .isEqualTo("21");
    assertThat(BenchReport.jdkMajor("java version \"1.8.0_452\"")).isEqualTo("8");
    assertThat(BenchReport.jdkMajor("17.0.2")).isEqualTo("17");
    assertThat(BenchReport.jdkMajor(null)).isNull();
    assertThat(BenchReport.jdkVersion("openjdk version \"21.0.10\" 2026-01-20"))
        .isEqualTo("21.0.10");
  }

  @Test
  @DisplayName("recorded harness commands are valid bash (part 8 reproduction)")
  void harnessCommandIsShellQuoted() {
    assertThat(
            OverheadBench.shellQuoted(
                "load",
                "--java",
                "C:\\Program Files\\Java\\bin\\java",
                "--label",
                "it's",
                "--x",
                ""))
        .isEqualTo("load --java 'C:\\Program Files\\Java\\bin\\java' --label 'it'\\''s' --x ''");
    assertThat(OverheadBench.shellQuoted("--rate", "1000", "--out", "build/bench/a-1"))
        .isEqualTo("--rate 1000 --out build/bench/a-1");
  }

  /** Writes a set directory as the harness does (set.json, runs/NN-variant/run.json). */
  private static Path writeSet(
      Path dir, Map<String, Object> set, List<Map<String, Object>> runs, Map<String, Object> env)
      throws IOException {
    FileTools.writeJson(dir.resolve("set.json"), set);
    FileTools.writeJson(dir.resolve(EnvRecord.FILE_NAME), env);
    int i = 0;
    for (Map<String, Object> r : runs) {
      Object index = r.get("index");
      String name =
          index == null
              ? String.format(Locale.ROOT, "%03d-%s", ++i, r.get("variant"))
              : String.format(Locale.ROOT, "%02d-%s", (Integer) index, r.get("variant"));
      FileTools.writeJson(dir.resolve("runs").resolve(name).resolve("run.json"), r);
    }
    return dir;
  }

  @Test
  @DisplayName("publish writes <date>-<commit7>.md from set directories and compares a baseline")
  void publishFromDirectories(@TempDir Path dir) throws Exception {
    Map<String, Object> env = Fixtures.envRecord();
    double[] off = {600, 606, 594};
    double[] on = {618, 624, 612};
    List<String> sets = new ArrayList<>();
    sets.add(
        writeSet(
                dir.resolve("main"),
                Fixtures.loadSet("process", false, "default"),
                Fixtures.loadRuns(off, on),
                env)
            .toString());
    sets.add(
        writeSet(
                dir.resolve("vt"),
                Fixtures.loadSet("process", true, "default"),
                Fixtures.loadRuns(off, on),
                env)
            .toString());
    sets.add(
        writeSet(
                dir.resolve("container"),
                Fixtures.loadSet("container", false, "default"),
                Fixtures.loadRuns(off, on),
                env)
            .toString());
    sets.add(
        writeSet(
                dir.resolve("startup"),
                Fixtures.startupSet(),
                Fixtures.startupRuns(1500, 1800),
                env)
            .toString());
    Path out = dir.resolve("docs-bench");
    int code =
        BenchReport.publish(
            Options.parse(
                List.of("--sets", String.join(",", sets), "--out", out.toString()),
                Set.of("overwrite")));
    assertThat(code).isZero();
    Path md = out.resolve("2026-11-03-4a43e77.md");
    assertThat(md).exists();
    assertThat(out.resolve("2026-11-03-4a43e77/summary.json")).exists();
    assertThat(out.resolve("2026-11-03-4a43e77/main-latency-cdf.tsv")).exists();

    // Second report with the first as the baseline: same numbers, regression OK.
    Path out2 = dir.resolve("docs-bench-2");
    int again =
        BenchReport.publish(
            Options.parse(
                List.of(
                    "--sets",
                    String.join(",", sets),
                    "--out",
                    out2.toString(),
                    "--baseline",
                    md.toString(),
                    "--name",
                    "second"),
                Set.of("overwrite")));
    assertThat(again).isZero();
    assertThat(out2.resolve("second.md"))
        .content()
        .contains("- Regression (18 §7.7): OK against 2026-11-03-4a43e77.md");

    // --baseline auto: nothing approved yet -> NOT COMPARED with the reason
    int third =
        BenchReport.publish(
            Options.parse(
                List.of(
                    "--sets",
                    String.join(",", sets),
                    "--out",
                    out.toString(),
                    "--baseline",
                    "auto",
                    "--name",
                    "2026-11-10-1234567"),
                BenchReport.PUBLISH_FLAGS));
    assertThat(third).isZero();
    assertThat(out.resolve("2026-11-10-1234567.md"))
        .content()
        .contains("- Regression (18 §7.7): NOT COMPARED")
        .contains("no approved report with the same machine profile");

    // a maintainer approves the first report; auto then picks it, --latest and --trend follow
    String text = Files.readString(md, StandardCharsets.UTF_8);
    Files.writeString(
        md, text.replace("\"approved\": false", "\"approved\": true"), StandardCharsets.UTF_8);
    int fourth =
        BenchReport.publish(
            Options.parse(
                List.of(
                    "--sets",
                    String.join(",", sets),
                    "--out",
                    out.toString(),
                    "--baseline",
                    "auto",
                    "--name",
                    "2026-11-10-1234567",
                    "--overwrite",
                    "--latest",
                    "--trend"),
                BenchReport.PUBLISH_FLAGS));
    assertThat(fourth).isZero();
    assertThat(out.resolve("2026-11-10-1234567.md"))
        .content()
        .contains("- Regression (18 §7.7): OK against 2026-11-03-4a43e77.md")
        .contains("baseline chosen by --baseline auto")
        .contains("--baseline " + md);
    assertThat(out.resolve("latest.md"))
        .content()
        .contains("[2026-11-10-1234567](2026-11-10-1234567.md)")
        .contains("regression (18 §7.7): OK");
    assertThat(out.resolve("trend.md")).content().contains("| 2 | [2026-11-10-1234567]");
    assertThat(out.resolve("trend.svg")).exists();
    assertThat(out.resolve("trend.tsv")).exists();
  }
}
