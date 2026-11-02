/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;

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
  }
}
