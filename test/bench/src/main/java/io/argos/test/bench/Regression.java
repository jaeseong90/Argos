/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regression judgment of 18 §7.7 against the previous approved report: WARN when the relative CPU
 * overhead is worse by 1.5 percentage points or more, or the p99 difference by 0.3 ms or more.
 * (FAIL is the N-xx limit itself, judged by {@link BenchReport}.) The numbers of a report travel in
 * its machine-readable summary, an HTML comment at the end of the Markdown file.
 */
final class Regression {
  private Regression() {}

  /** 18 §7.7: relative CPU +1.5 percentage points. */
  static final double CPU_POINTS = 0.015;

  /** 18 §7.7: p99 difference +0.3 ms. */
  static final double P99_MS = 0.3;

  /** Absolute tolerance of the comparisons (binary fractions of percent values). */
  private static final double EPSILON = 1e-9;

  static final String SUMMARY_START = "<!-- argos-bench-summary";
  static final String SUMMARY_END = "-->";
  private static final Pattern SUMMARY =
      Pattern.compile(
          Pattern.quote(SUMMARY_START) + "\\s*(\\{.*?})\\s*" + SUMMARY_END, Pattern.DOTALL);

  /**
   * Compares {@code current} with {@code baseline} (both summary metric maps: {@code relativeCpu}
   * as a fraction, {@code p99DiffMs}). The result has {@code verdict} WARN, OK or NOT COMPARED (no
   * baseline, or a number missing on either side) and the reasons.
   */
  static Map<String, Object> compare(
      Map<String, Object> current, Map<String, Object> baseline, String baselineName) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("baseline", baselineName);
    List<String> reasons = new ArrayList<>();
    if (baseline == null) {
      out.put("verdict", "NOT COMPARED");
      reasons.add("no baseline report given (--baseline)");
      out.put("reasons", reasons);
      return out;
    }
    double cpuNow = Json.number(current.get("relativeCpu"));
    double cpuBefore = Json.number(baseline.get("relativeCpu"));
    double p99Now = Json.number(current.get("p99DiffMs"));
    double p99Before = Json.number(baseline.get("p99DiffMs"));
    double cpuDelta = cpuNow - cpuBefore;
    double p99Delta = p99Now - p99Before;
    out.put("relativeCpuDeltaPoints", cpuDelta * 100);
    out.put("p99DiffDeltaMs", p99Delta);
    boolean compared = false;
    boolean warn = false;
    if (!Double.isNaN(cpuDelta)) {
      compared = true;
      if (cpuDelta >= CPU_POINTS - EPSILON) {
        warn = true;
        reasons.add(
            String.format(
                Locale.ROOT,
                "relative CPU %+.2f%% vs %+.2f%%: %+.2f percentage points (WARN at +1.5)",
                cpuNow * 100,
                cpuBefore * 100,
                cpuDelta * 100));
      }
    }
    if (!Double.isNaN(p99Delta)) {
      compared = true;
      if (p99Delta >= P99_MS - EPSILON) {
        warn = true;
        reasons.add(
            String.format(
                Locale.ROOT,
                "p99 difference %+.3f ms vs %+.3f ms: %+.3f ms (WARN at +0.3 ms)",
                p99Now,
                p99Before,
                p99Delta));
      }
    }
    if (!compared) {
      reasons.add("no relative CPU or p99 difference on both sides");
    }
    out.put("verdict", !compared ? "NOT COMPARED" : warn ? "WARN" : "OK");
    out.put("reasons", reasons);
    return out;
  }

  /** The summary block of a report (Markdown with the summary comment, or a summary JSON file). */
  static Map<String, Object> readSummary(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new UsageException("no baseline report " + file);
    }
    String text = Files.readString(file, StandardCharsets.UTF_8);
    if (file.getFileName().toString().endsWith(".json")) {
      return Json.object(Json.parse(text));
    }
    Matcher m = SUMMARY.matcher(text);
    if (!m.find()) {
      throw new UsageException(file + " has no " + SUMMARY_START + " block (not a bench report)");
    }
    return Json.object(Json.parse(m.group(1)));
  }

  /** The summary as the comment that ends a report ("--" cannot occur inside a comment). */
  static String summaryComment(Map<String, Object> summary) {
    return SUMMARY_START
        + "\n"
        + Json.write(summary).replace("--", "-\\u002d")
        + SUMMARY_END
        + "\n";
  }
}
