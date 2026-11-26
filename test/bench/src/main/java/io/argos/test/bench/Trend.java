/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * The trend of 18 §7.7 over the published reports of a directory ({@code docs/bench}): relative CPU
 * (mean and 1-minute peak), p99 difference, agent heap and startup increase of every report of the
 * newest report's configuration, as {@code trend.md}, {@code trend.svg}, {@code trend-p99.svg} and
 * {@code trend.tsv}; and {@code latest.md}, the pointer to the newest report. WO-02 era directories
 * and JSONL-era numbers are never part of a trend (ADR-0010 C-145 (4)); a change of the JDBC
 * drivers in the shop-boot3 jar is footnoted on startup numbers (C-186 (2)).
 */
final class Trend {
  private Trend() {}

  /** A published report and its summary. */
  record Entry(String name, Map<String, Object> summary) {
    String order() {
      Object started = summary.get("startedAt");
      return (started instanceof String s ? s : name.substring(0, 10)) + "|" + name;
    }
  }

  /** {@code trend --dir D [--out D]}. */
  static int run(Options o) throws IOException {
    Path dir = Path.of(o.required("dir")).toAbsolutePath().normalize();
    Path out = o.optional("out").map(Path::of).orElse(dir).toAbsolutePath().normalize();
    o.rejectUnknown();
    if (!Files.isDirectory(dir)) {
      throw new UsageException("no report directory " + dir);
    }
    Map<String, Object> model = write(dir, out);
    LoadSet.log(
        "trend "
            + out.resolve("trend.md")
            + ": "
            + Json.array(model.get("points")).size()
            + " reports plotted, "
            + Json.array(model.get("excluded")).size()
            + " left out");
    return 0;
  }

