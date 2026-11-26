/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chaos report ({@code report.md}) and its machine-readable form ({@code results.json}: per
 * scenario the verdict, the reason and every row with its verdict and value), plus the T-NFR-05 row
 * (N-05 "수집 서버 장애 시 앱 영향 0") aggregated from T-CH-001..003 (18 appendix A: N-05 = T-CH-001~003).
 */
public final class ChaosReport {

  /** The scenarios whose rows make the T-NFR-05 row. */
  static final Set<String> N05_SCENARIOS = Set.of("T-CH-001", "T-CH-002", "T-CH-003");

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  /**
   * What the report says about the run.
   *
   * @param runId run id
   * @param started start
   * @param finished end
   * @param commit {@code git rev-parse --short HEAD}, or "unknown"
   * @param machineProfile {@code -Pbench.machine-profile}
   * @param profile {@code short} or {@code full}
   * @param rate requests per second
   * @param valid whether timing verdicts may be PASS/FAIL
   * @param environment host OS, Docker, toxiproxy network, JDK
   * @param notes run notes (deviations, aborts)
   */
  public record RunInfo(
      String runId,
      Instant started,
      Instant finished,
      String commit,
      String machineProfile,
      String profile,
      int rate,
      boolean valid,
      String environment,
      List<String> notes) {

    /** Copies. */
    public RunInfo {
      notes = List.copyOf(notes);
    }
  }

  private ChaosReport() {}

  /** The T-NFR-05 row from the scenarios' p99, ERROR and accounting rows. */
  static Check n05(List<ScenarioResult> results) {
    List<ScenarioResult> ran =
        results.stream()
            .filter(r -> N05_SCENARIOS.contains(r.id()) && r.verdict() != Verdict.SKIPPED)
            .toList();
    if (ran.isEmpty()) {
      return new Check(
          "T-NFR-05",
          "app impact 0 during collector failures (N-05)",
          "T-CH-001..003: p99 within the noise band, agent ERROR 0, drops counted",
          "-",
          Check.Kind.INVARIANT,
          Verdict.SKIPPED,
          "none of T-CH-001..003 ran");
    }
    List<String> parts = new ArrayList<>();
    List<Check> rows = new ArrayList<>();
    for (ScenarioResult r : ran) {
      List<Check> relevant =
          r.checks().stream()
              .filter(
                  c ->
                      c.title().startsWith("app p99")
                          || c.title().startsWith("agent log ERROR")
                          || c.title().startsWith("every transaction stored"))
              .toList();
      rows.addAll(relevant);
      if (r.checks().isEmpty()) {
        rows.add(
            new Check(r.id(), "aborted", "", r.reason(), Check.Kind.INVARIANT, Verdict.FAIL, ""));
        parts.add(r.id() + " FAIL (aborted)");
        continue;
      }
      parts.add(r.id() + " " + Judge.scenario(relevant));
    }
    Verdict verdict = Judge.scenario(rows);
    return new Check(
        "T-NFR-05",
        "app impact 0 during collector failures (N-05)",
        "T-CH-001..003: p99 within the noise band, agent ERROR 0, drops counted",
        String.join(", ", parts),
        Check.Kind.INVARIANT,
        verdict,
        ran.size() < N05_SCENARIOS.size() ? "not every scenario of T-CH-001..003 ran" : "");
  }

