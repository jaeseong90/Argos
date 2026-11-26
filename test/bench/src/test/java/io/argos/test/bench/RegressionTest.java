/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 18 §7.7: WARN at relative CPU +1.5 percentage points or p99 difference +0.3 ms. */
@Tag("req:DSN-18-07")
class RegressionTest {

  private static Map<String, Object> metrics(double relativeCpu, double p99DiffMs) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("relativeCpu", relativeCpu);
    m.put("p99DiffMs", p99DiffMs);
    return m;
  }

  private static Object verdict(Map<String, Object> now, Map<String, Object> before) {
    return Regression.compare(now, before, "base.md").get("verdict");
  }

  @Test
  @Tag("req:N-01")
  @DisplayName("relative CPU: +1.5 percentage points is WARN, +1.49 is not")
  void cpuBoundary() {
    assertThat(verdict(metrics(0.045, 0.1), metrics(0.03, 0.1))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.035, 0.1), metrics(0.02, 0.1))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.0349, 0.1), metrics(0.02, 0.1))).isEqualTo("OK");
    // better than the baseline is never a regression
    assertThat(verdict(metrics(0.01, 0.1), metrics(0.04, 0.1))).isEqualTo("OK");
    Map<String, Object> warn = Regression.compare(metrics(0.045, 0.1), metrics(0.03, 0.1), "b");
    assertThat(Json.array(warn.get("reasons")).toString()).contains("+1.50 percentage points");
  }

  @Test
  @Tag("req:N-03")
  @DisplayName("p99 difference: +0.3 ms is WARN, +0.29 ms is not")
  void p99Boundary() {
    assertThat(verdict(metrics(0.03, 0.5), metrics(0.03, 0.2))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.03, 0.49), metrics(0.03, 0.2))).isEqualTo("OK");
    assertThat(verdict(metrics(0.03, -0.1), metrics(0.03, 0.2))).isEqualTo("OK");
  }

  @Test
  @DisplayName("no baseline or no numbers: NOT COMPARED")
  void notCompared() {
    assertThat(Regression.compare(metrics(0.03, 0.2), null, null).get("verdict"))
        .isEqualTo("NOT COMPARED");
    assertThat(verdict(metrics(Double.NaN, Double.NaN), metrics(0.03, 0.2)))
        .isEqualTo("NOT COMPARED");
  }

  /** A summary of a published report with the given identity and metrics. */
  static Map<String, Object> summary(
      String name,
      boolean approved,
      String machineProfile,
      String exporter,
      String encoding,
      double relativeCpu,
      double p99DiffMs) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("format", "argos-bench-report/1");
    s.put("name", name);
    s.put("verdict", "PASS");
    s.put("approved", approved);
    s.put("startedAt", name.substring(0, 10) + "T01:00:00Z");
    s.put("machineProfile", machineProfile);
    s.put("app", "shop-boot3");
    s.put("jdk", "21");
    s.put("exporter", exporter);
    if (encoding != null) {
      s.put("encoding", encoding);
    }
    s.put("sampleAppDrivers", List.of("h2-2.3.232.jar", "postgresql-42.7.11.jar"));
    s.put("metrics", metrics(relativeCpu, p99DiffMs));
    return s;
  }

  static Map<String, Object> identity() {
    Map<String, Object> id =
        summary("2026-11-29-0000000", false, "argos-bench-app", "noop", "protobuf", 0, 0);
    id.remove("metrics");
    return id;
  }

  private static Object judged(String reportVerdict, Map<String, Object> now, double before) {
    return Regression.judge(
            reportVerdict,
            now,
            identity(),
            summary("2026-11-22-1111111", true, "argos-bench-app", "noop", "protobuf", before, 0.2),
            "2026-11-22-1111111.md",
            List.of())
        .get("verdict");
  }

  @Test
  @Tag("req:N-01")
  @Tag("req:N-03")
  @DisplayName(
      "T-BLD-015a the WARN boundaries themselves are WARN (+1.5 pp, +0.3 ms in binary fractions)")
  void exactBoundariesAreWarn() {
    // differences that are not exact in binary: 0.4 - 0.1, 1.3 - 1.0, 0.036 - 0.021, 0.065 - 0.05
    assertThat(0.4 - 0.1).isNotEqualTo(0.3);
    assertThat(verdict(metrics(0.03, 0.4), metrics(0.03, 0.1))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.03, 1.3), metrics(0.03, 1.0))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.03, 0.5), metrics(0.03, 0.2))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.036, 0.1), metrics(0.021, 0.1))).isEqualTo("WARN");
    assertThat(verdict(metrics(0.065, 0.1), metrics(0.05, 0.1))).isEqualTo("WARN");
    // just below
    assertThat(verdict(metrics(0.03, 0.4 - 1e-6), metrics(0.03, 0.1))).isEqualTo("OK");
    assertThat(verdict(metrics(0.065 - 1e-6, 0.1), metrics(0.05, 0.1))).isEqualTo("OK");
  }

  @Test
  @Tag("req:N-01")
  @DisplayName("T-BLD-015a an N-xx limit FAIL takes precedence over WARN, OK and NOT COMPARED")
  void failTakesPrecedence() {
    Map<String, Object> warn = metrics(0.07, 0.2); // +2 pp over the baseline's 0.05
    assertThat(judged("PASS", warn, 0.05)).isEqualTo("WARN");
    assertThat(judged("INVALID", warn, 0.05)).isEqualTo("WARN");
    assertThat(judged("FAIL", warn, 0.05)).isEqualTo("FAIL");
    assertThat(judged("FAIL", metrics(0.05, 0.2), 0.05)).isEqualTo("FAIL");
    Map<String, Object> failed =
        Regression.judge(
            "FAIL",
            warn,
            identity(),
            summary("2026-11-22-1111111", true, "argos-bench-app", "noop", "protobuf", 0.05, 0.2),
            "2026-11-22-1111111.md",
            List.of());
    assertThat(failed.get("comparison")).isEqualTo("WARN");
    assertThat(Json.array(failed.get("reasons")).get(0).toString())
        .contains("FAIL takes precedence over WARN");
    // no baseline at all: still FAIL
    assertThat(
            Regression.judge("FAIL", warn, identity(), null, null, List.of("no baseline"))
                .get("verdict"))
        .isEqualTo("FAIL");
    assertThat(
            Regression.judge("PASS", warn, identity(), null, null, List.of("no baseline"))
                .get("verdict"))
        .isEqualTo("NOT COMPARED");
  }

  @Test
  @DisplayName(
      "T-BLD-015a a baseline of another machine, exporter, encoding or the JSONL era is NOT"
          + " COMPARED with the reason")
  void nonComparableBaselines() {
    Map<String, Object> now = metrics(0.09, 0.9); // would be WARN against any of them
    record Case(Map<String, Object> baseline, String reason) {}
    List<Case> cases =
        List.of(
            new Case(
                summary("2026-11-22-1111111", true, "dev-laptop-shared", "noop", "protobuf", 0, 0),
                "machineProfile differs: baseline dev-laptop-shared, this report argos-bench-app"),
            new Case(
                summary("2026-11-22-1111111", true, "argos-bench-app", "drop", "none", 0, 0),
                "exporter differs: baseline drop, this report noop"),
            new Case(
                summary("2026-11-22-1111111", true, "argos-bench-app", "noop", null, 0, 0),
                "records no encoding"),
            new Case(
                summary("2026-11-22-1111111", true, "argos-bench-app", "noop", "jsonl", 0, 0),
                "JSONL encoding"));
    for (Case c : cases) {
      Map<String, Object> r =
          Regression.judge("PASS", now, identity(), c.baseline(), "b.md", List.of());
      assertThat(r.get("verdict")).as(c.reason()).isEqualTo("NOT COMPARED");
      assertThat(Json.array(r.get("reasons")).toString()).contains(c.reason());
    }
    Map<String, Object> other =
        summary("2026-11-22-1111111", true, "argos-bench-app", "noop", "protobuf", 0, 0);
    other.put("jdk", "17");
    assertThat(Regression.notComparable(identity(), other))
        .containsExactly("jdk differs: baseline 17, this report 21");
    assertThat(Regression.encoding("noop")).isEqualTo("protobuf");
    assertThat(Regression.encoding("grpc")).isEqualTo("protobuf");
    assertThat(Regression.encoding("drop")).isEqualTo("none");
    assertThat(Regression.encoding(null)).isEqualTo("jsonl");
  }

  @Test
  @DisplayName("T-BLD-015a the C-186 (2) footnote when the shop-boot3 JDBC drivers differ")
  void driversFootnote() {
    Map<String, Object> before =
        summary("2026-11-22-1111111", true, "argos-bench-app", "noop", "protobuf", 0.03, 0.2);
    before.put("sampleAppDrivers", List.of("h2-2.2.224.jar", "postgresql-42.7.4.jar"));
    Map<String, Object> id = identity();
    id.put(
        "sampleAppDrivers",
        List.of("h2-2.3.232.jar", "mariadb-java-client-3.5.8.jar", "postgresql-42.7.11.jar"));
    Map<String, Object> r =
        Regression.judge("PASS", metrics(0.03, 0.2), id, before, "b.md", List.of());
    assertThat(r.get("verdict")).isEqualTo("OK");
    assertThat(Json.array(r.get("notes")).toString())
        .contains("[h2, postgresql]")
        .contains("[h2, mariadb-java-client, postgresql]")
        .contains("C-186 (2)");
    // a version bump of the same drivers is not the WO-13 addition
    before.put(
        "sampleAppDrivers",
        List.of("h2-2.3.232.jar", "mariadb-java-client-3.5.7.jar", "postgresql-42.7.10.jar"));
    assertThat(Regression.driversNote(id.get("sampleAppDrivers"), before.get("sampleAppDrivers")))
        .isEmpty();
  }

  private static void writeReport(Path dir, Map<String, Object> summary) throws IOException {
    Files.writeString(
        dir.resolve(summary.get("name") + ".md"),
        "# report\n\n" + Regression.summaryComment(summary),
        StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-BLD-015b --baseline auto takes the newest approved report of the same configuration")
  void autoBaselineSelection(@TempDir Path dir) throws IOException {
    writeReport(
        dir, summary("2026-11-01-aaaaaaa", true, "argos-bench-app", "noop", "protobuf", 0.03, 0.2));
    writeReport(
        dir,
        summary("2026-11-08-bbbbbbb", true, "argos-bench-app", "noop", "protobuf", 0.031, 0.21));
    writeReport(
        dir,
        summary("2026-11-15-ccccccc", false, "argos-bench-app", "noop", "protobuf", 0.032, 0.2));
    writeReport(
        dir,
        summary("2026-11-20-ddddddd", true, "dev-laptop-shared", "noop", "protobuf", 0.03, 0.2));
    writeReport(
        dir, summary("2026-11-21-eeeeeee", true, "argos-bench-app", "drop", "none", 0.03, 0.2));
    writeReport(
        dir, summary("2026-11-22-fffffff", true, "argos-bench-app", "noop", null, 0.03, 0.2));
    writeReport(
        dir, summary("2026-11-29-0000000", true, "argos-bench-app", "noop", "protobuf", 0.03, 0.2));
    Files.writeString(dir.resolve("notes.md"), "# not a report\n", StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("2026-11-23-9999999.md"), "# no summary\n", StandardCharsets.UTF_8);

    // the report being written (2026-11-29-0000000) is never its own baseline
    Regression.Selection s = Regression.selectBaseline(dir, identity(), "2026-11-29-0000000");
    assertThat(s.file().getFileName().toString()).isEqualTo("2026-11-08-bbbbbbb.md");
    assertThat(Json.number(s.summary(), "metrics", "relativeCpu")).isEqualTo(0.031);
    assertThat(s.reasons().toString()).contains("newest approved report of the same configuration");

    // without the exclusion the newest one wins
    assertThat(Regression.selectBaseline(dir, identity(), "x").file().getFileName().toString())
        .isEqualTo("2026-11-29-0000000.md");

    // other configurations only: none, with the reasons
    Map<String, Object> grpc = identity();
    grpc.put("exporter", "grpc");
    Regression.Selection none = Regression.selectBaseline(dir, grpc, "x");
    assertThat(none.file()).isNull();
    assertThat(none.summary()).isNull();
    assertThat(none.reasons().get(0)).contains("no approved report").contains("6 approved");
    assertThat(none.reasons().toString()).contains("exporter differs");

    // the judgment then says why it did not compare
    Map<String, Object> judged =
        Regression.judge("PASS", metrics(0.03, 0.2), grpc, null, null, none.reasons());
    assertThat(judged.get("verdict")).isEqualTo("NOT COMPARED");
    assertThat(Json.array(judged.get("reasons")).toString()).contains("no approved report");
    assertThat(Regression.selectBaseline(dir.resolve("missing"), identity(), "x").reasons())
        .anyMatch(r -> r.contains("no report directory"));
  }

  @Test
  @DisplayName("the summary comment of a report is read back as the baseline")
  void summaryRoundTrip(@TempDir Path dir) throws IOException {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("format", "argos-bench-report/1");
    summary.put("note", "a -- b --> c");
    summary.put("metrics", metrics(0.034, 0.25));
    summary.put("list", List.of(1, 2));
    Path md = dir.resolve("2026-11-03-4a43e77.md");
    Files.writeString(
        md, "# report\n\ntext\n\n" + Regression.summaryComment(summary), StandardCharsets.UTF_8);
    String text = Files.readString(md, StandardCharsets.UTF_8);
    // "--" may not occur inside the comment
    String comment = text.substring(text.indexOf(Regression.SUMMARY_START) + 4);
    assertThat(comment.substring(0, comment.lastIndexOf("-->"))).doesNotContain("--");
    Map<String, Object> read = Regression.readSummary(md);
    assertThat(read.get("note")).isEqualTo("a -- b --> c");
    assertThat(Json.number(read, "metrics", "relativeCpu")).isEqualTo(0.034);
    Path json = dir.resolve("summary.json");
    FileTools.writeJson(json, summary);
    assertThat(Json.number(Regression.readSummary(json), "metrics", "p99DiffMs")).isEqualTo(0.25);
    Path other = dir.resolve("other.md");
    Files.writeString(other, "# not a report\n", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> Regression.readSummary(other)).isInstanceOf(UsageException.class);
  }
}