  /** Reads the reports of {@code dir} and writes the trend files into {@code out}. */
  static Map<String, Object> write(Path dir, Path out) throws IOException {
    List<Entry> entries = new ArrayList<>();
    List<String[]> excluded = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        if (Files.isDirectory(file)) {
          if (name.toLowerCase(Locale.ROOT).startsWith("wo02-")) {
            excluded.add(
                new String[] {
                  name + "/",
                  "WO-02 era directory (JSONL encoding): reference material, never compared"
                      + " (C-145 (4))"
                });
          }
          continue;
        }
        Matcher m = Regression.REPORT_NAME.matcher(name);
        if (!m.matches()) {
          continue;
        }
        try {
          entries.add(
              new Entry(name.substring(0, name.length() - 3), Regression.readSummary(file)));
        } catch (UsageException | IllegalArgumentException e) {
          excluded.add(new String[] {name, "no readable summary: " + e.getMessage()});
        }
      }
    }
    Map<String, Object> model = model(entries, excluded);
    Files.createDirectories(out);
    Files.writeString(out.resolve("trend.md"), markdown(model, dir), StandardCharsets.UTF_8);
    writeGraphs(out, model);
    return model;
  }

  /**
   * The trend model: the newest report of a comparable encoding decides the configuration; every
   * report of that configuration is a point (oldest first), the others are listed as excluded with
   * the reason. Pure (tested without files).
   */
  static Map<String, Object> model(List<Entry> entries, List<String[]> excludedSoFar) {
    List<String[]> excluded = new ArrayList<>(excludedSoFar);
    List<Entry> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing(Entry::order));
    List<Entry> usable = new ArrayList<>();
    for (Entry e : sorted) {
      Object encoding = e.summary().get("encoding");
      if (encoding == null) {
        excluded.add(
            new String[] {
              e.name() + ".md",
              "no encoding in its summary (made before WO-51, possibly the JSONL era): not"
                  + " compared (C-145 (4))"
            });
      } else if ("jsonl".equals(encoding)) {
        excluded.add(
            new String[] {
              e.name() + ".md", "JSONL encoding (WO-02 era): never compared (C-145 (4))"
            });
      } else {
        usable.add(e);
      }
    }
    Map<String, Object> model = new LinkedHashMap<>();
    List<Object> points = new ArrayList<>();
    Map<String, Object> configuration = new LinkedHashMap<>();
    if (!usable.isEmpty()) {
      Map<String, Object> newest = usable.get(usable.size() - 1).summary();
      for (String key : Regression.IDENTITY_KEYS) {
        configuration.put(key, newest.get(key));
      }
      Object newestDrivers = newest.get("sampleAppDrivers");
      for (Entry e : usable) {
        List<String> problems = Regression.notComparable(configuration, e.summary());
        if (!problems.isEmpty()) {
          excluded.add(
              new String[] {
                e.name() + ".md", "other configuration: " + String.join("; ", problems)
              });
          continue;
        }
        Map<String, Object> s = e.summary();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", e.name());
        p.put("date", e.name().substring(0, 10));
        p.put("approved", Boolean.TRUE.equals(s.get("approved")));
        p.put("verdict", s.get("verdict"));
        p.put("regression", s.get("regression"));
        p.put("relativeCpu", Json.number(s, "metrics", "relativeCpu"));
        p.put("peakRelativeCpu", Json.number(s, "metrics", "peakRelativeCpu"));
        p.put("p99DiffMs", Json.number(s, "metrics", "p99DiffMs"));
        p.put("agentHeapMb", Json.number(s, "metrics", "agentHeapMb"));
        p.put("startupIncreaseMs", Json.object(Json.at(s, "metrics", "startupIncreaseMs")));
        p.put("sampleAppDrivers", s.get("sampleAppDrivers"));
        p.put(
            "driversNote",
            Regression.driversNote(newestDrivers, s.get("sampleAppDrivers")).orElse(null));
        points.add(p);
      }
    }
    model.put("configuration", configuration);
    model.put("points", points);
    List<Object> ex = new ArrayList<>();
    for (String[] e : excluded) {
      ex.add(Map.of("item", e[0], "reason", e[1]));
    }
    model.put("excluded", ex);
    return model;
  }

  static String markdown(Map<String, Object> model, Path dir) {
    StringBuilder md = new StringBuilder("# Agent overhead trend\n\n");
    md.append("Generated by `test/bench/bench.sh trend` (also `publish --trend`) from the report")
        .append(" summaries in `")
        .append(dir.getFileName())
        .append("/` (18 §7.7). Numbers are on - off of the first main set of each report;")
        .append(" approval happens in review of each report.\n\n");
    md.append(Regression.C145_NOTE).append("\n\n");
    Map<String, Object> configuration = Json.object(model.get("configuration"));
    List<Object> points = Json.array(model.get("points"));
    if (points.isEmpty()) {
      md.append("No report to plot.\n\n");
    } else {
      md.append("Configuration plotted (the newest report's): machine profile ")
          .append(configuration.get("machineProfile"))
          .append(", app ")
          .append(configuration.get("app"))
          .append(", JDK ")
          .append(configuration.get("jdk"))
          .append(", exporter ")
          .append(configuration.get("exporter"))
          .append(" (encoding ")
          .append(configuration.get("encoding"))
          .append("); ")
          .append(points.size())
          .append(" reports.\n\n");
      md.append("![Relative CPU trend](trend.svg) ([data](trend.tsv))\n\n");
      md.append("![p99 difference trend](trend-p99.svg)\n\n");
      md.append(
          "| # | report | approved | verdict | regression | relative CPU | 1-minute peak | p99"
              + " difference ms | agent heap MB | startup increase ms |\n"
              + "|---|---|---|---|---|---|---|---|---|---|\n");
      List<String> footnotes = new ArrayList<>();
      int i = 0;
      for (Object p : points) {
        i++;
        String note = (String) Json.at(p, "driversNote");
        String mark = "";
        if (note != null) {
          if (!footnotes.contains(note)) {
            footnotes.add(note);
          }
          mark = " [" + (footnotes.indexOf(note) + 1) + "]";
        }
        md.append("| ")
            .append(i)
            .append(" | [")
            .append(Json.at(p, "name"))
            .append("](")
            .append(Json.at(p, "name"))
            .append(".md) | ")
            .append(Boolean.TRUE.equals(Json.at(p, "approved")) ? "yes" : "no")
            .append(" | ")
            .append(Json.at(p, "verdict"))
            .append(" | ")
            .append(Json.at(p, "regression"))
            .append(" | ")
            .append(LoadReport.pct(Json.number(p, "relativeCpu")))
            .append(" | ")
            .append(LoadReport.pct(Json.number(p, "peakRelativeCpu")))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(p, "p99DiffMs"), 3))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(p, "agentHeapMb"), 2))
            .append(" | ")
            .append(BenchReport.cell(startups(Json.object(Json.at(p, "startupIncreaseMs")))))
            .append(mark)
            .append(" |\n");
      }
      md.append('\n');
      for (int f = 0; f < footnotes.size(); f++) {
        md.append("[").append(f + 1).append("] ").append(footnotes.get(f)).append("\n\n");
      }
    }
    List<Object> excluded = Json.array(model.get("excluded"));
    if (!excluded.isEmpty()) {
      md.append("## Not in the trend\n\n| item | reason |\n|---|---|\n");
      for (Object e : excluded) {
        md.append("| ")
            .append(BenchReport.cell(String.valueOf(Json.at(e, "item"))))
            .append(" | ")
            .append(BenchReport.cell(String.valueOf(Json.at(e, "reason"))))
            .append(" |\n");
      }
      md.append('\n');
    }
    return md.toString();
  }

  private static String startups(Map<String, Object> increases) {
    if (increases.isEmpty()) {
      return "-";
    }
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> e : increases.entrySet()) {
      parts.add(e.getKey() + " " + LoadReport.fmt(Json.number(e.getValue()), 0));
    }
    return String.join("; ", parts);
  }

  private static void writeGraphs(Path out, Map<String, Object> model) throws IOException {
    List<Object> points = Json.array(model.get("points"));
    List<double[]> mean = new ArrayList<>();
    List<double[]> peak = new ArrayList<>();
    List<double[]> p99 = new ArrayList<>();
    StringBuilder tsv =
        new StringBuilder(
            "index\treport\tapproved\trelative_cpu_pct\tpeak_relative_cpu_pct\tp99_diff_ms"
                + "\tagent_heap_mb\n");
    int i = 0;
    for (Object p : points) {
      i++;
      double cpu = Json.number(p, "relativeCpu") * 100;
      double pk = Json.number(p, "peakRelativeCpu") * 100;
      double d = Json.number(p, "p99DiffMs");
      mean.add(new double[] {i, cpu});
      peak.add(new double[] {i, pk});
      p99.add(new double[] {i, d});
      tsv.append(i)
          .append('\t')
          .append(Json.at(p, "name"))
          .append('\t')
          .append(Json.at(p, "approved"))
          .append('\t')
          .append(LoadReport.fmt(cpu, 3))
          .append('\t')
          .append(LoadReport.fmt(pk, 3))
          .append('\t')
          .append(LoadReport.fmt(d, 3))
          .append('\t')
          .append(LoadReport.fmt(Json.number(p, "agentHeapMb"), 3))
          .append('\n');
    }
    String x = "report (oldest to newest, numbered as in trend.md)";
    Files.writeString(
        out.resolve("trend.svg"),
        Svg.lineChart(
            "Relative agent CPU overhead per report",
            x,
            "relative CPU %",
            List.of(new Svg.Series("mean", 0, mean), new Svg.Series("1-minute peak", 1, peak)),
            false,
            List.of("mean", "1-minute peak (dashed)")),
        StandardCharsets.UTF_8);
    Files.writeString(
        out.resolve("trend-p99.svg"),
        Svg.lineChart(
            "p99 difference (on - off) per report",
            x,
            "ms",
            List.of(new Svg.Series("p99 difference", 0, p99)),
            false,
            List.of("p99 difference")),
        StandardCharsets.UTF_8);
    Files.writeString(out.resolve("trend.tsv"), tsv, StandardCharsets.UTF_8);
  }

  /**
   * {@code latest.md}: a short pointer to the newest report (18 §7.7 names a symbolic link; a
   * pointer file works in every checkout, Windows included).
   */
  static void writeLatest(Path out, String reportName, Map<String, Object> summary)
      throws IOException {
    Files.createDirectories(out);
    Files.writeString(
        out.resolve("latest.md"), latestMarkdown(reportName, summary), StandardCharsets.UTF_8);
  }

  static String latestMarkdown(String reportName, Map<String, Object> summary) {
    StringBuilder md = new StringBuilder("# Latest agent overhead benchmark\n\n");
    md.append(
        "Points to the newest report of `test/bench/bench.sh publish --latest` (18 §7.7). A"
            + " pointer file rather than a symbolic link, so that it works in every checkout.\n\n");
    md.append("- Report: [").append(reportName).append("](").append(reportName).append(".md)\n");
    md.append("- Verdict: **")
        .append(summary.get("verdict"))
        .append("** (")
        .append(summary.get("measuredRequirements"))
        .append(" of 9 requirements have numbers); regression (18 §7.7): ")
        .append(summary.get("regression"))
        .append('\n');
    md.append("- Approved: ")
        .append(
            Boolean.TRUE.equals(summary.get("approved"))
                ? "yes"
                : "no (pending review: approval sets `\"approved\": true` in the report's summary)")
        .append('\n');
    md.append("- Configuration: machine profile ")
        .append(summary.get("machineProfile"))
        .append(", app ")
        .append(summary.get("app"))
        .append(", JDK ")
        .append(summary.get("jdkVersion") == null ? summary.get("jdk") : summary.get("jdkVersion"))
        .append(", exporter ")
        .append(summary.get("exporter"))
        .append(" (encoding ")
        .append(summary.get("encoding"))
        .append(")\n");
    md.append("- Relative CPU ")
        .append(LoadReport.pct(Json.number(summary, "metrics", "relativeCpu")))
        .append(", 1-minute peak ")
        .append(LoadReport.pct(Json.number(summary, "metrics", "peakRelativeCpu")))
        .append(", p99 difference ")
        .append(LoadReport.fmt(Json.number(summary, "metrics", "p99DiffMs"), 3))
        .append(" ms, agent heap ")
        .append(LoadReport.fmt(Json.number(summary, "metrics", "agentHeapMb"), 2))
        .append(" MB\n");
    Map<String, Object> starts = Json.object(Json.at(summary, "metrics", "startupIncreaseMs"));
    if (!starts.isEmpty()) {
      md.append("- Startup increase (ms): ").append(startups(starts)).append('\n');
    }
    md.append("- Trend: [trend.md](trend.md)\n\n");
    md.append(Regression.C145_NOTE).append('\n');
    return md.toString();
  }
}