  /** The Markdown report. */
  public static String markdown(RunInfo run, List<ScenarioResult> results) {
    StringBuilder md = new StringBuilder();
    md.append("# Chaos test report ").append(run.runId()).append("\n\n");
    if (!run.valid()) {
      md.append("> **INVALID run.** Profile `")
          .append(run.profile())
          .append("` on machine profile `")
          .append(run.machineProfile())
          .append("`: timing and p99 rows are reference values, not verdicts. A valid run is")
          .append(" profile `full` on `")
          .append(String.join("`, `", Judge.DEDICATED_PROFILES))
          .append("` (18 §11.1, §2.3). Invariant rows are verdicts in every run.\n\n");
    }
    md.append("| | |\n|---|---|\n");
    row(md, "Run", run.runId());
    row(md, "Started / finished (UTC)", run.started() + " / " + run.finished());
    row(md, "Commit", run.commit());
    row(md, "Machine profile", run.machineProfile());
    row(md, "Profile", run.profile());
    row(md, "Load", run.rate() + " requests/s, demo load generator mix `" + LoadRun.MIX + "`");
    row(md, "Environment", run.environment());
    md.append('\n');

    md.append("## Summary\n\n| ID | Scenario | Verdict | Reason |\n|---|---|---|---|\n");
    for (ScenarioResult r : results) {
      md.append("| ")
          .append(r.id())
          .append(" | ")
          .append(cell(r.title()))
          .append(" | ")
          .append(r.verdict())
          .append(" | ")
          .append(cell(r.reason()))
          .append(" |\n");
    }
    Check n05 = n05(results);
    md.append("| ")
        .append(n05.id())
        .append(" | ")
        .append(cell(n05.title()))
        .append(" | ")
        .append(n05.verdict())
        .append(" | ")
        .append(cell(n05.measured() + (n05.note().isEmpty() ? "" : "; " + n05.note())))
        .append(" |\n\n");

    md.append("## Method\n\n");
    md.append(
        "- Stack: PostgreSQL and ClickHouse (Testcontainers), the server boot jar as a child JVM,"
            + " toxiproxy (pinned by digest) between the agent and the collector, shop-boot3 with the"
            + " agent on JDK 21, ext-stub in process. 18 §11.1 describes a Compose stack and"
            + " `docker stop argos-server`; the runner stops the server JVM (SIGTERM on POSIX) and"
            + " starts it again on the same WAL.\n");
    md.append(
        "- Load: the demo load generator (`sample-apps/load`, open model, fixed rate) instead of"
            + " k6 (18 §11.1); one run per scenario over baseline, fault and recovery, a p99 per 10 s"
            + " interval.\n");
    md.append(
        "- Noise band: upper = max(baseline interval p99) + max(spread, 10 % of the baseline"
            + " median); the first baseline interval is left out; the fault window is judged by the"
            + " median of its interval p99 values.\n");
    md.append(
        "- Recovery: from the fault's removal until the instance is UP or WARN with a heartbeat"
            + " after the removal and the first xlog row of a transaction after the removal is"
            + " stored; target 60 s.\n");
    md.append(
        "- Drop accounting: stored xlog rows + agent drops (`argos_agent_dropped_total{queue="
            + "\"transaction\"}`) + server drops seen by the agent = answered requests (a range when"
            + " requests had no answer).\n");
    md.append(
        "- Verdicts: invariants are PASS/FAIL in every run; timing and p99 rows only in a valid"
            + " run; INFO rows are references.\n\n");

    for (ScenarioResult r : results) {
      md.append("## ").append(r.id()).append(' ').append(r.title()).append("\n\n");
      md.append("Verdict: **").append(r.verdict()).append("**");
      if (!r.reason().isEmpty()) {
        md.append(" (").append(r.reason()).append(')');
      }
      md.append("\n\n");
      if (!r.description().isEmpty()) {
        md.append("Fault: ").append(r.description()).append("\n\n");
      }
      if (r.faultStart() != null) {
        md.append("Timeline (UTC): start ")
            .append(r.start())
            .append(", fault ")
            .append(r.faultStart())
            .append(" .. ")
            .append(r.faultEnd())
            .append(", end ")
            .append(r.end())
            .append("\n\n");
      }
      if (!r.checks().isEmpty()) {
        md.append("| Check | Target | Measured | Kind | Verdict | Note |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Check c : r.checks()) {
          md.append("| ")
              .append(cell(c.title()))
              .append(" | ")
              .append(cell(c.target()))
              .append(" | ")
              .append(cell(c.measured()))
              .append(" | ")
              .append(c.kind().name().toLowerCase(Locale.ROOT))
              .append(" | ")
              .append(c.verdict())
              .append(" | ")
              .append(cell(c.note()))
              .append(" |\n");
        }
        md.append('\n');
      }
      for (String note : r.notes()) {
        md.append("- ").append(note).append('\n');
      }
      if (!r.notes().isEmpty()) {
        md.append('\n');
      }
    }
    if (!run.notes().isEmpty()) {
      md.append("## Notes\n\n");
      for (String note : run.notes()) {
        md.append("- ").append(note).append('\n');
      }
      md.append('\n');
    }
    return md.toString();
  }

  /** The JSON results. */
  public static String json(RunInfo run, List<ScenarioResult> results) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("tool", "argos-chaos");
    root.put("run", run.runId());
    root.put("started", run.started().toString());
    root.put("finished", run.finished().toString());
    root.put("commit", run.commit());
    root.put("machineProfile", run.machineProfile());
    root.put("profile", run.profile());
    root.put("rate", run.rate());
    root.put("valid", run.valid());
    root.put("environment", run.environment());
    List<Object> scenarios = new ArrayList<>();
    for (ScenarioResult r : results) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("id", r.id());
      s.put("title", r.title());
      s.put("verdict", r.verdict().name());
      s.put("reason", r.reason());
      s.put("checks", r.checks().stream().map(ChaosReport::checkJson).toList());
      scenarios.add(s);
    }
    Check n05 = n05(results);
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", n05.id());
    s.put("title", n05.title());
    s.put("verdict", n05.verdict().name());
    s.put("reason", n05.note());
    s.put("checks", List.of(checkJson(n05)));
    scenarios.add(s);
    root.put("scenarios", scenarios);
    root.put("notes", run.notes());
    return JSON.writeValueAsString(root) + "\n";
  }

  private static Map<String, Object> checkJson(Check c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.id());
    m.put("title", c.title());
    m.put("target", c.target());
    m.put("value", c.measured());
    m.put("kind", c.kind().name());
    m.put("verdict", c.verdict().name());
    m.put("reason", c.note());
    return m;
  }

  private static void row(StringBuilder md, String key, String value) {
    md.append("| ").append(key).append(" | ").append(cell(value)).append(" |\n");
  }

  /** A table cell: pipes escaped, line breaks as spaces. */
  static String cell(String text) {
    return text == null ? "" : text.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
  }
}
