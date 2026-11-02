/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Analysis of a startup set: medians per app, CDS mode and variant, the increase with the agent and
 * the verdicts of N-04 (+2 s; heavy app also +10%), Gate 0 ("+2 s, heavy app included") and N-30
 * (premain <= 500 ms). A set without starts, or whose agent rows lack a number (port medians,
 * premain), is INVALID: no PASS without numbers (18 §7.6; WO-02 H-1). Writes {@code result.json}
 * and {@code report.md}; exit code 0 PASS, 3 FAIL, 4 INVALID.
 */
final class StartupReport {
  private StartupReport() {}

  /** The heavy sample app (N-04 relative limit, N-27). */
  static final String HEAVY_APP = "shop-heavy";

  static int write(Path setDir) throws IOException {
    Map<String, Object> set = Json.object(FileTools.readJson(setDir.resolve("set.json")));
    List<Map<String, Object>> runs = LoadReport.readRuns(setDir);
    Map<String, Object> result = analyze(runs);
    result.put("label", set.get("label"));
    FileTools.writeJson(setDir.resolve("result.json"), result);
    Files.writeString(setDir.resolve("report.md"), markdown(set, result), StandardCharsets.UTF_8);
    LoadSet.log(
        "result "
            + setDir.resolve("result.json")
            + ": "
            + result.get("verdict")
            + " "
            + result.get("reasons"));
    return switch (String.valueOf(result.get("verdict"))) {
      case "PASS" -> 0;
      case "FAIL" -> 3;
      default -> 4;
    };
  }

  private static double[] values(
      List<Map<String, Object>> runs, String app, String cds, String variant, String key) {
    return runs.stream()
        .filter(
            r ->
                app.equals(r.get("app"))
                    && cds.equals(r.get("cds"))
                    && variant.equals(r.get("variant")))
        .mapToDouble(r -> Json.number(r.get(key)))
        .filter(v -> !Double.isNaN(v))
        .toArray();
  }

