/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Analysis of a load set (01 §7.3 items 4-7, 18 §7.4, ADR-0004 C-46): validity (k6 gate per run,
 * off-run CPU CV, the agent's exporter), relative CPU (mean and 1-minute peak), agent thread CPU,
 * latency differences with 95% confidence intervals (bootstrap over the requests of the
 * re-aggregated HDR histograms, 18 §7.2, and Student t over the paired rounds), memory and threads.
 * A required number that is missing makes the set INVALID: no PASS without numbers (18 §7.6).
 * Writes {@code result.json} and {@code report.md}; the exit code is 0 PASS, 3 FAIL, 4 INVALID.
 */
final class LoadReport {
  private LoadReport() {}

  /** 01 §7.3 item 4: off-run CPU coefficient of variation above 2% makes the set INVALID. */
  static final double CV_LIMIT = 0.02;

  static final double MB = 1024.0;

  /** Re-aggregated latencies of a run's measured window (written by {@link LoadSet}). */
  static final String HDR_FILE = "latency-hdr.json";

  /** In-memory keys of a run map that hold its histograms (never written to JSON). */
  static final String HDR_ALL = "_hdrAll";

  static final String HDR_ROUTES = "_hdrRoutes";

  /** Reported quantiles (18 §7.2: p50, p95, p99, p99.9). */
  static final Map<String, Double> QUANTILES = quantiles();

  private static Map<String, Double> quantiles() {
    Map<String, Double> q = new LinkedHashMap<>();
    q.put("p50", 0.50);
    q.put("p95", 0.95);
    q.put("p99", 0.99);
    q.put("p99.9", 0.999);
    return Collections.unmodifiableMap(q);
  }

  static int write(Path setDir) throws IOException {
    Map<String, Object> set = Json.object(FileTools.readJson(setDir.resolve("set.json")));
    List<Map<String, Object>> runs = readRuns(setDir);
    addGcWindows(setDir, runs);
    Map<String, Object> result = analyze(set, runs, seedOf(set));
    FileTools.writeJson(setDir.resolve("result.json"), result);
    Files.writeString(
        setDir.resolve("report.md"), markdown(set, runs, result), StandardCharsets.UTF_8);
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

  /**
   * Adds the GC activity of each run's measured window ({@code gc}) from its raw files: gc.log and
   * the thread dumps taken just before and after the window (JVM uptime).
   */
  static void addGcWindows(Path setDir, List<Map<String, Object>> runs) {
    for (Map<String, Object> run : runs) {
      Path dir =
          setDir
              .resolve("runs")
              .resolve(
                  String.format(
                      Locale.ROOT,
                      "%02d-%s",
                      (long) Json.number(run.get("index")),
                      run.get("variant")));
      String gc = FileTools.readLenient(dir.resolve("gc.log"));
      double from =
          GcLog.uptimeOfThreadDump(FileTools.readLenient(dir.resolve("threads-start.txt")));
      double to = GcLog.uptimeOfThreadDump(FileTools.readLenient(dir.resolve("threads-end.txt")));
      if (gc.isEmpty() || Double.isNaN(from) || Double.isNaN(to)) {
        continue;
      }
      Map<String, Object> window = GcLog.window(gc, from, to);
      double requests = Json.number(run, "measure", "requests");
      window.put(
          "allocatedKbPerRequest",
          requests > 0
              ? Json.number(window.get("allocatedMbBetweenPauses")) * 1024 / requests
              : Double.NaN);
      run.put("gc", window);
    }
  }

  /**
   * The runs of a set ({@code runs/<NN>-<variant>/run.json}, discarded attempts excluded), each
   * with its re-aggregated histograms attached in memory ({@link #HDR_ALL}, {@link #HDR_ROUTES})
   * when its {@link #HDR_FILE} exists.
   */
  static List<Map<String, Object>> readRuns(Path setDir) throws IOException {
    Path runsDir = setDir.resolve("runs");
    List<Map<String, Object>> runs = new ArrayList<>();
    if (!Files.isDirectory(runsDir)) {
      return runs;
    }
    try (Stream<Path> dirs = Files.list(runsDir)) {
      for (Path dir : dirs.sorted().toList()) {
        Path file = dir.resolve("run.json");
        if (Files.isRegularFile(file)) {
          Map<String, Object> run = Json.object(FileTools.readJson(file));
          Path hdr = dir.resolve(HDR_FILE);
          if (Files.isRegularFile(hdr)) {
            Object json = FileTools.readJson(hdr);
            run.put(HDR_ALL, LatencyHistogram.fromJson(Json.at(json, "all")));
            Map<String, LatencyHistogram> routes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : Json.object(Json.at(json, "routes")).entrySet()) {
              routes.put(e.getKey(), LatencyHistogram.fromJson(e.getValue()));
            }
            run.put(HDR_ROUTES, routes);
          }
          runs.add(run);
        }
      }
    }
    runs.sort(Comparator.comparingDouble(r -> Json.number(r.get("index"))));
    return runs;
  }

  /** Bootstrap seed of a set (set.json), 42 by default. */
  static long seedOf(Map<String, Object> set) {
    double seed = Json.number(set, "parameters", "bootstrapSeed");
    return Double.isNaN(seed) ? Bootstrap.DEFAULT_SEED : (long) seed;
  }

  /** The re-aggregated histogram of a run, or null (no raw output). */
  static LatencyHistogram histogram(Map<String, Object> run) {
    return run.get(HDR_ALL) instanceof LatencyHistogram h && h.count() > 0 ? h : null;
  }

  /** The route histogram of a run, or null. */
  static LatencyHistogram routeHistogram(Map<String, Object> run, String route) {
    if (run.get(HDR_ROUTES) instanceof Map<?, ?> routes
        && routes.get(route) instanceof LatencyHistogram h
        && h.count() > 0) {
      return h;
    }
    return null;
  }

  /**
   * Quantile {@code q} (key of {@link #QUANTILES}) of a run's window in ms: from the re-aggregated
   * k6 raw output when the run has it (18 §7.2), else k6's summary (WO-02 sets).
   */
  static double latency(Map<String, Object> run, String q) {
    LatencyHistogram h = histogram(run);
    return h != null
        ? h.quantileMillis(QUANTILES.get(q))
        : Json.number(run, "measure", "latencyMs", q);
  }

  private static double[] latencies(List<Map<String, Object>> runs, String variant, String q) {
    return runs.stream()
        .filter(r -> variant.equals(r.get("variant")))
        .mapToDouble(r -> latency(r, q))
        .toArray();
  }

  private static List<LatencyHistogram> histograms(List<Map<String, Object>> runs, String variant) {
    List<LatencyHistogram> out = new ArrayList<>();
    for (Map<String, Object> r : of(runs, variant)) {
      LatencyHistogram h = histogram(r);
      if (h == null) {
        return List.of();
      }
      out.add(h);
    }
    return out;
  }

  private static double[] values(List<Map<String, Object>> runs, String variant, String... path) {
    return runs.stream()
        .filter(r -> variant.equals(r.get("variant")))
        .mapToDouble(r -> Json.number(r, path))
        .toArray();
  }

  private static List<Map<String, Object>> of(List<Map<String, Object>> runs, String variant) {
    return runs.stream().filter(r -> variant.equals(r.get("variant"))).toList();
  }

  static Map<String, Object> analyze(Map<String, Object> set, List<Map<String, Object>> runs) {
    return analyze(set, runs, seedOf(set));
  }

  static Map<String, Object> analyze(
      Map<String, Object> set, List<Map<String, Object>> runs, long seed) {
    Map<String, Object> parameters = Json.object(set.get("parameters"));
    String mode = String.valueOf(parameters.get("mode"));
    boolean container = mode.equals("container");
    boolean vt = Json.object(parameters.get("appEnv")).containsKey("SAMPLE_VT");
    double logicalCpus =
        container
            ? Json.number(parameters.get("cpus"))
            : Json.number(set, "environment", "logicalCpus");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", "load");
    result.put("label", set.get("label"));
    result.put("mode", mode);
    result.put("virtualThreads", vt);
    result.put(
        "criteria",
        container
            ? "N-29 (container), N-03"
            : vt
                ? "N-28 (virtual threads): N-01..N-03"
                : "N-01, N-03, N-26 (Gate 0: N-01 mean, N-03)");
    List<String> reasons = new ArrayList<>();
    boolean invalid = false;
    boolean fail = false;

    // Per-run table.
    List<Object> table = new ArrayList<>();
    for (Map<String, Object> r : runs) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", r.get("index"));
      row.put("round", r.get("round"));
      row.put("variant", r.get("variant"));
      row.put("appCpuSeconds", Json.number(r, "measure", "appCpuSeconds"));
      row.put("wallSeconds", Json.number(r, "measure", "wallSeconds"));
      row.put("requests", Json.number(r, "measure", "requests"));
      row.put("dropped", Json.number(r, "measure", "dropped"));
      row.put("unexpectedRate", Json.number(r, "measure", "unexpectedRate"));
      row.put("http5xxRate", Json.number(r, "measure", "http5xxRate"));
      row.put("p50", latency(r, "p50"));
      row.put("p95", latency(r, "p95"));
      row.put("p99", latency(r, "p99"));
      row.put("p999", latency(r, "p99.9"));
      row.put("latencySource", histogram(r) != null ? "hdr" : "k6 summary");
      LatencyHistogram h = histogram(r);
      row.put("hdrRequests", h == null ? null : h.count());
      row.put("hostCpuPercentMean", Json.number(r, "measure", "hostCpuPercentMean"));
      row.put("loaderCpuPercentMean", Json.number(r, "measure", "loaderMachineCpuPercentMean"));
      row.put("cpuBeforePercent", Json.number(r, "before", "cpuPercent5s"));
      row.put("backgroundCpuPercent", Json.number(r, "measure", "backgroundCpuPercentMean"));
      row.put("attempt", r.get("attempt"));
      row.put("discardedAttempts", Json.array(r.get("discardedAttempts")).size());
      row.put("gate", Json.at(r, "measure", "gate", "verdict"));
      table.add(row);
    }
    result.put("runs", table);

    // Validity: C-46 gate per run and 01 §7.3 item 4 (off CPU CV).
    Map<String, Object> validity = new LinkedHashMap<>();
    double[] offCpu = values(runs, "off", "measure", "appCpuSeconds");
    double cv = Stats.cv(offCpu);
    validity.put("offCpuSeconds", offCpu);
    validity.put("offCpuCv", cv);
    validity.put("offCpuCvLimit", CV_LIMIT);
    boolean cvOk = offCpu.length >= 2 && cv <= CV_LIMIT;
    validity.put("offCpuCvOk", cvOk);
    if (!cvOk) {
      invalid = true;
      reasons.add(
          String.format(Locale.ROOT, "off-run CPU CV %.2f%% > 2%% (01 7.3 item 4)", cv * 100));
    }
    List<String> gateProblems = new ArrayList<>();
    for (Map<String, Object> r : runs) {
      Object verdict = Json.at(r, "measure", "gate", "verdict");
      if ("PASS".equals(verdict)) {
        continue;
      }
      String where = "run " + r.get("index") + " (" + r.get("variant") + ")";
      gateProblems.add(where + ": " + verdict);
      if ("INVALID".equals(verdict) || "off".equals(r.get("variant")) || verdict == null) {
        invalid = true;
        reasons.add(where + ": k6 gate " + verdict + " (C-46)");
      } else {
        fail = true;
        reasons.add(where + ": k6 gate FAIL with the agent (load not sustained, C-46)");
      }
    }
    validity.put("gateProblems", gateProblems);
    // The agent must have run with the exporter the set asked for (its start banner): an agent
    // that fell back to another exporter measured something else (18 §5.4, §7.3 --exporter).
    List<String> exporterProblems = new ArrayList<>();
    for (Map<String, Object> r : runs) {
      Object expected = r.get("expectedExporter");
      if (expected != null && !expected.equals(r.get("agentExporter"))) {
        exporterProblems.add(
            "run "
                + r.get("index")
                + " ("
                + r.get("variant")
                + "): agent banner exporter="
                + r.get("agentExporter")
                + ", expected "
                + expected);
      }
    }
    validity.put("exporterProblems", exporterProblems);
    if (!exporterProblems.isEmpty()) {
      invalid = true;
      reasons.addAll(exporterProblems);
    }
    validity.put("bootstrapSeed", seed);
    result.put("validity", validity);

    // Comparisons with off.
    Set<String> variants = new LinkedHashSet<>();
    runs.forEach(r -> variants.add(String.valueOf(r.get("variant"))));
    variants.remove("off");
    Map<String, Object> comparisons = new LinkedHashMap<>();
    for (String variant : variants) {
      Map<String, Object> c = compare(runs, variant, container, logicalCpus, seed);
      comparisons.put(variant, c);
      if (variant.equals("on")) {
        for (Object failed : Json.array(c.get("failed"))) {
          fail = true;
          reasons.add(String.valueOf(failed));
        }
        // No PASS without numbers (18 §7.6): a required number that is missing invalidates.
        for (Object missing : Json.array(c.get("noData"))) {
          invalid = true;
          reasons.add("no data: " + missing);
        }
      }
    }
    if (!variants.contains("on")) {
      invalid = true;
      reasons.add("no on runs");
    }
    result.put("comparisons", comparisons);
    result.put("verdict", invalid ? "INVALID" : fail ? "FAIL" : "PASS");
    result.put("reasons", reasons);
    return result;
  }

  private static Map<String, Object> verdict(double value, double limit, String unit) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("value", value);
    v.put("limit", limit);
    v.put("unit", unit);
    v.put("verdict", Double.isNaN(value) ? "NO DATA" : value <= limit ? "PASS" : "FAIL");
    return v;
  }

  static Map<String, Object> compare(
      List<Map<String, Object>> runs, String variant, boolean container, double logicalCpus) {
    return compare(runs, variant, container, logicalCpus, Bootstrap.DEFAULT_SEED);
  }

  static Map<String, Object> compare(
      List<Map<String, Object>> runs,
      String variant,
      boolean container,
      double logicalCpus,
      long seed) {
    Map<String, Object> c = new LinkedHashMap<>();
    List<String> failed = new ArrayList<>();
    List<String> noData = new ArrayList<>();
    String tag = variant.equals("on") ? "" : " [" + variant + "]";

    // CPU (01 §7.3 item 5): relative = (on - off) / off on process CPU seconds of the window.
    double[] off = values(runs, "off", "measure", "appCpuSeconds");
    double[] on = values(runs, variant, "measure", "appCpuSeconds");
    double offMean = Stats.mean(off);
    double onMean = Stats.mean(on);
    double relative = onMean / offMean - 1;
    double[] pairs = new double[Math.min(on.length, off.length)];
    for (int i = 0; i < pairs.length; i++) {
      pairs[i] = on[i] / off[i] - 1;
    }
    Stats.Interval pairCi = Stats.meanInterval(pairs);
    Map<String, Object> cpu = new LinkedHashMap<>();
    cpu.put("offCpuSeconds", off);
    cpu.put(variant + "CpuSeconds", on);
    cpu.put("offMean", offMean);
    cpu.put(variant + "Mean", onMean);
    cpu.put("relative", relative);
    cpu.put("pairRelatives", pairs);
    cpu.put("pairRelativeCi95", List.of(pairCi.low(), pairCi.high()));
    double limit = container ? 0.08 : 0.05;
    Map<String, Object> cpuVerdict = verdict(relative, limit, "relative");
    cpu.put("check", cpuVerdict);
    if ("FAIL".equals(cpuVerdict.get("verdict"))) {
      failed.add(
          String.format(
              Locale.ROOT, "relative CPU %+.2f%% > %.0f%%%s", relative * 100, limit * 100, tag));
    }
    noData(noData, cpuVerdict, "relative CPU (N-01)");
    double[] offPerRequest = values(runs, "off", "measure", "appCpuMsPerRequest");
    double[] onPerRequest = values(runs, variant, "measure", "appCpuMsPerRequest");
    cpu.put("offCpuMsPerRequestMean", Stats.mean(offPerRequest));
    cpu.put(variant + "CpuMsPerRequestMean", Stats.mean(onPerRequest));
    cpu.put("relativePerRequest", Stats.mean(onPerRequest) / Stats.mean(offPerRequest) - 1);
    c.put("cpu", cpu);

    // Peak (18 §7.4): 1-minute windows started every 10 s, aligned by time in the window and
    // averaged over the rounds; peak relative = max over windows of on / off - 1.
    double[] peakRates = peakRelatives(of(runs, "off"), of(runs, variant));
    Map<String, Object> peak = new LinkedHashMap<>();
    peak.put("windowSeconds", 60);
    peak.put("stepSeconds", 10);
    peak.put("windowRelatives", peakRates);
    double peakValue = Stats.max(peakRates);
    if (container) {
      peak.put("value", peakValue);
      peak.put("note", "reported only (N-29 has no peak limit)");
    } else {
      Map<String, Object> pv = verdict(peakValue, 0.08, "relative");
      peak.putAll(pv);
      if ("FAIL".equals(pv.get("verdict"))) {
        failed.add(
            String.format(Locale.ROOT, "peak relative CPU %+.2f%% > 8%%%s", peakValue * 100, tag));
      }
      noData(noData, pv, "1-minute peak relative CPU (N-01)");
    }
    c.put("peak", peak);

    // Agent threads' own CPU (N-01 auxiliary: <= 1% of the host).
    double[] agentCpu = values(runs, variant, "measure", "agentThreadCpuSeconds");
    double[] wall = values(runs, variant, "measure", "wallSeconds");
    double[] hostPercent = new double[agentCpu.length];
    for (int i = 0; i < agentCpu.length; i++) {
      hostPercent[i] = 100 * agentCpu[i] / (wall[i] * logicalCpus);
    }
    Map<String, Object> threads = new LinkedHashMap<>();
    threads.put("agentThreadCpuSeconds", agentCpu);
    threads.put("hostCpus", logicalCpus);
    if (variant.equals("on")) {
      Map<String, Object> tv = verdict(Stats.max(hostPercent), 1.0, "% of host CPU");
      threads.putAll(tv);
      if ("FAIL".equals(tv.get("verdict"))) {
        failed.add(
            String.format(
                Locale.ROOT, "agent thread CPU %.3f%% of host > 1%%", Stats.max(hostPercent)));
      }
      noData(noData, tv, "agent thread CPU (N-01)");
    }
    c.put("agentThreadCpu", threads);

    // Latency (01 §7.3 item 6, 18 §7.2, §7.4): per run the quantiles of the re-aggregated HDR
    // histogram (else k6's summary). The difference on - off of the run means has two 95%
    // intervals: bootstrap over the requests (1,000 replicates, fixed seed) when every run has its
    // histogram, and Student t over the paired rounds (n - 1 df). "No difference" when the
    // bootstrap interval contains 0 (the t interval when there is no bootstrap).
    List<LatencyHistogram> offHdr = histograms(runs, "off");
    List<LatencyHistogram> onHdr = histograms(runs, variant);
    boolean bootstrap = !offHdr.isEmpty() && !onHdr.isEmpty();
    Map<String, Object> latency = new LinkedHashMap<>();
    latency.put("source", bootstrap ? "hdr (k6 raw output)" : "k6 summary");
    for (String q : QUANTILES.keySet()) {
      double[] lo = latencies(runs, "off", q);
      double[] lv = latencies(runs, variant, q);
      Stats.Interval d = Stats.meanInterval(Stats.pairedDifferences(lv, lo));
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("off", lo);
      l.put(variant, lv);
      l.put("offMean", Stats.mean(lo));
      l.put(variant + "Mean", Stats.mean(lv));
      l.put("diffMean", d.mean());
      l.put("diffCi95", List.of(d.low(), d.high()));
      boolean noDifference = d.containsZero();
      if (bootstrap) {
        Bootstrap.Interval b =
            Bootstrap.meanQuantileDifference(
                onHdr, offHdr, QUANTILES.get(q), Bootstrap.REPLICATES, seed);
        l.put("diffBootstrapCi95", List.of(b.low(), b.high()));
        l.put("bootstrapReplicates", b.replicates());
        l.put("bootstrapSeed", b.seed());
        noDifference = b.containsZero();
      }
      l.put("noDifference", noDifference);
      if (q.equals("p99")) {
        double limitMs = Math.max(1.0, 0.05 * Stats.mean(lo));
        Map<String, Object> lv99 = verdict(d.mean(), limitMs, "ms");
        l.put("check", lv99);
        if ("FAIL".equals(lv99.get("verdict")) && variant.equals("on")) {
          failed.add(
              String.format(Locale.ROOT, "p99 increase %.3f ms > %.3f ms", d.mean(), limitMs));
        }
        noData(noData, lv99, "p99 increase (N-03)");
      }
      latency.put(q, l);
    }
    c.put("latency", latency);

    // Per route p99 (informational: the 300 ms route dominates the mix p99).
    Map<String, Object> routes = new LinkedHashMap<>();
    for (String route : routeNames(runs)) {
      double[] ro = routeP99(runs, "off", route);
      double[] rv = routeP99(runs, variant, route);
      Stats.Interval d = Stats.meanInterval(Stats.pairedDifferences(rv, ro));
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("offP99Mean", Stats.mean(ro));
      r.put(variant + "P99Mean", Stats.mean(rv));
      r.put("diffMean", d.mean());
      r.put("diffCi95", List.of(d.low(), d.high()));
      routes.put(route, r);
    }
    c.put("routesP99", routes);

    // Memory and threads (01 §7.3 item 7, N-02, N-26).
    Map<String, Object> memory = new LinkedHashMap<>();
    double agentHeapMb = Stats.max(values(runs, variant, "memory", "agentHeapBytes")) / MB / MB;

    // Metaspace (18 §7.4): jcmd VM.metaspace used, on - off; NMT's metaspace when a run has no
    // VM.metaspace numbers (WO-02 sets).
    String metaKey =
        allHave(runs, variant, "vmMetaspaceUsedKb") && allHave(runs, "off", "vmMetaspaceUsedKb")
            ? "vmMetaspaceUsedKb"
            : "metaspaceUsedKb";
    double metaDiff =
        (Stats.mean(values(runs, variant, "memory", metaKey))
                - Stats.mean(values(runs, "off", "memory", metaKey)))
            / MB;
    memory.put(
        "metaspaceSource",
        metaKey.equals("vmMetaspaceUsedKb") ? "jcmd VM.metaspace (used)" : "NMT Class (used)");
    double internalOther =
        (mean(runs, variant, "Internal")
                + mean(runs, variant, "Other")
                - mean(runs, "off", "Internal")
                - mean(runs, "off", "Other"))
            / MB;
    double other = (mean(runs, variant, "Other") - mean(runs, "off", "Other")) / MB;
    // Threads (18 §7.4): /proc/<pid>/status Threads where measured (Linux), else NMT's count.
    String threadKey =
        allHave(runs, variant, "osThreads") && allHave(runs, "off", "osThreads")
            ? "osThreads"
            : "nmtThreads";
    double threadDiff =
        Stats.mean(values(runs, variant, "memory", threadKey))
            - Stats.mean(values(runs, "off", "memory", threadKey));
    memory.put(
        "threadSource",
        threadKey.equals("osThreads") ? "/proc/<pid>/status Threads" : "NMT Thread count");
    double totalDiff =
        (Stats.mean(values(runs, variant, "memory", "nmtTotalCommittedKb"))
                - Stats.mean(values(runs, "off", "memory", "nmtTotalCommittedKb")))
            / MB;
    double classDiff =
        Stats.mean(values(runs, variant, "memory", "classes"))
            - Stats.mean(values(runs, "off", "memory", "classes"));
    // Live heap after the two GC.run (sum of the class histogram), on - off: everything the agent
    // keeps (its strings, arrays and collections too, which the io.argos.agent.* count of N-02
    // misses), plus the run-to-run noise of the app's own caches. Informational.
    double liveHeapDiff =
        (Stats.mean(values(runs, variant, "memory", "histogramTotalBytes"))
                - Stats.mean(values(runs, "off", "memory", "histogramTotalBytes")))
            / MB
            / MB;
    if (variant.equals("on")) {
      List<Object[]> checks = new ArrayList<>();
      checks.add(new Object[] {"agentHeapMb", agentHeapMb, 64.0, "MB", "N-02 agent heap"});
      checks.add(
          new Object[] {"metaspaceIncreaseMb", metaDiff, 32.0, "MB", "N-26 metaspace increase"});
      checks.add(
          new Object[] {
            "nmtInternalOtherIncreaseMb",
            internalOther,
            16.0,
            "MB",
            "01 7.3 item 7 NMT Internal+Other"
          });
      checks.add(
          new Object[] {
            "directMemoryIncreaseMb", other, 8.0, "MB", "N-26 direct memory (NMT Other)"
          });
      checks.add(
          new Object[] {
            "threadIncrease", threadDiff, 6.0, "threads", "01 7.3 item 7 thread increase"
          });
      double agentThreads = maxAgentThreads(of(runs, variant));
      checks.add(new Object[] {"agentThreads", agentThreads, 6.0, "threads", "N-26 agent threads"});
      for (Object[] check : checks) {
        Map<String, Object> v = verdict((Double) check[1], (Double) check[2], (String) check[3]);
        v.put("requirement", check[4]);
        memory.put((String) check[0], v);
        if ("FAIL".equals(v.get("verdict"))) {
          failed.add(
              String.format(
                  Locale.ROOT,
                  "%s %.2f %s > %.0f",
                  check[4],
                  (Double) check[1],
                  check[3],
                  (Double) check[2]));
        }
        noData(noData, v, String.valueOf(check[4]));
      }
    } else {
      memory.put("metaspaceIncreaseMb", metaDiff);
      memory.put("nmtInternalOtherIncreaseMb", internalOther);
      memory.put("threadIncrease", threadDiff);
    }
    memory.put("nmtTotalCommittedIncreaseMb", totalDiff);
    memory.put("liveHeapIncreaseMb", liveHeapDiff);
    memory.put("loadedClassIncrease", classDiff);
    c.put("memory", memory);

    // GC in the window (informational; transient allocation per request, 02 §12 A-3).
    Map<String, Object> gc = new LinkedHashMap<>();
    double offAlloc = Stats.mean(values(runs, "off", "gc", "allocatedKbPerRequest"));
    double onAlloc = Stats.mean(values(runs, variant, "gc", "allocatedKbPerRequest"));
    gc.put("offAllocatedKbPerRequestMean", offAlloc);
    gc.put(variant + "AllocatedKbPerRequestMean", onAlloc);
    gc.put("allocatedKbPerRequestIncrease", onAlloc - offAlloc);
    gc.put("offPausesMean", Stats.mean(values(runs, "off", "gc", "pauses")));
    gc.put(variant + "PausesMean", Stats.mean(values(runs, variant, "gc", "pauses")));
    gc.put("offPauseMsTotalMean", Stats.mean(values(runs, "off", "gc", "pauseMsTotal")));
    gc.put(variant + "PauseMsTotalMean", Stats.mean(values(runs, variant, "gc", "pauseMsTotal")));
    c.put("gc", gc);

    // premain (N-30) and the startup of the load runs (informational; startup set judges N-04).
    double[] premain = values(runs, variant, "premainMs");
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("premainMs", premain);
    start.put("premainMedianMs", Stats.median(premain));
    start.put("offReadyMedianMs", Stats.median(values(runs, "off", "startup", "spawnToReadyMs")));
    start.put(
        variant + "ReadyMedianMs",
        Stats.median(values(runs, variant, "startup", "spawnToReadyMs")));
    c.put("startup", start);
    c.put("failed", failed);
    c.put("noData", noData);
    return c;
  }

  /** Adds {@code what} to {@code noData} when the check has no number. */
  private static void noData(List<String> noData, Map<String, Object> check, String what) {
    if ("NO DATA".equals(check.get("verdict"))) {
      noData.add(what);
    }
  }

  /** True when every run of {@code variant} has a number at memory.{@code key}. */
  private static boolean allHave(List<Map<String, Object>> runs, String variant, String key) {
    List<Map<String, Object>> rs = of(runs, variant);
    return !rs.isEmpty() && rs.stream().allMatch(r -> !Double.isNaN(Json.number(r, "memory", key)));
  }

  private static double mean(List<Map<String, Object>> runs, String variant, String nmtCategory) {
    return Stats.mean(values(runs, variant, "memory", "nmtCommittedKb", nmtCategory));
  }

  private static double maxAgentThreads(List<Map<String, Object>> runs) {
    double max = 0;
    for (Map<String, Object> r : runs) {
      max = Math.max(max, Json.array(Json.at(r, "measure", "agentThreads")).size());
    }
    return max;
  }

  static double[] peakRelatives(
      List<Map<String, Object>> offRuns, List<Map<String, Object>> onRuns) {
    List<double[]> off = windowRates(offRuns);
    List<double[]> on = windowRates(onRuns);
    if (off.isEmpty() || on.isEmpty()) {
      return new double[0];
    }
    int n = Integer.MAX_VALUE;
    for (double[] r : off) {
      n = Math.min(n, r.length);
    }
    for (double[] r : on) {
      n = Math.min(n, r.length);
    }
    double[] rel = new double[n];
    for (int k = 0; k < n; k++) {
      double a = 0;
      double b = 0;
      for (double[] r : off) {
        a += r[k];
      }
      for (double[] r : on) {
        b += r[k];
      }
      rel[k] = (b / on.size()) / (a / off.size()) - 1;
    }
    return rel;
  }

  private static List<double[]> windowRates(List<Map<String, Object>> runs) {
    List<double[]> out = new ArrayList<>();
    for (Map<String, Object> r : runs) {
      List<Object> rows = Json.array(Json.at(r, "measure", "appCpuTimeline"));
      double[] t = new double[rows.size()];
      double[] y = new double[rows.size()];
      for (int i = 0; i < rows.size(); i++) {
        List<Object> row = Json.array(rows.get(i));
        t[i] = Json.number(row.get(0));
        y[i] = Json.number(row.get(1));
      }
      double[] rates = Stats.windowRates(t, y, 60, 10);
      if (rates.length > 0) {
        out.add(rates);
      }
    }
    return out;
  }

  private static List<String> routeNames(List<Map<String, Object>> runs) {
    Set<String> names = new LinkedHashSet<>();
    for (Map<String, Object> r : runs) {
      for (Object route : Json.array(Json.at(r, "measure", "routes"))) {
        names.add(String.valueOf(Json.at(route, "route")));
      }
    }
    return new ArrayList<>(names);
  }

  private static double[] routeP99(List<Map<String, Object>> runs, String variant, String route) {
    List<Double> out = new ArrayList<>();
    for (Map<String, Object> r : of(runs, variant)) {
      double v = Double.NaN;
      LatencyHistogram h = routeHistogram(r, route);
      if (h != null) {
        v = h.quantileMillis(0.99);
      } else {
        for (Object x : Json.array(Json.at(r, "measure", "routes"))) {
          if (route.equals(Json.at(x, "route"))) {
            v = Json.number(x, "latencyMs", "p99");
          }
        }
      }
      out.add(v);
    }
    return Stats.toArray(out);
  }

  // ---- report.md ------------------------------------------------------------------------------

  static String fmt(double v, int decimals) {
    return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%." + decimals + "f", v);
  }

  static String pct(double v) {
    return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%+.2f%%", v * 100);
  }

  static String markdown(
      Map<String, Object> set, List<Map<String, Object>> runs, Map<String, Object> result) {
    StringBuilder md = new StringBuilder();
    Map<String, Object> p = Json.object(set.get("parameters"));
    md.append("# Agent overhead load set: ").append(result.get("verdict")).append("\n\n");
    md.append(
        "Generated by test/bench (18 §7, set level). The benchmark report of 18 §7.6 is"
            + " `bench.sh publish` over one or more sets.\n\n");
    md.append("- label: ").append(set.get("label")).append('\n');
    md.append("- exporter: ")
        .append(p.get("exporter") == null ? "(not recorded)" : p.get("exporter"))
        .append(", profile ")
        .append(p.get("profile") == null ? "(not recorded)" : p.get("profile"))
        .append(", latency source ")
        .append(Json.at(result, "comparisons", "on", "latency", "source"))
        .append('\n');
    md.append("- mode: ")
        .append(p.get("mode"))
        .append(", app ")
        .append(p.get("app"))
        .append(", rate ")
        .append(p.get("rate"))
        .append("/s, warm-up ")
        .append(p.get("warmupSeconds"))
        .append(" s, window ")
        .append(p.get("durationSeconds"))
        .append(" s, ")
        .append(p.get("order"))
        .append('\n');
    md.append("- JVM: ")
        .append(p.get("jvmOptions"))
        .append(" + ")
        .append(p.get("jvmOptionsAddedByHarness"))
        .append(", app env ")
        .append(p.get("appEnv"))
        .append('\n');
    md.append("- agent settings: ").append(p.get("agentConfig")).append('\n');
    md.append("- k6: ").append(Json.at(set, "environment", "k6")).append('\n');
    md.append("- started ")
        .append(set.get("startedAt"))
        .append(", finished ")
        .append(set.get("finishedAt"))
        .append('\n');
    md.append("- criteria: ").append(result.get("criteria")).append('\n');
    md.append("- reasons: ").append(result.get("reasons")).append("\n\n");
    md.append("## Runs (measured window)\n\n");
    md.append(
        "| # | round | variant | app CPU s | requests | dropped | unexpected | p50 ms | p95 ms | p99 ms | p99.9 ms | latency source | host CPU % | background CPU % | loader CPU % | CPU before % | attempt (discarded) | gate |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Object o : Json.array(result.get("runs"))) {
      md.append("| ")
          .append(Json.at(o, "index"))
          .append(" | ")
          .append(Json.at(o, "round"))
          .append(" | ")
          .append(Json.at(o, "variant"))
          .append(" | ")
          .append(fmt(Json.number(o, "appCpuSeconds"), 2))
          .append(" | ")
          .append(fmt(Json.number(o, "requests"), 0))
          .append(" | ")
          .append(fmt(Json.number(o, "dropped"), 0))
          .append(" | ")
          .append(fmt(Json.number(o, "unexpectedRate") * 100, 3))
          .append('%')
          .append(" | ")
          .append(fmt(Json.number(o, "p50"), 3))
          .append(" | ")
          .append(fmt(Json.number(o, "p95"), 3))
          .append(" | ")
          .append(fmt(Json.number(o, "p99"), 3))
          .append(" | ")
          .append(fmt(Json.number(o, "p999"), 3))
          .append(" | ")
          .append(Json.at(o, "latencySource"))
          .append(" | ")
          .append(fmt(Json.number(o, "hostCpuPercentMean"), 1))
          .append(" | ")
          .append(fmt(Json.number(o, "backgroundCpuPercent"), 1))
          .append(" | ")
          .append(fmt(Json.number(o, "loaderCpuPercentMean"), 1))
          .append(" | ")
          .append(fmt(Json.number(o, "cpuBeforePercent"), 1))
          .append(" | ")
          .append(Json.at(o, "attempt"))
          .append(" (")
          .append(Json.at(o, "discardedAttempts"))
          .append(") | ")
          .append(Json.at(o, "gate"))
          .append(" |\n");
    }
    md.append("\nOff-run CPU CV: ")
        .append(fmt(Json.number(result, "validity", "offCpuCv") * 100, 2))
        .append("% (limit 2%).\n");
    for (Map.Entry<String, Object> e : Json.object(result.get("comparisons")).entrySet()) {
      String v = e.getKey();
      Object c = e.getValue();
      md.append("\n## ").append(v).append(" vs off\n\n");
      md.append("| measure | off | ")
          .append(v)
          .append(
              " | difference | 95% CI of the paired difference (t over rounds; bootstrap over requests) | limit | verdict |\n|---|---|---|---|---|---|---|\n");
      md.append("| app CPU s (mean) | ")
          .append(fmt(Json.number(c, "cpu", "offMean"), 2))
          .append(" | ")
          .append(fmt(Json.number(c, "cpu", v + "Mean"), 2))
          .append(" | ")
          .append(pct(Json.number(c, "cpu", "relative")))
          .append(" | ")
          .append(pct(Json.number(Json.array(Json.at(c, "cpu", "pairRelativeCi95")).get(0))))
          .append(" .. ")
          .append(pct(Json.number(Json.array(Json.at(c, "cpu", "pairRelativeCi95")).get(1))))
          .append(" | ")
          .append(Json.at(c, "cpu", "check", "limit"))
          .append(" | ")
          .append(Json.at(c, "cpu", "check", "verdict"))
          .append(" |\n");
      md.append("| app CPU ms per request | ")
          .append(fmt(Json.number(c, "cpu", "offCpuMsPerRequestMean"), 4))
          .append(" | ")
          .append(fmt(Json.number(c, "cpu", v + "CpuMsPerRequestMean"), 4))
          .append(" | ")
          .append(pct(Json.number(c, "cpu", "relativePerRequest")))
          .append(" | | | |\n");
      md.append("| peak 1-min relative CPU | | | ")
          .append(pct(Json.number(c, "peak", "value")))
          .append(" | | ")
          .append(Json.at(c, "peak", "limit") == null ? "-" : Json.at(c, "peak", "limit"))
          .append(" | ")
          .append(
              Json.at(c, "peak", "verdict") == null ? "reported" : Json.at(c, "peak", "verdict"))
          .append(" |\n");
      for (String q : List.of("p50", "p95", "p99", "p99.9")) {
        Object l = Json.at(c, "latency", q);
        List<Object> ci = Json.array(Json.at(l, "diffCi95"));
        md.append("| ")
            .append(q)
            .append(" ms (mean of runs) | ")
            .append(fmt(Json.number(l, "offMean"), 3))
            .append(" | ")
            .append(fmt(Json.number(l, v + "Mean"), 3))
            .append(" | ")
            .append(fmt(Json.number(l, "diffMean"), 3))
            .append(" | ")
            .append(fmt(Json.number(ci.get(0)), 3))
            .append(" .. ")
            .append(fmt(Json.number(ci.get(1)), 3))
            .append(" (t)");
        List<Object> boot = Json.array(Json.at(l, "diffBootstrapCi95"));
        if (boot.size() == 2) {
          md.append("; ")
              .append(fmt(Json.number(boot.get(0)), 3))
              .append(" .. ")
              .append(fmt(Json.number(boot.get(1)), 3))
              .append(" (bootstrap)");
        }
        md.append(Boolean.TRUE.equals(Json.at(l, "noDifference")) ? " (no difference)" : "")
            .append(" | ")
            .append(q.equals("p99") ? fmt(Json.number(l, "check", "limit"), 3) : "")
            .append(" | ")
            .append(q.equals("p99") ? Json.at(l, "check", "verdict") : "")
            .append(" |\n");
      }
      if (Json.at(c, "agentThreadCpu", "verdict") != null) {
        md.append("| agent thread CPU (max of runs) | | | ")
            .append(fmt(Json.number(c, "agentThreadCpu", "value"), 4))
            .append("% of host | | 1% | ")
            .append(Json.at(c, "agentThreadCpu", "verdict"))
            .append(" |\n");
      }
      for (Map.Entry<String, Object> m : Json.object(Json.at(c, "memory")).entrySet()) {
        if (m.getValue() instanceof Map<?, ?> check) {
          md.append("| ")
              .append(check.get("requirement"))
              .append(" | | | ")
              .append(fmt(Json.number(check.get("value")), 2))
              .append(' ')
              .append(check.get("unit"))
              .append(" | | ")
              .append(check.get("limit"))
              .append(" | ")
              .append(check.get("verdict"))
              .append(" |\n");
        } else if (m.getValue() instanceof String text) {
          md.append("| ").append(m.getKey()).append(" | ").append(text).append(" | | | | | |\n");
        } else {
          md.append("| ")
              .append(m.getKey())
              .append(" | | | ")
              .append(fmt(Json.number(m.getValue()), 2))
              .append(" | | | |\n");
        }
      }
      md.append("| allocated KB per request (GC log) | ")
          .append(fmt(Json.number(c, "gc", "offAllocatedKbPerRequestMean"), 2))
          .append(" | ")
          .append(fmt(Json.number(c, "gc", v + "AllocatedKbPerRequestMean"), 2))
          .append(" | ")
          .append(fmt(Json.number(c, "gc", "allocatedKbPerRequestIncrease"), 2))
          .append(" | | | |\n");
      md.append("| GC pauses in the window (count / total ms) | ")
          .append(fmt(Json.number(c, "gc", "offPausesMean"), 1))
          .append(" / ")
          .append(fmt(Json.number(c, "gc", "offPauseMsTotalMean"), 1))
          .append(" | ")
          .append(fmt(Json.number(c, "gc", v + "PausesMean"), 1))
          .append(" / ")
          .append(fmt(Json.number(c, "gc", v + "PauseMsTotalMean"), 1))
          .append(" | | | | |\n");
      md.append("| premain ms (median) | | ")
          .append(fmt(Json.number(c, "startup", "premainMedianMs"), 1))
          .append(" | | | 500 | |\n");
      md.append("\nPer-route p99 (ms, mean of runs; informational):\n\n| route | off | ")
          .append(v)
          .append(" | difference | 95% CI |\n|---|---|---|---|---|\n");
      for (Map.Entry<String, Object> r : Json.object(Json.at(c, "routesP99")).entrySet()) {
        List<Object> ci = Json.array(Json.at(r.getValue(), "diffCi95"));
        md.append("| ")
            .append(r.getKey())
            .append(" | ")
            .append(fmt(Json.number(r.getValue(), "offP99Mean"), 3))
            .append(" | ")
            .append(fmt(Json.number(r.getValue(), v + "P99Mean"), 3))
            .append(" | ")
            .append(fmt(Json.number(r.getValue(), "diffMean"), 3))
            .append(" | ")
            .append(fmt(Json.number(ci.get(0)), 3))
            .append(" .. ")
            .append(fmt(Json.number(ci.get(1)), 3))
            .append(" |\n");
      }
    }
    md.append(
        "\nRaw files per run: runs/<NN>-<variant>/ (run.json, k6.json, k6-warmup.json, k6-raw.json.gz, latency-hdr.json, machine.json, threads-*.txt, class-histogram.txt, nmt-summary.txt, metaspace.txt, gc.log, app-log-excerpt.txt, agent-logs/).\n");
    return md.toString();
  }
}
