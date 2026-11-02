/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The benchmark report of 18 §7.6, {@code docs/bench/<YYYY-MM-DD>-<commit7>.md}, over one or more
 * sets (load sets: main, worst profile, virtual threads, container; a startup set): 1 environment,
 * 2 run parameters, 3 raw per-round table, 4 statistics with confidence intervals, 5 memory,
 * threads and startup, 6 verdicts of N-01..N-04 and N-26..N-30 (PASS, FAIL or INVALID), 7 graphs
 * (SVG and TSV next to the report), 8 reproduction commands; then the 18 §7.7 regression check
 * against a previous approved report, and a machine-readable summary.
 *
 * <p>No PASS without numbers: a requirement is PASS only when all its checks have numbers within
 * their limits, its sets are valid (harness verdict, 01 §7.3 protocol, 18 §7.1 environment), and
 * the report as a whole is PASS only when all nine requirements are.
 */
final class BenchReport {
  private BenchReport() {}

  static final List<String> REQUIREMENTS =
      List.of("N-01", "N-02", "N-03", "N-04", "N-26", "N-27", "N-28", "N-29", "N-30");

  /** One measured set and its analysis. */
  record SetData(
      String name,
      Path dir,
      Map<String, Object> set,
      List<Map<String, Object>> runs,
      Map<String, Object> result,
      String role,
      Map<String, Object> envRecord) {

    /** Reasons why the set's numbers cannot decide a requirement. */
    List<String> problems() {
      List<String> out = new ArrayList<>();
      if ("INVALID".equals(result.get("verdict"))) {
        out.add("set " + name + ": harness verdict INVALID " + result.get("reasons"));
      }
      for (String d : Protocol.deviations(set)) {
        out.add("set " + name + ": protocol deviation: " + d);
      }
      for (String p : EnvRecord.problems(envRecord)) {
        out.add("set " + name + ": " + p);
      }
      return out;
    }
  }

  /** One limit check of a requirement. {@code unit} "relative" values are fractions. */
  record Check(String what, double value, double limit, String unit) {
    boolean missing() {
      return Double.isNaN(value) || Double.isNaN(limit);
    }

    boolean over() {
      return value > limit;
    }

    String text() {
      return what + " " + format(value, unit) + " (limit " + format(limit, unit) + ")";
    }
  }

  /** A requirement row of part 6. */
  static final class Requirement {
    final String id;
    final String test;
    final String criterion;
    final Set<String> sources = new LinkedHashSet<>();
    final List<Check> checks = new ArrayList<>();
    final Set<String> invalid = new LinkedHashSet<>();
    final List<String> notes = new ArrayList<>();

    Requirement(String id, String test, String criterion) {
      this.id = id;
      this.test = test;
      this.criterion = criterion;
    }

    /** True when every check has a number (and there is at least one check). */
    boolean measured() {
      return !checks.isEmpty() && checks.stream().noneMatch(Check::missing);
    }

    /** What the numbers say, whatever the validity. */
    String outcome() {
      if (!measured()) {
        return "no numbers";
      }
      return checks.stream().anyMatch(Check::over) ? "over the limit" : "within the limits";
    }

    /** PASS only with numbers, within the limits, from valid sets. */
    String verdict() {
      if (!invalid.isEmpty() || !measured()) {
        return "INVALID";
      }
      return checks.stream().anyMatch(Check::over) ? "FAIL" : "PASS";
    }

