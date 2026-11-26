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
import java.util.Map;
import java.util.Set;

/**
 * The agent part of the Gate 1 report (03 gates, 18 §16.1): one row per T-NFR-01..04 and 26..30
 * with the requirement, its limit, the numbers, PASS, FAIL or INVALID and the evidence (report and
 * sets), from the summaries of one or more published reports. No PASS without numbers: a row is
 * PASS only when a report has every check of the requirement with a number within its limit from
 * valid sets; a valid FAIL in any report is FAIL. The fragment also lists what only a person can
 * confirm (dedicated machines, target hardware). WO-53 gathers the fragments into {@code
 * docs/bench/gate1.md}.
 */
final class Gate1Report {
  private Gate1Report() {}

  /** One Gate 1 row: requirement, test ID, subject and limit (01 §7.2). */
  record Row(String requirement, String test, String subject, String limit) {}

  static final List<Row> ROWS =
      List.of(
          new Row(
              "N-01",
              "T-NFR-01",
              "agent CPU overhead",
              "relative mean <= 5%, 1-minute peak <= 8%, agent threads <= 1% of the host"),
          new Row(
              "N-02",
              "T-NFR-02",
              "agent heap",
              "io.argos.agent.* after 2 GC <= 64 MB (72-hour slope: T-SOAK-001, WO-52)"),
          new Row(
              "N-03",
              "T-NFR-03",
              "added latency",
              "p99 increase <= max(1 ms, 5% of the off p99); p99.9 reported"),
          new Row(
              "N-04",
              "T-NFR-04",
              "application startup",
              "port-open median +2 s at most (CDS default and off), heavy app +10% at most"),
          new Row(
              "N-26",
              "T-NFR-26",
              "agent non-heap",
              "metaspace +32 MB, direct memory 8 MB, agent threads 6"),
          new Row(
              "N-27",
              "T-NFR-27",
              "transformation cost",
              "heavy app startup +10% at most (class list baseline: T-AG-005)"),
          new Row(
              "N-28",
              "T-NFR-28",
              "virtual threads",
              "N-01..N-03 limits with spring.threads.virtual.enabled=true"),
          new Row(
              "N-29",
              "T-NFR-29",
              "container --cpus=2 --memory=4g",
              "relative CPU <= 8%, N-03 p99 limit"),
          new Row("N-30", "T-NFR-30", "agent startup", "premain median <= 500 ms"));

  /** What only a person can confirm (WO-51 plan §6), listed in every fragment. */
  static final List<String> HUMAN_CHECKS =
      List.of(
          "The full 5-hour protocol (benchTest) once on the dedicated machines argos-bench-app and"
              + " argos-bench-load, and the weekly bench.yml run with its pull request (19 U-10,"
              + " U-11; 18 open item 2).",
          "Valid numbers and Gate 1 verdicts of T-NFR-01..04 and 26..30. N-30 is judged on one run"
              + " on the dedicated machine; PremainTimeAttachTest (agent-testing) is a reference"
              + " only (ADR-0010 C-240 (7)).",
          "ADR-0010 C-244 (7): the 17 open item 9 and §7.3 ratios are decided after the full"
              + " serverLoadTest run on target hardware (WO-51, WO-53).",
          "ADR-0010 C-245 (1): the class histogram of that full serverLoadTest run re-checks the"
              + " alert window memory (339.4 MiB extrapolated for 30 apps x 500 services, budget"
              + " 400 MiB).",
          "WO-05 manual acceptance (the WO-02 numbers reproduced, docs/poc/agent-overhead.md 9.4),"
              + " the noise threshold (18 open item 3, bench.sh noise) and H-2, H-5, H-6 (ADR-0009"
              + " C-80) after the first valid dedicated run.",
          "The two-machine path (--k6-ssh, --loader) on real machines (H-4) and the OpenTelemetry"
              + " reference numbers on the dedicated machine.");

