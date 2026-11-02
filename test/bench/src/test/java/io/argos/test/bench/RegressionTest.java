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
