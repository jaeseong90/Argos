/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 18 §7.7: latest.md and the trend over published reports. */
@Tag("req:DSN-18-07")
class TrendTest {

  private static void report(Path dir, Map<String, Object> summary) throws IOException {
    Files.writeString(
        dir.resolve(summary.get("name") + ".md"),
        "# report\n\n" + Regression.summaryComment(summary),
        StandardCharsets.UTF_8);
  }

  private static Map<String, Object> summary(
      String name, String machine, String encoding, double cpu, double p99) {
    return RegressionTest.summary(name, true, machine, "noop", encoding, cpu, p99);
  }

  @Test
  @Tag("req:N-01")
  @Tag("req:N-03")
  @DisplayName(
      "T-BLD-015c the trend plots the newest configuration, leaves out WO-02 era and other"
          + " configurations with the reason, and footnotes a shop-boot3 driver change")
  void trendFromReports(@TempDir Path dir) throws IOException {
    Map<String, Object> old =
        summary("2026-10-30-aaaaaaa", "argos-bench-app", "protobuf", 0.04, 0.3);
    old.put("sampleAppDrivers", List.of("h2-2.3.232.jar", "postgresql-42.7.11.jar"));
    report(dir, old);
    Map<String, Object> mid =
        summary("2026-11-08-bbbbbbb", "argos-bench-app", "protobuf", 0.035, 0.25);
    mid.put(
        "sampleAppDrivers",
        List.of("h2-2.3.232.jar", "mariadb-java-client-3.5.8.jar", "postgresql-42.7.11.jar"));
    report(dir, mid);
    Map<String, Object> newest =
        summary("2026-11-15-ccccccc", "argos-bench-app", "protobuf", -0.002, 0.2);
    newest.put("approved", false);
    newest.put("sampleAppDrivers", mid.get("sampleAppDrivers"));
    report(dir, newest);
    report(dir, summary("2026-11-01-ddddddd", "dev-laptop-shared", "protobuf", 0.09, 1.0));
    report(dir, summary("2026-10-20-eeeeeee", "argos-bench-app", "jsonl", 0.03, 0.1));
    report(dir, summary("2026-10-21-fffffff", "argos-bench-app", null, 0.03, 0.1));
    Files.createDirectories(dir.resolve("wo02-overhead-2026-10-24/main-1"));
    Files.writeString(dir.resolve("README.md"), "# docs/bench\n", StandardCharsets.UTF_8);

    Path out = dir.resolve("out");
    Map<String, Object> model = Trend.write(dir, out);
    List<Object> points = Json.array(model.get("points"));
    assertThat(points).hasSize(3);
    assertThat(points.stream().map(p -> Json.at(p, "name")))
        .containsExactly("2026-10-30-aaaaaaa", "2026-11-08-bbbbbbb", "2026-11-15-ccccccc");
    assertThat(Json.at(model, "configuration", "machineProfile")).isEqualTo("argos-bench-app");

    String md = Files.readString(out.resolve("trend.md"), StandardCharsets.UTF_8);
    assertThat(md)
        .contains(Regression.C145_NOTE)
        .contains("| 1 | [2026-10-30-aaaaaaa](2026-10-30-aaaaaaa.md) | yes | PASS |")
        .contains("| 3 | [2026-11-15-ccccccc](2026-11-15-ccccccc.md) | no | PASS |")
        .contains("+4.00%")
        .contains("-0.20%")
        // the oldest point predates the WO-13 drivers: footnote [1] (C-186 (2))
        .contains(" [1] |")
        .contains("[1] shop-boot3 JDBC drivers: baseline [h2, postgresql], this report [h2,")
        .contains("C-186 (2)")
        .contains("| wo02-overhead-2026-10-24/ | WO-02 era directory")
        .contains("| 2026-10-20-eeeeeee.md | JSONL encoding (WO-02 era): never compared")
        .contains("| 2026-10-21-fffffff.md | no encoding in its summary")
        .contains("| 2026-11-01-ddddddd.md | other configuration: machineProfile differs");
    // only one footnote mark: the newer points have the newest drivers
    assertThat(md.split(" \\[1] \\|", -1)).hasSize(2);
    assertThat(md).doesNotContain("README.md");

    String svg = Files.readString(out.resolve("trend.svg"), StandardCharsets.UTF_8);
    assertThat(svg).startsWith("<svg").contains("<polyline class=\"s0\"").contains("class=\"s1\"");
    assertThat(out.resolve("trend-p99.svg")).content().contains("<polyline class=\"s0\"");
    List<String> tsv = Files.readAllLines(out.resolve("trend.tsv"), StandardCharsets.UTF_8);
    assertThat(tsv).hasSize(4);
    assertThat(tsv.get(3)).startsWith("3\t2026-11-15-ccccccc\tfalse\t-0.200\t");

    // the trend command writes next to the reports by default
    assertThat(Trend.run(Options.parse(List.of("--dir", dir.toString()), Set.of()))).isZero();
    assertThat(dir.resolve("trend.md")).exists();
  }

  @Test
  @DisplayName("T-BLD-015c an empty directory gives a trend without points")
  void emptyTrend(@TempDir Path dir) throws IOException {
    Map<String, Object> model = Trend.write(dir, dir);
    assertThat(Json.array(model.get("points"))).isEmpty();
    assertThat(dir.resolve("trend.md")).content().contains("No report to plot.");
    assertThat(dir.resolve("trend.svg")).content().contains("no data");
  }

  @Test
  @DisplayName("T-BLD-015c latest.md points to the newest report with its headline numbers")
  void latestPointer(@TempDir Path dir) throws IOException {
    Map<String, Object> s =
        RegressionTest.summary(
            "2026-11-15-ccccccc", false, "argos-bench-app", "noop", "protobuf", 0.031, 0.25);
    s.put("measuredRequirements", 9);
    s.put("regression", "WARN");
    s.put("jdkVersion", "21.0.10");
    Trend.writeLatest(dir, "2026-11-15-ccccccc", s);
    assertThat(dir.resolve("latest.md"))
        .content()
        .startsWith("# Latest agent overhead benchmark")
        .contains("- Report: [2026-11-15-ccccccc](2026-11-15-ccccccc.md)")
        .contains(
            "- Verdict: **PASS** (9 of 9 requirements have numbers); regression (18 §7.7): WARN")
        .contains("- Approved: no (pending review")
        .contains("machine profile argos-bench-app, app shop-boot3, JDK 21.0.10, exporter noop")
        .contains("- Relative CPU +3.10%, 1-minute peak n/a, p99 difference 0.250 ms")
        .contains(Regression.C145_NOTE)
        .contains("[trend.md](trend.md)");
  }
}
