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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Gate 1 fragment of T-NFR-01..04 and 26..30 from report summaries. */
@Tag("req:DSN-18-07")
@Tag("req:N-01")
@Tag("req:N-02")
@Tag("req:N-03")
@Tag("req:N-04")
@Tag("req:N-26")
@Tag("req:N-27")
@Tag("req:N-28")
@Tag("req:N-29")
@Tag("req:N-30")
class Gate1ReportTest {

  /** The summary of a report over {@code sets}, as read back from the published Markdown. */
  private static Map<String, Object> summary(String name, List<BenchReport.SetData> sets) {
    Map<String, Object> model = BenchReport.evaluate(sets, name, null, null);
    return Json.object(Json.parse(Json.write(model.get("summary"))));
  }

  private static Object row(Map<String, Object> model, int index) {
    return Json.array(model.get("rows")).get(index);
  }

  private static Map<String, String> verdicts(Map<String, Object> model) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Object r : Json.array(model.get("rows"))) {
      out.put(String.valueOf(Json.at(r, "test")), String.valueOf(Json.at(r, "verdict")));
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-BLD-015e nine rows T-NFR-01..04, 26..30 with numbers, verdicts, evidence and the person"
          + " checks (C-240 (7), C-244 (7), C-245 (1)) and C-145 (4)")
  void nineRowsFromAValidReport() {
    Map<String, Object> model =
        Gate1Report.build(
            List.of(summary("2026-11-03-4a43e77", Fixtures.completeSets(Fixtures.envRecord()))));
    assertThat(verdicts(model).keySet())
        .containsExactly(
            "T-NFR-01",
            "T-NFR-02",
            "T-NFR-03",
            "T-NFR-04",
            "T-NFR-26",
            "T-NFR-27",
            "T-NFR-28",
            "T-NFR-29",
            "T-NFR-30");
    assertThat(verdicts(model).values()).containsOnly("PASS");
    assertThat(model.get("verdict")).isEqualTo("PASS");
    assertThat(Json.array(Json.at(row(model, 0), "numbers")).toString())
        .contains("relative CPU +3.00% (limit +5.00%)");
    assertThat(Json.array(Json.at(row(model, 0), "evidence")).toString())
        .contains("2026-11-03-4a43e77 (main (main))");
    String md = Gate1Report.markdown(model);
    for (String id : verdicts(model).keySet()) {
      assertThat(md).contains("| " + id + " | ");
    }
    assertThat(md)
        .startsWith("# Gate 1, agent overhead: PASS")
        .contains(Regression.C145_NOTE)
        .contains("## Still needed from a person")
        .contains("ADR-0010 C-240 (7)")
        .contains("ADR-0010 C-244 (7)")
        .contains("ADR-0010 C-245 (1)")
        .contains(
            "| 2026-11-03-4a43e77 | PASS | no | argos-bench-app | noop (encoding protobuf) |");
  }

  @Test
  @DisplayName(
      "T-BLD-015e an INVALID environment propagates: every row INVALID with its reasons, never"
          + " PASS")
  void invalidPropagates() {
    Map<String, Object> model =
        Gate1Report.build(
            List.of(
                summary(
                    "2026-11-26-abcdef0",
                    Fixtures.completeSets(
                        Fixtures.envRecord("shared machine (--allow-shared, smoke)")))));
    assertThat(verdicts(model).values()).containsOnly("INVALID");
    assertThat(model.get("verdict")).isEqualTo("INVALID");
    assertThat(Json.array(Json.at(row(model, 0), "reasons")).toString())
        .contains("2026-11-26-abcdef0: set main: environment: shared machine");
    // numbers are still shown (what was measured), the verdict stays INVALID
    assertThat(Json.array(Json.at(row(model, 0), "numbers"))).isNotEmpty();
    assertThat(Json.array(Json.at(row(model, 0), "evidence"))).isEmpty();
  }

  @Test
  @DisplayName("T-BLD-015e no PASS without numbers, whatever a summary claims")
  void noPassWithoutNumbers() {
    // a summary from before WO-51: verdicts only
    Map<String, Object> old = new LinkedHashMap<>();
    old.put("name", "2026-11-01-0000000");
    Map<String, Object> claimed = new LinkedHashMap<>();
    for (String n : BenchReport.REQUIREMENTS) {
      claimed.put(n, "PASS");
    }
    old.put("requirements", claimed);
    Map<String, Object> model = Gate1Report.build(List.of(old));
    assertThat(verdicts(model).values()).containsOnly("INVALID");
    assertThat(Json.array(Json.at(row(model, 0), "reasons")).toString())
        .contains("its PASS is not taken without numbers");

    // a detail that says PASS with a missing number
    Map<String, Object> s =
        summary("2026-11-03-4a43e77", Fixtures.completeSets(Fixtures.envRecord()));
    Map<String, Object> n01 = Json.object(Json.at(s, "requirementDetails", "N-01"));
    List<Object> checks = new ArrayList<>(Json.array(n01.get("checks")));
    Map<String, Object> first = new LinkedHashMap<>(Json.object(checks.get(0)));
    first.put("value", null);
    checks.set(0, first);
    n01.put("checks", checks);
    Map<String, Object> details = Json.object(s.get("requirementDetails"));
    details.put("N-01", n01);
    s.put("requirementDetails", details);
    Map<String, Object> tampered = Gate1Report.build(List.of(s));
    assertThat(verdicts(tampered).get("T-NFR-01")).isEqualTo("INVALID");
    assertThat(Json.array(Json.at(row(tampered, 0), "reasons")).toString())
        .contains("PASS without every number is not taken");
    assertThat(Gate1Report.build(List.of()).get("verdict")).isEqualTo("INVALID");
  }

  @Test
  @DisplayName(
      "T-BLD-015e several reports: a valid FAIL wins, a requirement missing in one report is"
          + " taken from the other")
  void severalReports() {
    // report A: main over the limit (FAIL N-01), no vt set (N-28 INVALID)
    List<BenchReport.SetData> a = Fixtures.completeSets(Fixtures.envRecord());
    a.set(
        0,
        Fixtures.setData(
            "main",
            Fixtures.loadSet("process", false, "default"),
            Fixtures.loadRuns(new double[] {600, 606, 594}, new double[] {640, 646, 634}),
            Fixtures.envRecord()));
    a.removeIf(s -> s.role().equals("vt"));
    // report B: everything within the limits
    List<BenchReport.SetData> b = Fixtures.completeSets(Fixtures.envRecord());
    Map<String, Object> model =
        Gate1Report.build(
            List.of(summary("2026-11-03-aaaaaaa", a), summary("2026-11-10-bbbbbbb", b)));
    Map<String, String> v = verdicts(model);
    assertThat(v.get("T-NFR-01")).isEqualTo("FAIL");
    assertThat(v.get("T-NFR-28")).isEqualTo("PASS");
    assertThat(Json.array(Json.at(row(model, 6), "evidence")).toString())
        .contains("2026-11-10-bbbbbbb");
    assertThat(model.get("verdict")).isEqualTo("FAIL");
    assertThat(Json.array(Json.at(row(model, 0), "evidence")).toString())
        .contains("2026-11-03-aaaaaaa");
  }

  @Test
  @DisplayName("T-BLD-015e gate1 reads published reports and writes FILE.md and FILE.json")
  void command(@TempDir Path dir) throws IOException {
    Map<String, Object> s =
        summary("2026-11-26-abcdef0", Fixtures.completeSets(Fixtures.envRecord("smoke")));
    Path report = dir.resolve("2026-11-26-abcdef0.md");
    Files.writeString(report, "# r\n\n" + Regression.summaryComment(s), StandardCharsets.UTF_8);
    Path out = dir.resolve("gate/gate1.md");
    int code =
        Gate1Report.run(
            Options.parse(
                List.of("--reports", report.toString(), "--out", out.toString()), Set.of()));
    assertThat(code).isEqualTo(4);
    assertThat(out).content().startsWith("# Gate 1, agent overhead: INVALID");
    assertThat(Json.at(FileTools.readJson(dir.resolve("gate/gate1.json")), "verdict"))
        .isEqualTo("INVALID");
  }
}