  static Map<String, Object> analyze(List<Map<String, Object>> runs) {
    Set<String> apps = new LinkedHashSet<>();
    Set<String> cdsModes = new LinkedHashSet<>();
    Set<String> variants = new LinkedHashSet<>();
    for (Map<String, Object> r : runs) {
      apps.add(String.valueOf(r.get("app")));
      cdsModes.add(String.valueOf(r.get("cds")));
      variants.add(String.valueOf(r.get("variant")));
    }
    variants.remove("off");
    List<Object> rows = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    boolean fail = false;
    boolean invalid = false;
    if (runs.isEmpty()) {
      invalid = true;
      reasons.add("no starts in the set (WO-02 H-1)");
    } else if (!variants.contains("on")) {
      invalid = true;
      reasons.add("no starts with the agent (variant on)");
    }
    for (String app : apps) {
      for (String cds : cdsModes) {
        for (String variant : variants) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("app", app);
          row.put("cds", cds);
          row.put("variant", variant);
          double[] offPort = values(runs, app, cds, "off", "spawnToPortMs");
          double[] onPort = values(runs, app, cds, variant, "spawnToPortMs");
          double[] offReady = values(runs, app, cds, "off", "spawnToReadyMs");
          double[] onReady = values(runs, app, cds, variant, "spawnToReadyMs");
          double offPortMedian = Stats.median(offPort);
          double onPortMedian = Stats.median(onPort);
          double increase = onPortMedian - offPortMedian;
          double relative = onPortMedian / offPortMedian - 1;
          row.put("n", Math.min(offPort.length, onPort.length));
          row.put("offPortMs", offPort);
          row.put(variant + "PortMs", onPort);
          row.put("offPortMedianMs", offPortMedian);
          row.put(variant + "PortMedianMs", onPortMedian);
          row.put("portIncreaseMs", increase);
          row.put("portIncreaseRelative", relative);
          row.put("offReadyMedianMs", Stats.median(offReady));
          row.put(variant + "ReadyMedianMs", Stats.median(onReady));
          row.put("readyIncreaseMs", Stats.median(onReady) - Stats.median(offReady));
          row.put(
              "offSpringStartedMedianS",
              Stats.median(values(runs, app, cds, "off", "springStartedSeconds")));
          row.put(
              variant + "SpringStartedMedianS",
              Stats.median(values(runs, app, cds, variant, "springStartedSeconds")));
          double[] premain = values(runs, app, cds, variant, "premainMs");
          row.put("premainMedianMs", Stats.median(premain));
          row.put("premainMaxMs", Stats.max(premain));
          row.put("offCpuBeforeMedianPercent", Stats.median(before(runs, app, cds, "off")));
          row.put(
              variant + "CpuBeforeMedianPercent", Stats.median(before(runs, app, cds, variant)));
          if (variant.equals("on") && (Double.isNaN(increase) || Double.isNaN(relative))) {
            // No port times for off or on in this cell: no number, no verdict.
            row.put("plus2sVerdict", "NO DATA");
            invalid = true;
            reasons.add(
                String.format(
                    Locale.ROOT,
                    "%s cds=%s: no data (port times off %d, on %d)",
                    app,
                    cds,
                    offPort.length,
                    onPort.length));
          } else if (variant.equals("on")) {
            List<String> failed = new ArrayList<>();
            boolean plus2s = increase <= 2000;
            row.put("plus2sVerdict", plus2s ? "PASS" : "FAIL");
            if (!plus2s) {
              failed.add(
                  String.format(
                      Locale.ROOT,
                      "%s cds=%s port +%.0f ms > 2,000 ms (N-04, Gate 0)",
                      app,
                      cds,
                      increase));
            }
            if (app.equals(HEAVY_APP)) {
              boolean tenPercent = relative <= 0.10;
              row.put("plus10PercentVerdict", tenPercent ? "PASS" : "FAIL");
              if (!tenPercent) {
                failed.add(
                    String.format(
                        Locale.ROOT,
                        "%s cds=%s port %+.1f%% > 10%% (N-04, N-27)",
                        app,
                        cds,
                        relative * 100));
              }
            }
            boolean premainOk = !(Stats.median(premain) > 500);
            row.put(
                "premainVerdict", premain.length == 0 ? "NO DATA" : premainOk ? "PASS" : "FAIL");
            if (premain.length == 0) {
              invalid = true;
              reasons.add(
                  String.format(
                      Locale.ROOT,
                      "%s cds=%s: no premain time in the agent logs (N-30)",
                      app,
                      cds));
            }
            if (premain.length > 0 && !premainOk) {
              failed.add(
                  String.format(
                      Locale.ROOT,
                      "%s cds=%s premain median %.0f ms > 500 ms (N-30)",
                      app,
                      cds,
                      Stats.median(premain)));
            }
            reasons.addAll(failed);
            fail |= !failed.isEmpty();
          }
          rows.add(row);
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", "startup");
    result.put("rows", rows);
    result.put("verdict", invalid ? "INVALID" : fail ? "FAIL" : "PASS");
    result.put("reasons", reasons);
    return result;
  }

  private static double[] before(
      List<Map<String, Object>> runs, String app, String cds, String variant) {
    return runs.stream()
        .filter(
            r ->
                app.equals(r.get("app"))
                    && cds.equals(r.get("cds"))
                    && variant.equals(r.get("variant")))
        .mapToDouble(r -> Json.number(r, "before", "cpuPercent5s"))
        .toArray();
  }

  static String markdown(Map<String, Object> set, Map<String, Object> result) {
    StringBuilder md = new StringBuilder();
    Map<String, Object> p = Json.object(set.get("parameters"));
    md.append("# Agent startup set: ").append(result.get("verdict")).append("\n\n");
    md.append("Generated by test/bench (18 §7, set level). ")
        .append(p.get("definition"))
        .append(".\n\n");
    md.append("- label: ").append(set.get("label")).append('\n');
    md.append("- apps ")
        .append(p.get("apps"))
        .append(", ")
        .append(p.get("runsPerVariant"))
        .append(" starts per variant, CDS ")
        .append(p.get("cds"))
        .append(", ")
        .append(p.get("order"))
        .append('\n');
    md.append("- JVM: ")
        .append(p.get("jvmOptions"))
        .append(", agent settings ")
        .append(p.get("agentConfig"))
        .append('\n');
    md.append("- JDK: ")
        .append(String.valueOf(Json.at(set, "environment", "javaVersion")).replace('\n', ' '))
        .append('\n');
    md.append("- started ")
        .append(set.get("startedAt"))
        .append(", finished ")
        .append(set.get("finishedAt"))
        .append('\n');
    md.append("- reasons: ").append(result.get("reasons")).append("\n\n");
    md.append(
        "| app | CDS | variant | n | port off ms | port on ms | increase ms | increase % | ready off ms | ready on ms | Spring Started off s | on s | premain median ms | CPU before off/on % | +2 s | +10% (heavy) | premain |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Object row : Json.array(result.get("rows"))) {
      String v = String.valueOf(Json.at(row, "variant"));
      md.append("| ")
          .append(Json.at(row, "app"))
          .append(" | ")
          .append(Json.at(row, "cds"))
          .append(" | ")
          .append(v)
          .append(" | ")
          .append(Json.at(row, "n"))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "offPortMedianMs"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, v + "PortMedianMs"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "portIncreaseMs"), 0))
          .append(" | ")
          .append(LoadReport.pct(Json.number(row, "portIncreaseRelative")))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "offReadyMedianMs"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, v + "ReadyMedianMs"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "offSpringStartedMedianS"), 3))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, v + "SpringStartedMedianS"), 3))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "premainMedianMs"), 1))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "offCpuBeforeMedianPercent"), 0))
          .append(" / ")
          .append(LoadReport.fmt(Json.number(row, v + "CpuBeforeMedianPercent"), 0))
          .append(" | ")
          .append(orDash(Json.at(row, "plus2sVerdict")))
          .append(" | ")
          .append(orDash(Json.at(row, "plus10PercentVerdict")))
          .append(" | ")
          .append(orDash(Json.at(row, "premainVerdict")))
          .append(" |\n");
    }
    md.append(
        "\nRaw files per start: runs/<NNN>-<app>-<cds>-<variant>/ (run.json, command.txt, stdout.log, stderr.log, agent-logs/).\n");
    return md.toString();
  }

  private static String orDash(Object o) {
    return o == null ? "-" : String.valueOf(o);
  }
}