  /** {@code gate1 --reports F[,F...] --out F}. Exit 0 all PASS, 3 a FAIL, 4 otherwise. */
  static int run(Options o) throws IOException {
    List<String> files = o.list("reports", "");
    if (files.isEmpty()) {
      throw new UsageException("--reports REPORT.md[,REPORT.md...] is required");
    }
    Path out = Path.of(o.required("out")).toAbsolutePath().normalize();
    o.rejectUnknown();
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (String f : files) {
      Path file = Path.of(f).toAbsolutePath().normalize();
      Map<String, Object> summary = Regression.readSummary(file);
      if (summary.get("name") == null) {
        summary.put("name", file.getFileName().toString());
      }
      summaries.add(summary);
    }
    Map<String, Object> model = build(summaries);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(out, markdown(model), StandardCharsets.UTF_8);
    String base = out.getFileName().toString();
    String json = (base.endsWith(".md") ? base.substring(0, base.length() - 3) : base) + ".json";
    FileTools.writeJson(out.resolveSibling(json), model);
    LoadSet.log("gate 1 fragment " + out + ": " + model.get("verdict"));
    return switch (String.valueOf(model.get("verdict"))) {
      case "PASS" -> 0;
      case "FAIL" -> 3;
      default -> 4;
    };
  }