    Map<String, Object> toJson() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("verdict", verdict());
      out.put("outcome", outcome());
      out.put("test", test);
      List<Object> cs = new ArrayList<>();
      for (Check c : checks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("what", c.what());
        m.put("value", c.value());
        m.put("limit", c.limit());
        m.put("unit", c.unit());
        cs.add(m);
      }
      out.put("checks", cs);
      out.put("sources", new ArrayList<>(sources));
      out.put("invalidReasons", new ArrayList<>(invalid));
      out.put("notes", notes);
      return out;
    }
  }

  // ---- command ---------------------------------------------------------------------------------

  static int publish(Options o) throws Exception {
    List<String> setDirs = o.list("sets", "");
    if (setDirs.isEmpty()) {
      throw new UsageException("--sets DIR[,DIR...] is required");
    }
    Path out = Path.of(o.string("out", "docs/bench")).toAbsolutePath().normalize();
    Path baseline = o.optional("baseline").map(Path::of).orElse(null);
    String name = o.optional("name").orElse(null);
    boolean overwrite = o.flag("overwrite");
    String command = o.string("command", "");
    long seed = o.integer("seed", (int) Bootstrap.DEFAULT_SEED, 0, Integer.MAX_VALUE);
    o.rejectUnknown();

    List<SetData> sets = new ArrayList<>();
    for (String d : setDirs) {
      sets.add(load(Path.of(d).toAbsolutePath().normalize(), seed));
    }
    Map<String, Object> baselineSummary = null;
    if (baseline != null) {
      baselineSummary = Regression.readSummary(baseline);
    }
    String reportName = name != null ? name : defaultName(sets);
    Path md = out.resolve(reportName + ".md");
    if (Files.exists(md) && !overwrite) {
      throw new UsageException(md + " exists (choose --name or pass --overwrite)");
    }
    Files.createDirectories(out.resolve(reportName));
    Map<String, Object> model =
        evaluate(
            sets,
            reportName,
            baselineSummary,
            baseline == null ? null : baseline.getFileName().toString());
    List<String> graphs = writeGraphs(out.resolve(reportName), reportName, sets);
    String text = markdown(model, sets, graphs, command, setDirs, out, baseline);
    Files.writeString(md, text, StandardCharsets.UTF_8);
    FileTools.writeJson(out.resolve(reportName).resolve("summary.json"), model.get("summary"));
    LoadSet.log(
        "report "
            + md
            + ": "
            + model.get("verdict")
            + " (regression "
            + Json.at(model, "regression", "verdict")
            + ")");
    return switch (String.valueOf(model.get("verdict"))) {
      case "PASS" -> 0;
      case "FAIL" -> 3;
      default -> 4;
    };
  }

  /** Reads a set directory and analyses it like {@code report} does. */
  static SetData load(Path dir, long seed) throws IOException {
    Path setFile = dir.resolve("set.json");
    if (!Files.isRegularFile(setFile)) {
      throw new UsageException("no set.json in " + dir);
    }
    Map<String, Object> set = Json.object(FileTools.readJson(setFile));
    List<Map<String, Object>> runs = LoadReport.readRuns(dir);
    Map<String, Object> result;
    if ("load".equals(set.get("kind"))) {
      LoadReport.addGcWindows(dir, runs);
      result = LoadReport.analyze(set, runs, seed);
    } else if ("startup".equals(set.get("kind"))) {
      result = StartupReport.analyze(runs);
    } else {
      throw new UsageException("unknown set kind " + set.get("kind") + " in " + setFile);
    }
    return new SetData(
        dir.getFileName().toString(), dir, set, runs, result, role(set), EnvRecord.ofSet(dir));
  }

  /** main, worst, vt, container (load sets) or startup. */
  static String role(Map<String, Object> set) {
    if ("startup".equals(set.get("kind"))) {
      return "startup";
    }
    Map<String, Object> p = Json.object(set.get("parameters"));
    if ("container".equals(p.get("mode"))) {
      return "container";
    }
    if (Json.object(p.get("appEnv")).containsKey("SAMPLE_VT")) {
      return "vt";
    }
    if ("worst".equals(p.get("profile"))) {
      return "worst";
    }
    return "main";
  }

  /** {@code <YYYY-MM-DD>-<commit7>}: the UTC date of the earliest set start and its commit. */
  static String defaultName(List<SetData> sets) {
    String date = null;
    String commit = null;
    for (SetData s : sets) {
      Object started = s.set().get("startedAt");
      if (started instanceof String t
          && t.length() >= 10
          && (date == null || t.compareTo(date) < 0)) {
        date = t;
      }
      Object c = Json.at(s.set(), "git", "commit");
      if (commit == null && c instanceof String text && text.matches("[0-9a-f]{7,40}")) {
        commit = text.substring(0, 7);
      }
    }
    return (date == null ? Instant.now().toString() : date).substring(0, 10)
        + "-"
        + (commit == null ? "nocommit" : commit);
  }

  // ---- evaluation ------------------------------------------------------------------------------

  /**
   * The report model: requirement verdicts, overall verdict, regression and the summary. Pure
   * (tested without files).
   */
  static Map<String, Object> evaluate(
      List<SetData> sets,
      String reportName,
      Map<String, Object> baselineSummary,
      String baselineName) {
    List<SetData> mains = byRole(sets, "main", "worst");
    List<SetData> vts = byRole(sets, "vt");
    List<SetData> containers = byRole(sets, "container");
    List<SetData> startups = byRole(sets, "startup");
    Map<String, Requirement> reqs = new LinkedHashMap<>();

    Requirement n01 =
        new Requirement(
            "N-01",
            "T-NFR-01",
            "relative CPU mean <= 5%, 1-minute peak <= 8%, agent threads' CPU <= 1% of the host");
    for (SetData s : mains) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n01.checks.add(check(s, mains, "relative CPU", on, 0.05, "relative", "cpu", "relative"));
      n01.checks.add(check(s, mains, "1-minute peak", on, 0.08, "relative", "peak", "value"));
      n01.checks.add(
          check(s, mains, "agent thread CPU", on, 1.0, "% of host", "agentThreadCpu", "value"));
      use(n01, s);
    }
    noSet(n01, mains, "main");
    reqs.put("N-01", n01);

    Requirement n02 =
        new Requirement("N-02", "T-NFR-02", "agent heap (io.argos.agent.* after 2 GC) <= 64 MB");
    for (SetData s : mains) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n02.checks.add(check(s, mains, "agent heap", on, 64, "MB", "memory", "agentHeapMb", "value"));
      use(n02, s);
    }
    n02.notes.add("72-hour slope <= 0 is T-SOAK-001 (soakTest, WO-52), not part of this report");
    noSet(n02, mains, "main");
    reqs.put("N-02", n02);

    Requirement n03 =
        new Requirement(
            "N-03", "T-NFR-03", "p99 increase <= max(1 ms, 5% of the off p99); p99.9 reported");
    for (SetData s : mains) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n03.checks.add(p99Check(s, mains, on));
      use(n03, s);
      n03.notes.add(latencyNote(s, on));
    }
    noSet(n03, mains, "main");
    reqs.put("N-03", n03);

    Requirement n04 =
        new Requirement(
            "N-04",
            "T-NFR-04",
            "port-open median +2 s at most (every app, CDS default and off) and the heavy app"
                + " +10% at most");
    Requirement n27 =
        new Requirement(
            "N-27", "T-NFR-04 (heavy)", "heavy sample app port-open median +10% at most");
    Requirement n30 = new Requirement("N-30", "T-NFR-30", "premain median <= 500 ms");
    for (SetData s : startups) {
      boolean heavy = false;
      Set<String> cds = new LinkedHashSet<>();
      for (Object row : Json.array(s.result().get("rows"))) {
        if (!"on".equals(Json.at(row, "variant"))) {
          continue;
        }
        String cell = Json.at(row, "app") + "/" + Json.at(row, "cds");
        cds.add(String.valueOf(Json.at(row, "cds")));
        n04.checks.add(
            new Check(cell + " increase", Json.number(row, "portIncreaseMs"), 2000, "ms"));
        if (StartupReport.HEAVY_APP.equals(Json.at(row, "app"))) {
          heavy = true;
          Check relative =
              new Check(
                  cell + " increase", Json.number(row, "portIncreaseRelative"), 0.10, "relative");
          n04.checks.add(relative);
          n27.checks.add(relative);
        }
        n30.checks.add(
            new Check(cell + " premain", Json.number(row, "premainMedianMs"), 500, "ms"));
      }
      for (Requirement r : List.of(n04, n27, n30)) {
        use(r, s);
      }
      if (!heavy) {
        n04.invalid.add("set " + s.name() + ": no " + StartupReport.HEAVY_APP + " starts");
        n27.invalid.add("set " + s.name() + ": no " + StartupReport.HEAVY_APP + " starts");
      }
      if (!cds.contains("default") || !cds.contains("off")) {
        n04.invalid.add("set " + s.name() + ": CDS modes " + cds + " (default and off needed)");
      }
    }
    noSet(n04, startups, "startup");
    noSet(n27, startups, "startup");
    n27.notes.add(
        "the transformed-class baseline of N-27 is T-AG-005 (agent-testing, check), not part of"
            + " this report");
    if (startups.isEmpty()) {
      // premain also appears in the agent logs of the load runs (fewer starts).
      for (SetData s : mains) {
        Object on = Json.at(s.result(), "comparisons", "on");
        n30.checks.add(
            check(s, mains, "premain (load runs)", on, 500, "ms", "startup", "premainMedianMs"));
        use(n30, s);
      }
      noSet(n30, mains, "startup or main");
    }
    reqs.put("N-04", n04);

    Requirement n26 =
        new Requirement(
            "N-26",
            "T-NFR-26",
            "metaspace +32 MB, direct memory (NMT Other) 8 MB, agent threads 6, NMT"
                + " Internal+Other +16 MB, threads +6 at most (01 §7.3 item 7)");
    for (SetData s : mains) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n26.checks.add(
          check(
              s,
              mains,
              "metaspace increase",
              on,
              32,
              "MB",
              "memory",
              "metaspaceIncreaseMb",
              "value"));
      n26.checks.add(
          check(
              s,
              mains,
              "direct memory (NMT Other)",
              on,
              8,
              "MB",
              "memory",
              "directMemoryIncreaseMb",
              "value"));
      n26.checks.add(
          check(s, mains, "agent threads", on, 6, "threads", "memory", "agentThreads", "value"));
      n26.checks.add(
          check(
              s,
              mains,
              "NMT Internal+Other increase",
              on,
              16,
              "MB",
              "memory",
              "nmtInternalOtherIncreaseMb",
              "value"));
      n26.checks.add(
          check(
              s, mains, "thread increase", on, 6, "threads", "memory", "threadIncrease", "value"));
      use(n26, s);
    }
    noSet(n26, mains, "main");
    reqs.put("N-26", n26);
    reqs.put("N-27", n27);

    Requirement n28 =
        new Requirement("N-28", "T-NFR-28", "virtual threads (SAMPLE_VT=true): N-01..N-03 limits");
    for (SetData s : vts) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n28.checks.add(check(s, vts, "relative CPU", on, 0.05, "relative", "cpu", "relative"));
      n28.checks.add(check(s, vts, "1-minute peak", on, 0.08, "relative", "peak", "value"));
      n28.checks.add(
          check(s, vts, "agent thread CPU", on, 1.0, "% of host", "agentThreadCpu", "value"));
      n28.checks.add(check(s, vts, "agent heap", on, 64, "MB", "memory", "agentHeapMb", "value"));
      n28.checks.add(p99Check(s, vts, on));
      use(n28, s);
    }
    noSet(n28, vts, "vt");
    reqs.put("N-28", n28);

    Requirement n29 =
        new Requirement(
            "N-29",
            "T-NFR-29",
            "container --cpus=2 --memory=4g: relative CPU <= 8%, N-03 p99 limit");
    for (SetData s : containers) {
      Object on = Json.at(s.result(), "comparisons", "on");
      n29.checks.add(check(s, containers, "relative CPU", on, 0.08, "relative", "cpu", "relative"));
      n29.checks.add(p99Check(s, containers, on));
      use(n29, s);
      n29.notes.addAll(Protocol.notes(s.set()));
    }
    noSet(n29, containers, "container");
    reqs.put("N-29", n29);
    reqs.put("N-30", n30);

    // Overall: FAIL when a valid requirement fails, else INVALID when one cannot be decided.
    String verdict = "PASS";
    int measured = 0;
    for (Requirement r : reqs.values()) {
      if (r.measured()) {
        measured++;
      }
      if (r.verdict().equals("FAIL")) {
        verdict = "FAIL";
      } else if (r.verdict().equals("INVALID") && !verdict.equals("FAIL")) {
        verdict = "INVALID";
      }
    }
    Set<String> globalProblems = new LinkedHashSet<>();
    for (SetData s : sets) {
      for (String p : EnvRecord.problems(s.envRecord())) {
        globalProblems.add(p);
      }
    }

    Map<String, Object> metrics = metrics(mains, startups);
    Map<String, Object> regression =
        Regression.compare(metrics, metricsOf(baselineSummary), baselineName);

    Map<String, Object> model = new LinkedHashMap<>();
    model.put("name", reportName);
    model.put("verdict", verdict);
    model.put("measuredRequirements", measured);
    model.put("environmentProblems", new ArrayList<>(globalProblems));
    Map<String, Object> requirements = new LinkedHashMap<>();
    reqs.forEach((k, v) -> requirements.put(k, v.toJson()));
    model.put("requirements", requirements);
    model.put("requirementObjects", reqs);
    model.put("regression", regression);
    model.put("metrics", metrics);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("format", "argos-bench-report/1");
    summary.put("name", reportName);
    summary.put("verdict", verdict);
    summary.put("approved", false);
    summary.put("measuredRequirements", measured);
    summary.put("metrics", metrics);
    Map<String, Object> verdicts = new LinkedHashMap<>();
    reqs.forEach((k, v) -> verdicts.put(k, v.verdict()));
    summary.put("requirements", verdicts);
    summary.put("regression", regression.get("verdict"));
    List<Object> setList = new ArrayList<>();
    for (SetData s : sets) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", s.name());
      m.put("role", s.role());
      m.put("harnessVerdict", s.result().get("verdict"));
      setList.add(m);
    }
    summary.put("sets", setList);
    model.put("summary", summary);
    return model;
  }

  private static Map<String, Object> metricsOf(Map<String, Object> summary) {
    return summary == null ? null : Json.object(summary.get("metrics"));
  }

  /** Headline numbers of the first main set (and the startup set) for 18 §7.7 and trends. */
  static Map<String, Object> metrics(List<SetData> mains, List<SetData> startups) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (!mains.isEmpty()) {
      Object on = Json.at(mains.get(0).result(), "comparisons", "on");
      m.put("relativeCpu", Json.number(on, "cpu", "relative"));
      m.put("peakRelativeCpu", Json.number(on, "peak", "value"));
      m.put("p99DiffMs", Json.number(on, "latency", "p99", "diffMean"));
      m.put("p999DiffMs", Json.number(on, "latency", "p99.9", "diffMean"));
      m.put("agentHeapMb", Json.number(on, "memory", "agentHeapMb", "value"));
      m.put("metaspaceIncreaseMb", Json.number(on, "memory", "metaspaceIncreaseMb", "value"));
      m.put("agentThreads", Json.number(on, "memory", "agentThreads", "value"));
      m.put("source", mains.get(0).name());
    }
    if (!startups.isEmpty()) {
      Map<String, Object> starts = new LinkedHashMap<>();
      for (Object row : Json.array(startups.get(0).result().get("rows"))) {
        if ("on".equals(Json.at(row, "variant"))) {
          starts.put(
              Json.at(row, "app") + "/" + Json.at(row, "cds"), Json.number(row, "portIncreaseMs"));
        }
      }
      m.put("startupIncreaseMs", starts);
    }
    return m;
  }

  private static List<SetData> byRole(List<SetData> sets, String... roles) {
    List<String> wanted = List.of(roles);
    return sets.stream().filter(s -> wanted.contains(s.role())).toList();
  }

  private static void use(Requirement r, SetData s) {
    r.sources.add(s.name() + " (" + s.role() + ")");
    r.invalid.addAll(s.problems());
  }

  private static void noSet(Requirement r, List<SetData> sets, String role) {
    if (sets.isEmpty()) {
      r.invalid.add("not measured: no " + role + " set in this report");
    }
  }

  private static Check check(
      SetData s,
      List<SetData> group,
      String what,
      Object comparison,
      double limit,
      String unit,
      String... path) {
    String label = group.size() > 1 ? s.role() + " " + what : what;
    return new Check(label, Json.number(comparison, path), limit, unit);
  }

  private static Check p99Check(SetData s, List<SetData> group, Object on) {
    String label = group.size() > 1 ? s.role() + " p99 increase" : "p99 increase";
    return new Check(
        label,
        Json.number(on, "latency", "p99", "diffMean"),
        Json.number(on, "latency", "p99", "check", "limit"),
        "ms");
  }

  private static String latencyNote(SetData s, Object on) {
    List<Object> boot = Json.array(Json.at(on, "latency", "p99", "diffBootstrapCi95"));
    List<Object> t = Json.array(Json.at(on, "latency", "p99", "diffCi95"));
    StringBuilder note = new StringBuilder(s.name()).append(": p99 difference 95% CI ");
    if (boot.size() == 2) {
      note.append(range(boot)).append(" ms (bootstrap over requests), ");
    }
    if (t.size() == 2) {
      note.append(range(t)).append(" ms (t over rounds)");
    }
    note.append(
            Boolean.TRUE.equals(Json.at(on, "latency", "p99", "noDifference"))
                ? ": no difference"
                : "")
        .append("; p99.9 difference ")
        .append(LoadReport.fmt(Json.number(on, "latency", "p99.9", "diffMean"), 3))
        .append(" ms");
    return note.toString();
  }

  private static String range(List<Object> pair) {
    return LoadReport.fmt(Json.number(pair.get(0)), 3)
        + " .. "
        + LoadReport.fmt(Json.number(pair.get(1)), 3);
  }

  static String format(double v, String unit) {
    if (Double.isNaN(v)) {
      return "n/a";
    }
    return switch (unit) {
      case "relative" -> String.format(Locale.ROOT, "%+.2f%%", v * 100);
      case "ms" -> String.format(Locale.ROOT, "%.3f ms", v);
      case "MB" -> String.format(Locale.ROOT, "%.2f MB", v);
      case "threads" -> String.format(Locale.ROOT, "%.0f", v);
      case "% of host" -> String.format(Locale.ROOT, "%.3f%% of host", v);
      default -> String.format(Locale.ROOT, "%.3f %s", v, unit);
    };
  }

  // ---- graphs ----------------------------------------------------------------------------------

  /** Writes the CPU timeline and latency CDF of every load set; returns Markdown lines. */
  static List<String> writeGraphs(Path dir, String reportName, List<SetData> sets)
      throws IOException {
    Files.createDirectories(dir);
    List<String> lines = new ArrayList<>();
    for (SetData s : sets) {
      if (!"load".equals(s.set().get("kind"))) {
        continue;
      }
      String base = s.name();
      // CPU time series: cores used by the app, 10-second averages of the measured window.
      List<Svg.Series> cpu = new ArrayList<>();
      StringBuilder tsv = new StringBuilder("run\tvariant\tseconds\tcores\n");
      for (Map<String, Object> r : s.runs()) {
        String variant = String.valueOf(r.get("variant"));
        if (!variant.equals("off") && !variant.equals("on")) {
          continue;
        }
        List<Object> rows = Json.array(Json.at(r, "measure", "appCpuTimeline"));
        double[] t = new double[rows.size()];
        double[] y = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
          List<Object> row = Json.array(rows.get(i));
          t[i] = Json.number(row.get(0));
          y[i] = Json.number(row.get(1));
        }
        double[] rates = Stats.windowRates(t, y, 10, 10);
        List<double[]> points = new ArrayList<>();
        for (int k = 0; k < rates.length; k++) {
          points.add(new double[] {k * 10 + 5.0, rates[k]});
          tsv.append(r.get("index"))
              .append('\t')
              .append(variant)
              .append('\t')
              .append(k * 10 + 5)
              .append('\t')
              .append(LoadReport.fmt(rates[k], 4))
              .append('\n');
        }
        cpu.add(
            new Svg.Series(
                "run " + r.get("index") + " (" + variant + ")",
                variant.equals("off") ? 0 : 1,
                points));
      }
      Files.writeString(
          dir.resolve(base + "-cpu-timeline.svg"),
          Svg.lineChart(
              "App CPU in the measured window (" + s.role() + ")",
              "seconds into the window",
              "cores (10 s averages)",
              cpu,
              false,
              List.of("off runs", "agent runs (dashed)")),
          StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(base + "-cpu-timeline.tsv"), tsv, StandardCharsets.UTF_8);

      // Latency CDF of the merged re-aggregated histograms per variant.
      List<Svg.Series> cdf = new ArrayList<>();
      StringBuilder ctsv = new StringBuilder("variant\tlatency_ms\tfraction_at_or_below\n");
      for (String variant : List.of("off", "on")) {
        LatencyHistogram merged = new LatencyHistogram();
        for (Map<String, Object> r : s.runs()) {
          LatencyHistogram h = LoadReport.histogram(r);
          if (variant.equals(r.get("variant")) && h != null) {
            merged.add(h);
          }
        }
        List<double[]> points = merged.cdf(400);
        for (double[] p : points) {
          ctsv.append(variant)
              .append('\t')
              .append(LoadReport.fmt(p[0], 3))
              .append('\t')
              .append(String.format(Locale.ROOT, "%.6f", p[1]))
              .append('\n');
        }
        cdf.add(new Svg.Series(variant, variant.equals("off") ? 0 : 1, points));
      }
      Files.writeString(
          dir.resolve(base + "-latency-cdf.svg"),
          Svg.lineChart(
              "Request latency CDF, all runs of a variant (" + s.role() + ")",
              "latency ms (log scale)",
              "fraction of requests at or below",
              cdf,
              true,
              List.of("off", "agent (dashed)")),
          StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(base + "-latency-cdf.tsv"), ctsv, StandardCharsets.UTF_8);
      lines.add("### " + s.name() + " (" + s.role() + ")");
      lines.add("");
      lines.add(
          "![CPU time series of "
              + base
              + "]("
              + reportName
              + "/"
              + base
              + "-cpu-timeline.svg) ([data]("
              + reportName
              + "/"
              + base
              + "-cpu-timeline.tsv))");
      lines.add("");
      lines.add(
          "![Latency CDF of "
              + base
              + "]("
              + reportName
              + "/"
              + base
              + "-latency-cdf.svg) ([data]("
              + reportName
              + "/"
              + base
              + "-latency-cdf.tsv))"
              + (cdf.stream().allMatch(c -> c.points().isEmpty())
                  ? " (no k6 raw output in this set: the CDF needs it, 18 §7.2)"
                  : ""));
      lines.add("");
    }
    return lines;
  }

  // ---- markdown --------------------------------------------------------------------------------

  static String markdown(
      Map<String, Object> model,
      List<SetData> sets,
      List<String> graphs,
      String command,
      List<String> setDirs,
      Path out,
      Path baseline) {
    StringBuilder md = new StringBuilder();
    String name = String.valueOf(model.get("name"));
    md.append("# Agent overhead benchmark ")
        .append(name)
        .append(": ")
        .append(model.get("verdict"))
        .append("\n\n");
    md.append(
        "Generated by `test/bench` (`bench.sh publish`) in the report template of 18 §7.6."
            + " Requirements N-01..N-04 and N-26..N-30 (01 §7.2) under the protocol of 01 §7.3;"
            + " regression rule 18 §7.7.\n\n");
    md.append("- Overall verdict: **")
        .append(model.get("verdict"))
        .append("** (")
        .append(model.get("measuredRequirements"))
        .append(" of 9 requirements have numbers; PASS needs all nine PASS)\n");
    md.append("- Regression (18 §7.7): ")
        .append(Json.at(model, "regression", "verdict"))
        .append(
            Json.at(model, "regression", "baseline") == null
                ? ""
                : " against " + Json.at(model, "regression", "baseline"))
        .append(' ')
        .append(Json.array(Json.at(model, "regression", "reasons")))
        .append('\n');
    List<Object> envProblems = Json.array(model.get("environmentProblems"));
    if (!envProblems.isEmpty()) {
      md.append("- Environment problems (every verdict INVALID): ")
          .append(envProblems)
          .append('\n');
    }
    md.append(
        "- Approval: pending. A maintainer approves the report in review by setting"
            + " `\"approved\": true` in the summary comment at the end; 18 §7.7 compares new"
            + " reports with the last approved one (`--baseline`).\n\n");

    // 1. Environment
    md.append("## 1. Environment (18 §7.1)\n\n");
    Map<String, Object> env = null;
    for (SetData s : sets) {
      if (s.envRecord() != null) {
        env = s.envRecord();
        break;
      }
    }
    md.append(environmentTable(env, sets.get(0).set()));

    // 2. Parameters
    md.append("\n## 2. Run parameters\n\n");
    for (SetData s : sets) {
      md.append(parameters(s));
    }

    // 3. Raw per round
    md.append("## 3. Raw results per run\n\n");
    for (SetData s : sets) {
      md.append(rawTable(s));
    }

    // 4. Statistics
    md.append("## 4. Statistics\n\n");
    for (SetData s : sets) {
      if ("load".equals(s.set().get("kind"))) {
        md.append(statistics(s));
      }
    }

    // 5. Memory, threads, startup
    md.append("## 5. Memory, threads and startup\n\n");
    for (SetData s : sets) {
      md.append(memoryAndStartup(s));
    }

    // 6. Verdicts
    md.append("## 6. Verdicts\n\n");
    md.append(
        "| requirement | test | criterion | numbers | outcome | verdict | sets | why not decided /"
            + " notes |\n|---|---|---|---|---|---|---|---|\n");
    @SuppressWarnings("unchecked")
    Map<String, Requirement> reqs = (Map<String, Requirement>) model.get("requirementObjects");
    for (Requirement r : reqs.values()) {
      List<String> numbers = r.checks.stream().map(Check::text).toList();
      List<String> why = new ArrayList<>(r.invalid);
      why.addAll(r.notes);
      md.append("| ")
          .append(r.id)
          .append(" | ")
          .append(r.test)
          .append(" | ")
          .append(cell(r.criterion))
          .append(" | ")
          .append(numbers.isEmpty() ? "-" : cell(String.join("; ", numbers)))
          .append(" | ")
          .append(r.outcome())
          .append(" | **")
          .append(r.verdict())
          .append("** | ")
          .append(r.sources.isEmpty() ? "-" : cell(String.join(", ", r.sources)))
          .append(" | ")
          .append(why.isEmpty() ? "-" : cell(String.join("; ", why)))
          .append(" |\n");
    }
    md.append('\n');

    // 7. Graphs
    md.append("## 7. Graphs\n\n");
    if (graphs.isEmpty()) {
      md.append("No load set in this report.\n\n");
    } else {
      graphs.forEach(l -> md.append(l).append('\n'));
    }

    // 8. Reproduction
    md.append("## 8. Reproduction\n\n```bash\n");
    md.append("# 18 §7.1 environment record\n");
    md.append(
            env != null && env.get("command") != null
                ? String.valueOf(env.get("command"))
                : "test/bench/bench-env.sh --machine-profile <id> --out <dir>/env-record.json")
        .append('\n');
    md.append("# measurement\n");
    if (!command.isBlank()) {
      md.append(command).append('\n');
    }
    for (SetData s : sets) {
      Object harness = s.set().get("harnessCommand");
      md.append("#   ")
          .append(s.name())
          .append(": ")
          .append(
              harness == null ? "(harness command not recorded)" : "bench.sh --no-build " + harness)
          .append('\n');
    }
    md.append("# this report from the raw sets (no measurement)\n");
    md.append("test/bench/bench.sh --no-build publish --sets ")
        .append(String.join(",", setDirs))
        .append(" --out ")
        .append(out)
        .append(baseline == null ? "" : " --baseline " + baseline)
        .append(" --overwrite\n```\n\n");
    md.append("Inputs (SHA-256):\n\n");
    Set<String> seen = new LinkedHashSet<>();
    for (SetData s : sets) {
      for (Map.Entry<String, Object> e : Json.object(s.set().get("inputs")).entrySet()) {
        String line =
            "- `"
                + e.getKey()
                + "` "
                + Json.at(e.getValue(), "sha256")
                + " ("
                + LoadReport.fmt(Json.number(e.getValue(), "bytes"), 0)
                + " bytes)";
        if (seen.add(line)) {
          md.append(line).append('\n');
        }
      }
    }
    md.append(
        "\nRaw files stay in the set directories (`runs/<NN>-<variant>/`: run.json, k6.json,"
            + " k6-raw.json.gz, latency-hdr.json, machine.json, jcmd outputs, gc.log). Copy what"
            + " the report needs into `docs/bench/` following docs/poc/agent-overhead.md 9.4"
            + " (no file over 1 MB, no user names in paths).\n\n");
    md.append(Regression.summaryComment(Json.object(model.get("summary"))));
    return md.toString();
  }

  private static String environmentTable(Map<String, Object> env, Map<String, Object> firstSet) {
    StringBuilder md = new StringBuilder("| item | value |\n|---|---|\n");
    if (env == null) {
      md.append("| record | **none**: no bench-env.sh record (18 §7.1); harness facts below |\n");
      for (Map.Entry<String, Object> e : Json.object(firstSet.get("environment")).entrySet()) {
        md.append("| ")
            .append(e.getKey())
            .append(" | ")
            .append(cell(String.valueOf(e.getValue())))
            .append(" |\n");
      }
      return md.toString();
    }
    row(md, "machine profile", env.get("machineProfile"));
    row(md, "recorded at", env.get("recordedAt"));
    row(md, "app machine CPU", Json.at(env, "app", "cpuModel"));
    row(
        md,
        "logical CPUs / physical cores",
        Json.at(env, "app", "logicalCpus") + " / " + Json.at(env, "app", "physicalCores"));
    row(md, "memory MB", Json.at(env, "app", "memoryMb"));
    row(md, "CPU governor", Json.at(env, "app", "governor"));
    row(md, "transparent huge pages", Json.at(env, "app", "thp"));
    row(md, "kernel", Json.at(env, "app", "kernel"));
    row(md, "OS", Json.at(env, "app", "os"));
    row(md, "OS image hash", Json.at(env, "app", "osImageSha256"));
    row(md, "virtualization", Json.at(env, "app", "virtualization"));
    row(md, "JDK", Json.at(env, "jdk", "version"));
    row(
        md,
        "agent jar",
        Json.at(env, "agentJar", "name")
            + " "
            + Json.at(env, "agentJar", "version")
            + ", SHA-256 "
            + Json.at(env, "agentJar", "sha256"));
    row(md, "argos-agent.yml", Json.at(env, "agentYml"));
    row(
        md,
        "sample app commit",
        Json.at(env, "repository", "commit")
            + " (uncommitted entries: "
            + Json.at(env, "repository", "uncommittedEntries")
            + ")");
    row(md, "k6", Json.at(env, "k6"));
    row(md, "load machine", Json.at(env, "loader"));
    row(md, "ping RTT app -> load machine", Json.at(env, "pingRtt"));
    row(md, "record valid", env.get("valid"));
    row(md, "record problems", env.get("problems"));
    row(md, "overrides", env.get("overrides"));
    return md.toString();
  }

  private static void row(StringBuilder md, String item, Object value) {
    md.append("| ")
        .append(item)
        .append(" | ")
        .append(cell(value == null ? "n/a" : String.valueOf(value)))
        .append(" |\n");
  }

  private static String parameters(SetData s) {
    Map<String, Object> p = Json.object(s.set().get("parameters"));
    StringBuilder md = new StringBuilder();
    md.append("### ").append(s.name()).append(" (").append(s.role()).append(")\n\n");
    md.append("| parameter | value |\n|---|---|\n");
    row(md, "kind", s.set().get("kind"));
    row(md, "label", s.set().get("label"));
    row(md, "started / finished", s.set().get("startedAt") + " / " + s.set().get("finishedAt"));
    if ("load".equals(s.set().get("kind"))) {
      row(md, "mode", p.get("mode"));
      row(md, "app", p.get("app"));
      row(md, "rounds, order", p.get("rounds") + ", " + p.get("order"));
      row(
          md,
          "warm-up / window / rate",
          p.get("warmupSeconds")
              + " s / "
              + p.get("durationSeconds")
              + " s / "
              + p.get("rate")
              + "/s");
      row(md, "k6 VUs (pre-allocated / max)", p.get("preAllocatedVUs") + " / " + p.get("maxVUs"));
      row(md, "JVM options", p.get("jvmOptions") + " + " + p.get("jvmOptionsAddedByHarness"));
      row(md, "app environment", p.get("appEnv"));
      row(md, "exporter", p.get("exporter") + ": " + p.get("exporterDescription"));
      row(md, "profile", p.get("profile"));
      row(md, "agent settings (-Dargos.*)", p.get("agentConfig"));
      row(md, "k6", p.get("k6") + (p.get("k6Ssh") == null ? "" : " on " + p.get("k6Ssh")));
      row(md, "k6 raw output", p.get("k6RawOutput"));
      row(
          md,
          "bootstrap",
          p.get("bootstrapReplicates") + " replicates, seed " + p.get("bootstrapSeed"));
      if ("container".equals(p.get("mode"))) {
        row(md, "container limits", "--cpus " + p.get("cpus") + " --memory " + p.get("memory"));
      }
    } else {
      row(md, "apps", p.get("apps"));
      row(md, "starts per variant", p.get("runsPerVariant"));
      row(md, "CDS modes", p.get("cds"));
      row(md, "order", p.get("order"));
      row(md, "JVM options", p.get("jvmOptions"));
      row(md, "agent settings (-Dargos.*)", p.get("agentConfig"));
      row(md, "definition", p.get("definition"));
    }
    List<String> deviations = Protocol.deviations(s.set());
    row(md, "protocol deviations (01 §7.3)", deviations.isEmpty() ? "none" : deviations);
    List<String> notes = Protocol.notes(s.set());
    if (!notes.isEmpty()) {
      row(md, "notes", notes);
    }
    row(md, "harness verdict", s.result().get("verdict") + " " + s.result().get("reasons"));
    md.append('\n');
    return md.toString();
  }

  private static String rawTable(SetData s) {
    StringBuilder md = new StringBuilder("### " + s.name() + " (" + s.role() + ")\n\n");
    if (!"load".equals(s.set().get("kind"))) {
      md.append("Per-start values are in part 5.\n\n");
      return md.toString();
    }
    md.append(
        "| # | round | variant | app CPU s | requests | errors (unexpected) | 5xx (5% intended) |"
            + " dropped | p50 ms | p95 ms | p99 ms | p99.9 ms | latency source | k6 gate |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Object r : Json.array(s.result().get("runs"))) {
      double requests = Json.number(r, "requests");
      md.append("| ")
          .append(Json.at(r, "index"))
          .append(" | ")
          .append(Json.at(r, "round"))
          .append(" | ")
          .append(Json.at(r, "variant"))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "appCpuSeconds"), 2))
          .append(" | ")
          .append(LoadReport.fmt(requests, 0))
          .append(" | ")
          .append(LoadReport.fmt(requests * Json.number(r, "unexpectedRate"), 0))
          .append(" | ")
          .append(LoadReport.fmt(requests * Json.number(r, "http5xxRate"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "dropped"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "p50"), 3))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "p95"), 3))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "p99"), 3))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(r, "p999"), 3))
          .append(" | ")
          .append(Json.at(r, "latencySource"))
          .append(" | ")
          .append(Json.at(r, "gate"))
          .append(" |\n");
    }
    double cv = Json.number(s.result(), "validity", "offCpuCv");
    md.append("\nOff-run CPU coefficient of variation: ")
        .append(
            Double.isNaN(cv) ? "n/a (fewer than two off runs)" : LoadReport.fmt(cv * 100, 2) + "%")
        .append(" (INVALID above 2%, 01 §7.3 item 4).\n\n");
    return md.toString();
  }

  private static String statistics(SetData s) {
    StringBuilder md = new StringBuilder("### " + s.name() + " (" + s.role() + ")\n\n");
    for (Map.Entry<String, Object> e : Json.object(s.result().get("comparisons")).entrySet()) {
      String v = e.getKey();
      Object c = e.getValue();
      md.append("**")
          .append(v)
          .append(" vs off** (latency source: ")
          .append(Json.at(c, "latency", "source"))
          .append(")\n\n");
      md.append(
          "| measure | off | "
              + v
              + " | difference | 95% CI | note |\n|---|---|---|---|---|---|\n");
      md.append("| app CPU s (mean of runs) | ")
          .append(LoadReport.fmt(Json.number(c, "cpu", "offMean"), 2))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(c, "cpu", v + "Mean"), 2))
          .append(" | ")
          .append(LoadReport.pct(Json.number(c, "cpu", "relative")))
          .append(" | ")
          .append(pctRange(Json.array(Json.at(c, "cpu", "pairRelativeCi95"))))
          .append(" (t over round pairs) | relative = (on - off) / off |\n");
      md.append("| app CPU ms per request | ")
          .append(LoadReport.fmt(Json.number(c, "cpu", "offCpuMsPerRequestMean"), 4))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(c, "cpu", v + "CpuMsPerRequestMean"), 4))
          .append(" | ")
          .append(LoadReport.pct(Json.number(c, "cpu", "relativePerRequest")))
          .append(" | | |\n");
      md.append("| 1-minute peak relative CPU | | | ")
          .append(LoadReport.pct(Json.number(c, "peak", "value")))
          .append(" | | windows of 60 s every 10 s, averaged over the rounds |\n");
      md.append("| agent thread CPU | | | ")
          .append(LoadReport.fmt(Json.number(c, "agentThreadCpu", "value"), 4))
          .append("% of host | | jcmd Thread.print cpu= of argos-* threads |\n");
      for (String q : LoadReport.QUANTILES.keySet()) {
        Object l = Json.at(c, "latency", q);
        List<Object> boot = Json.array(Json.at(l, "diffBootstrapCi95"));
        md.append("| ")
            .append(q)
            .append(" ms (mean of runs) | ")
            .append(LoadReport.fmt(Json.number(l, "offMean"), 3))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(l, v + "Mean"), 3))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(l, "diffMean"), 3))
            .append(" | ")
            .append(boot.size() == 2 ? range(boot) + " (bootstrap); " : "")
            .append(range(Json.array(Json.at(l, "diffCi95"))))
            .append(" (t) | ")
            .append(Boolean.TRUE.equals(Json.at(l, "noDifference")) ? "no difference" : "")
            .append(" |\n");
      }
      md.append("\nPer-route p99 (ms; informational, WO-02 H-5):\n\n| route | off | ")
          .append(v)
          .append(" | difference | 95% CI (t) |\n|---|---|---|---|---|\n");
      for (Map.Entry<String, Object> r : Json.object(Json.at(c, "routesP99")).entrySet()) {
        md.append("| ")
            .append(r.getKey())
            .append(" | ")
            .append(LoadReport.fmt(Json.number(r.getValue(), "offP99Mean"), 3))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(r.getValue(), v + "P99Mean"), 3))
            .append(" | ")
            .append(LoadReport.fmt(Json.number(r.getValue(), "diffMean"), 3))
            .append(" | ")
            .append(range(Json.array(Json.at(r.getValue(), "diffCi95"))))
            .append(" |\n");
      }
      md.append('\n');
    }
    return md.toString();
  }

  private static String pctRange(List<Object> pair) {
    if (pair.size() != 2) {
      return "n/a";
    }
    return LoadReport.pct(Json.number(pair.get(0)))
        + " .. "
        + LoadReport.pct(Json.number(pair.get(1)));
  }

  private static String memoryAndStartup(SetData s) {
    StringBuilder md = new StringBuilder("### " + s.name() + " (" + s.role() + ")\n\n");
    if ("load".equals(s.set().get("kind"))) {
      Object on = Json.at(s.result(), "comparisons", "on");
      md.append("| measure (on vs off) | value | limit | source |\n|---|---|---|---|\n");
      memoryRow(
          md,
          "agent heap (io.argos.agent.*)",
          on,
          "agentHeapMb",
          "jcmd GC.class_histogram after GC.run x2");
      memoryRow(
          md,
          "metaspace increase",
          on,
          "metaspaceIncreaseMb",
          String.valueOf(Json.at(on, "memory", "metaspaceSource")));
      memoryRow(
          md,
          "NMT Internal+Other increase",
          on,
          "nmtInternalOtherIncreaseMb",
          "VM.native_memory summary");
      memoryRow(
          md,
          "direct memory (NMT Other increase)",
          on,
          "directMemoryIncreaseMb",
          "VM.native_memory summary");
      memoryRow(
          md,
          "thread increase",
          on,
          "threadIncrease",
          String.valueOf(Json.at(on, "memory", "threadSource")));
      memoryRow(md, "agent threads", on, "agentThreads", "argos-* threads of jcmd Thread.print");
      md.append("| live heap increase | ")
          .append(LoadReport.fmt(Json.number(on, "memory", "liveHeapIncreaseMb"), 2))
          .append(" MB | - | class histogram total (informational) |\n");
      md.append("| loaded class increase | ")
          .append(LoadReport.fmt(Json.number(on, "memory", "loadedClassIncrease"), 0))
          .append(" | - | NMT |\n");
      md.append("| allocated KB per request increase | ")
          .append(LoadReport.fmt(Json.number(on, "gc", "allocatedKbPerRequestIncrease"), 2))
          .append(" | - | gc.log (informational) |\n");
      md.append("| premain median (load runs) | ")
          .append(LoadReport.fmt(Json.number(on, "startup", "premainMedianMs"), 1))
          .append(" ms | 500 ms | agent log |\n\n");
      return md.toString();
    }
    md.append(
        "| app | CDS | n | port off median ms | port on median ms | increase ms | increase % |"
            + " ready off / on ms | premain median ms | +2 s | +10% (heavy) | premain |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Object row : Json.array(s.result().get("rows"))) {
      String v = String.valueOf(Json.at(row, "variant"));
      md.append("| ")
          .append(Json.at(row, "app"))
          .append(" | ")
          .append(Json.at(row, "cds"))
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
          .append(" / ")
          .append(LoadReport.fmt(Json.number(row, v + "ReadyMedianMs"), 0))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(row, "premainMedianMs"), 1))
          .append(" | ")
          .append(orDash(Json.at(row, "plus2sVerdict")))
          .append(" | ")
          .append(orDash(Json.at(row, "plus10PercentVerdict")))
          .append(" | ")
          .append(orDash(Json.at(row, "premainVerdict")))
          .append(" |\n");
    }
    md.append("\nPort-open times per start (ms, C-49):\n\n");
    for (Object row : Json.array(s.result().get("rows"))) {
      String v = String.valueOf(Json.at(row, "variant"));
      md.append("- ")
          .append(Json.at(row, "app"))
          .append(" cds=")
          .append(Json.at(row, "cds"))
          .append(": off ")
          .append(values(Json.at(row, "offPortMs")))
          .append(", ")
          .append(v)
          .append(' ')
          .append(values(Json.at(row, v + "PortMs")))
          .append('\n');
    }
    md.append('\n');
    return md.toString();
  }

  private static void memoryRow(
      StringBuilder md, String what, Object on, String key, String source) {
    md.append("| ")
        .append(what)
        .append(" | ")
        .append(
            format(
                Json.number(on, "memory", key, "value"),
                String.valueOf(Json.at(on, "memory", key, "unit"))))
        .append(" | ")
        .append(
            format(
                Json.number(on, "memory", key, "limit"),
                String.valueOf(Json.at(on, "memory", key, "unit"))))
        .append(" | ")
        .append(source)
        .append(" |\n");
  }

  private static String values(Object list) {
    List<String> out = new ArrayList<>();
    if (list instanceof double[] array) {
      for (double d : array) {
        out.add(LoadReport.fmt(d, 0));
      }
    } else {
      for (Object o : Json.array(list)) {
        out.add(LoadReport.fmt(Json.number(o), 0));
      }
    }
    return "[" + String.join(", ", out) + "]";
  }

  private static String orDash(Object o) {
    return o == null ? "-" : String.valueOf(o);
  }

  /** A table cell: no line breaks, pipes escaped. */
  static String cell(String text) {
    return text.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
  }
}
