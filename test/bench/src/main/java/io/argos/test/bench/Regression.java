/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Regression judgment of 18 §7.7 against the previous approved report: FAIL when the report exceeds
 * an N-xx limit (judged by {@link BenchReport}; it takes precedence over WARN), else WARN when the
 * relative CPU overhead is worse by 1.5 percentage points or more, or the p99 difference by 0.3 ms
 * or more (the boundaries themselves are WARN), else OK. A baseline of another configuration
 * (machine profile, app, JDK, exporter, encoding) is NOT COMPARED with the reason. The numbers of a
 * report travel in its machine-readable summary, an HTML comment at the end of the Markdown file.
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

  /** File names of published reports: {@code <YYYY-MM-DD>-<commit7>.md}. */
  static final Pattern REPORT_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-([0-9a-f]{7})\\.md");

  /** Summary keys that must be equal for two reports to be compared (18 §7.7, C-145 (4)). */
  static final List<String> IDENTITY_KEYS =
      List.of("machineProfile", "app", "jdk", "exporter", "encoding");

  /** ADR-0010 C-145 (4): what the no-op exporter costs, and what it is never compared with. */
  static final String C145_NOTE =
      "The no-op exporter (`--exporter noop`, argos.test.exporter=noop) encodes every transaction"
          + " with the agent's protobuf encoder and drops it (18 §5.4, ADR-0010 C-145 (4)). Its"
          + " numbers are never compared with WO-02 era numbers (JSONL test encoder): the"
          + " regression check and the trend leave those out.";

  /** ADR-0010 C-186 (2): the WO-13 JDBC drivers in the shop-boot3 jar. */
  static final String C186_NOTE =
      "WO-13 added JDBC drivers to the shop-boot3 jar, so its startup and transformed-class numbers"
          + " are not like for like across this change (ADR-0010 C-186 (2)); A-B differences"
          + " within one report are unaffected (same jar on both sides).";

  private static final Pattern DRIVER_VERSION = Pattern.compile("^(.+?)-\\d[^/]*\\.jar$");

  /** The encoding of an exporter: noop and grpc encode protobuf, drop none; unrecorded: WO-02. */
  static String encoding(Object exporter) {
    if (exporter == null) {
      return "jsonl";
    }
    return switch (String.valueOf(exporter)) {
      case "noop", "grpc" -> "protobuf";
      case "drop" -> "none";
      default -> "unknown";
    };
  }

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

  /**
   * Why the report of {@code current} (summary identity) cannot be compared with {@code baseline}
   * (a summary): empty when it can. Every {@link #IDENTITY_KEYS} value must be equal; a summary
   * without an encoding was made before WO-51 and a JSONL one in the WO-02 era (C-145 (4)).
   */
  static List<String> notComparable(Map<String, Object> current, Map<String, Object> baseline) {
    List<String> reasons = new ArrayList<>();
    if (baseline.get("encoding") == null) {
      reasons.add(
          "the baseline records no encoding (made before WO-51, possibly in the JSONL era):"
              + " not compared (C-145 (4))");
    } else if ("jsonl".equals(baseline.get("encoding"))
        || "jsonl".equals(current.get("encoding"))) {
      reasons.add("WO-02 era numbers (JSONL encoding) are never compared (C-145 (4))");
    }
    for (String key : IDENTITY_KEYS) {
      if (key.equals("encoding") && !reasons.isEmpty()) {
        continue;
      }
      Object now = current.get(key);
      Object before = baseline.get(key);
      if (!Objects.equals(now, before)) {
        reasons.add(key + " differs: baseline " + before + ", this report " + now);
      }
    }
    return reasons;
  }

  /**
   * The 18 §7.7 judgment of a report: {@code reportVerdict} FAIL (an N-xx limit) is FAIL whatever
   * the comparison says; otherwise the {@link #compare} verdict when the baseline is comparable,
   * else NOT COMPARED with the reasons. {@code selection} explains how the baseline was chosen (or
   * why there is none); notes carry the C-186 (2) footnote and the CPU resolution (18 open item 3).
   */
  static Map<String, Object> judge(
      String reportVerdict,
      Map<String, Object> metrics,
      Map<String, Object> identity,
      Map<String, Object> baselineSummary,
      String baselineName,
      List<String> selection) {
    Map<String, Object> out;
    List<String> notes = new ArrayList<>(selection);
    if (baselineSummary == null) {
      out = new LinkedHashMap<>();
      out.put("baseline", null);
      out.put("verdict", "NOT COMPARED");
      out.put(
          "reasons",
          new ArrayList<>(
              selection.isEmpty() ? List.of("no baseline report given (--baseline)") : selection));
      notes.clear();
    } else {
      List<String> problems = notComparable(identity, baselineSummary);
      if (!problems.isEmpty()) {
        out = new LinkedHashMap<>();
        out.put("baseline", baselineName);
        out.put("verdict", "NOT COMPARED");
        out.put("reasons", problems);
      } else {
        out = compare(metrics, metricsOf(baselineSummary), baselineName);
      }
      if (!Boolean.TRUE.equals(baselineSummary.get("approved"))) {
        notes.add("the baseline " + baselineName + " is not approved");
      }
      driversNote(identity.get("sampleAppDrivers"), baselineSummary.get("sampleAppDrivers"))
          .ifPresent(notes::add);
    }
    out.put("comparison", out.get("verdict"));
    if ("FAIL".equals(reportVerdict)) {
      List<String> reasons = new ArrayList<>();
      reasons.add("an N-xx limit is exceeded (FAIL takes precedence over WARN, 18 §7.7)");
      for (Object r : Json.array(out.get("reasons"))) {
        reasons.add(String.valueOf(r));
      }
      out.put("reasons", reasons);
      out.put("verdict", "FAIL");
    }
    double halfWidth = Json.number(metrics.get("relativeCpuCiHalfWidth"));
    if (!Double.isNaN(halfWidth)) {
      notes.add(
          String.format(
              Locale.ROOT,
              "relative CPU 95%% CI half-width %.2f percentage points (the +1.5 WARN step is"
                  + " resolvable only at <= 1.5; 18 open item 3, reported only)",
              halfWidth * 100));
    }
    out.put("notes", notes);
    return out;
  }

  private static Map<String, Object> metricsOf(Map<String, Object> summary) {
    return summary == null ? null : Json.object(summary.get("metrics"));
  }

  /**
   * The C-186 (2) footnote when the JDBC driver artifacts in the shop-boot3 jar differ between a
   * baseline (or trend point) and this report; empty when they are the same.
   */
  static Optional<String> driversNote(Object now, Object before) {
    Set<String> a = driverArtifacts(now);
    Set<String> b = driverArtifacts(before);
    if (Objects.equals(a, b)) {
      return Optional.empty();
    }
    return Optional.of(
        "shop-boot3 JDBC drivers: baseline "
            + (b == null ? "not recorded" : b)
            + ", this report "
            + (a == null ? "not recorded" : a)
            + ". "
            + C186_NOTE);
  }

  /** Artifact names (versions dropped) of a {@code sampleAppDrivers} list; null when absent. */
  static Set<String> driverArtifacts(Object drivers) {
    if (!(drivers instanceof List<?> list)) {
      return null;
    }
    Set<String> out = new TreeSet<>();
    for (Object jar : list) {
      String name = String.valueOf(jar);
      Matcher m = DRIVER_VERSION.matcher(name);
      out.add(m.matches() ? m.group(1) : name);
    }
    return out;
  }

  /** The baseline chosen by {@code --baseline auto}: its file and summary, or none with reasons. */
  record Selection(Path file, Map<String, Object> summary, List<String> reasons) {}

  /**
   * {@code --baseline auto}: the newest {@code <YYYY-MM-DD>-<commit7>.md} in {@code dir} (other
   * than {@code exclude}) whose summary is approved and has the same {@link #IDENTITY_KEYS} as
   * {@code identity}. Newest = latest set start ({@code startedAt}), then the date in the name,
   * then the name.
   */
  static Selection selectBaseline(Path dir, Map<String, Object> identity, String exclude)
      throws IOException {
    List<String> reasons = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      reasons.add("no report directory " + dir + " (--baseline auto)");
      return new Selection(null, null, reasons);
    }
    record Candidate(Path file, Map<String, Object> summary, String order) {}
    List<Candidate> comparable = new ArrayList<>();
    int reports = 0;
    int approved = 0;
    Set<String> mismatches = new LinkedHashSet<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        Matcher m = REPORT_NAME.matcher(name);
        if (!m.matches() || !Files.isRegularFile(file) || name.equals(exclude + ".md")) {
          continue;
        }
        Map<String, Object> summary;
        try {
          summary = readSummary(file);
        } catch (UsageException | IllegalArgumentException e) {
          continue;
        }
        reports++;
        if (!Boolean.TRUE.equals(summary.get("approved"))) {
          continue;
        }
        approved++;
        List<String> problems = notComparable(identity, summary);
        if (!problems.isEmpty()) {
          mismatches.add(name + ": " + String.join("; ", problems));
          continue;
        }
        Object started = summary.get("startedAt");
        comparable.add(
            new Candidate(
                file, summary, (started instanceof String s ? s : m.group(1)) + "|" + name));
      }
    }
    if (comparable.isEmpty()) {
      reasons.add(
          String.format(
              Locale.ROOT,
              "no approved report with the same machine profile, app, JDK, exporter and encoding"
                  + " in %s (%d reports, %d approved) (--baseline auto)",
              dir,
              reports,
              approved));
      mismatches.stream().limit(5).forEach(reasons::add);
      return new Selection(null, null, reasons);
    }
    Candidate newest =
        comparable.stream().max(Comparator.comparing(Candidate::order)).orElseThrow();
    reasons.add(
        "baseline chosen by --baseline auto: the newest approved report of the same configuration"
            + " ("
            + comparable.size()
            + " candidates)");
    return new Selection(newest.file(), newest.summary(), reasons);
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