  /** The fragment model from report summaries. Pure (tested without files). */
  static Map<String, Object> build(List<Map<String, Object>> summaries) {
    List<Object> rows = new ArrayList<>();
    String overall = "PASS";
    for (Row row : ROWS) {
      Map<String, Object> r = row(row, summaries);
      rows.add(r);
      String v = String.valueOf(r.get("verdict"));
      if (v.equals("FAIL")) {
        overall = "FAIL";
      } else if (v.equals("INVALID") && !overall.equals("FAIL")) {
        overall = "INVALID";
      }
    }
    List<Object> reports = new ArrayList<>();
    for (Map<String, Object> s : summaries) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", s.get("name"));
      m.put("verdict", s.get("verdict"));
      m.put("approved", Boolean.TRUE.equals(s.get("approved")));
      m.put("machineProfile", s.get("machineProfile"));
      m.put("exporter", s.get("exporter"));
      m.put("encoding", s.get("encoding"));
      reports.add(m);
    }
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("format", "argos-gate1-agent/1");
    model.put("verdict", overall);
    model.put("rows", rows);
    model.put("reports", reports);
    model.put("humanChecks", HUMAN_CHECKS);
    model.put("encodingNote", Regression.C145_NOTE);
    return model;
  }

  /**
   * One row: FAIL when a report has a valid FAIL; else PASS when a report has a valid PASS with
   * every number; else INVALID with the reasons of every report.
   */
  static Map<String, Object> row(Row row, List<Map<String, Object>> summaries) {
    List<String> pass = new ArrayList<>();
    List<String> fail = new ArrayList<>();
    Set<String> reasons = new LinkedHashSet<>();
    List<String> notes = new ArrayList<>();
    Object numbersFrom = null;
    List<String> numbers = new ArrayList<>();
    for (Map<String, Object> s : summaries) {
      String report = String.valueOf(s.get("name"));
      Object detail = Json.at(s, "requirementDetails", row.requirement());
      String claimed = String.valueOf(Json.at(s, "requirements", row.requirement()));
      if (detail == null) {
        reasons.add(
            report
                + ": no numbers in its summary (report made before WO-51 or without this"
                + " requirement)"
                + (claimed.equals("PASS") ? "; its PASS is not taken without numbers" : ""));
        continue;
      }
      List<String> checks = checks(detail);
      boolean complete = !checks.isEmpty() && !checks.contains(null);
      String verdict = String.valueOf(Json.at(detail, "verdict"));
      String evidence = report + " (" + sources(detail) + ")";
      if (complete && (numbersFrom == null || verdict.equals("FAIL") || verdict.equals("PASS"))) {
        numbersFrom = report;
        numbers = checks;
      }
      switch (verdict) {
        case "PASS" -> {
          if (complete) {
            pass.add(evidence);
          } else {
            reasons.add(report + ": PASS without every number is not taken (18 §7.6)");
          }
        }
        case "FAIL" -> fail.add(evidence);
        default -> {
          for (Object reason : Json.array(Json.at(detail, "invalidReasons"))) {
            reasons.add(report + ": " + reason);
          }
          if (!complete) {
            reasons.add(report + ": " + Json.at(detail, "outcome"));
          }
        }
      }
      for (Object note : Json.array(Json.at(detail, "notes"))) {
        String text = String.valueOf(note);
        if (!notes.contains(text)) {
          notes.add(text);
        }
      }
    }
    if (summaries.isEmpty()) {
      reasons.add("no report");
    }
    String verdict = !fail.isEmpty() ? "FAIL" : !pass.isEmpty() ? "PASS" : "INVALID";
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("requirement", row.requirement());
    out.put("test", row.test());
    out.put("subject", row.subject());
    out.put("limit", row.limit());
    out.put("numbers", numbers);
    out.put("numbersFrom", numbersFrom);
    out.put("verdict", verdict);
    out.put("evidence", verdict.equals("FAIL") ? fail : verdict.equals("PASS") ? pass : List.of());
    out.put("reasons", verdict.equals("INVALID") ? new ArrayList<>(reasons) : List.of());
    out.put("notes", notes);
    return out;
  }

  /** The checks of a requirement detail as text; a check without a number is null. */
  private static List<String> checks(Object detail) {
    List<String> out = new ArrayList<>();
    for (Object c : Json.array(Json.at(detail, "checks"))) {
      double value = Json.number(c, "value");
      double limit = Json.number(c, "limit");
      if (Double.isNaN(value) || Double.isNaN(limit)) {
        out.add(null);
        continue;
      }
      String unit = String.valueOf(Json.at(c, "unit"));
      out.add(new BenchReport.Check(String.valueOf(Json.at(c, "what")), value, limit, unit).text());
    }
    return out;
  }

  private static String sources(Object detail) {
    List<String> sets = new ArrayList<>();
    for (Object s : Json.array(Json.at(detail, "sources"))) {
      sets.add(String.valueOf(s));
    }
    return sets.isEmpty() ? "no set" : String.join(", ", sets);
  }

  static String markdown(Map<String, Object> model) {
    StringBuilder md = new StringBuilder("# Gate 1, agent overhead: ");
    md.append(model.get("verdict")).append("\n\n");
    md.append(
        "Generated by `test/bench/bench.sh gate1` from the summaries of the reports below: the"
            + " T-NFR-01..04 and 26..30 rows of the Gate 1 report (03 gates, 18 §16.1). A row is"
            + " PASS only with every number within its limit from valid sets; WO-53 gathers this"
            + " fragment into docs/bench/gate1.md.\n\n");
    md.append(
        "| test | requirement | subject | limit | numbers | verdict | evidence | why not decided"
            + " / notes |\n|---|---|---|---|---|---|---|---|\n");
    for (Object r : Json.array(model.get("rows"))) {
      List<String> numbers = new ArrayList<>();
      for (Object n : Json.array(Json.at(r, "numbers"))) {
        numbers.add(String.valueOf(n));
      }
      List<String> why = new ArrayList<>();
      for (Object x : Json.array(Json.at(r, "reasons"))) {
        why.add(String.valueOf(x));
      }
      for (Object x : Json.array(Json.at(r, "notes"))) {
        why.add(String.valueOf(x));
      }
      List<String> evidence = new ArrayList<>();
      for (Object x : Json.array(Json.at(r, "evidence"))) {
        evidence.add(String.valueOf(x));
      }
      md.append("| ")
          .append(Json.at(r, "test"))
          .append(" | ")
          .append(Json.at(r, "requirement"))
          .append(" | ")
          .append(Json.at(r, "subject"))
          .append(" | ")
          .append(BenchReport.cell(String.valueOf(Json.at(r, "limit"))))
          .append(" | ")
          .append(
              numbers.isEmpty()
                  ? "-"
                  : BenchReport.cell(
                      String.join("; ", numbers) + " (" + Json.at(r, "numbersFrom") + ")"))
          .append(" | **")
          .append(Json.at(r, "verdict"))
          .append("** | ")
          .append(evidence.isEmpty() ? "-" : BenchReport.cell(String.join("; ", evidence)))
          .append(" | ")
          .append(why.isEmpty() ? "-" : BenchReport.cell(String.join("; ", why)))
          .append(" |\n");
    }
    md.append("\n## Reports\n\n| report | verdict | approved | machine profile | exporter |\n");
    md.append("|---|---|---|---|---|\n");
    for (Object r : Json.array(model.get("reports"))) {
      md.append("| ")
          .append(Json.at(r, "name"))
          .append(" | ")
          .append(Json.at(r, "verdict"))
          .append(" | ")
          .append(Boolean.TRUE.equals(Json.at(r, "approved")) ? "yes" : "no")
          .append(" | ")
          .append(Json.at(r, "machineProfile"))
          .append(" | ")
          .append(Json.at(r, "exporter"))
          .append(" (encoding ")
          .append(Json.at(r, "encoding"))
          .append(") |\n");
    }
    md.append('\n').append(model.get("encodingNote")).append("\n\n");
    md.append("## Still needed from a person\n\n");
    for (Object h : Json.array(model.get("humanChecks"))) {
      md.append("- ").append(h).append('\n');
    }
    return md.toString();
  }
}
