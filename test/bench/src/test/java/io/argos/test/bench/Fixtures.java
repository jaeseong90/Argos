/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic sets, runs and environment records that follow the 01 §7.3 protocol. */
final class Fixtures {
  private Fixtures() {}

  /** set.json of a load set with the protocol's parameters. */
  static Map<String, Object> loadSet(String mode, boolean vt, String profile) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mode", mode);
    p.put("app", "shop-boot3");
    p.put("rounds", 3);
    p.put("variants", List.of("off", "on"));
    p.put("warmupSeconds", 180);
    p.put("durationSeconds", 600);
    p.put("rate", 1000);
    p.put("jvmOptions", List.of("-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"));
    p.put("appEnv", vt ? Map.of("SAMPLE_VT", "true") : Map.of());
    p.put("exporter", "noop");
    p.put("profile", profile);
    p.put("k6Ssh", mode.equals("process") ? "bench@loadhost" : null);
    p.put("cpus", 2);
    p.put("memory", "4g");
    Map<String, Object> set = new LinkedHashMap<>();
    set.put("kind", "load");
    set.put("label", "fixture");
    set.put("startedAt", "2026-11-03T01:02:03Z");
    set.put("parameters", p);
    set.put("environment", Map.of("logicalCpus", 12));
    set.put("git", Map.of("commit", "4a43e77c16472b2cfcb7566506324b7cea16b128"));
    set.put("inputs", Map.of());
    return set;
  }

  /** set.json of a startup set with the protocol's parameters. */
  static Map<String, Object> startupSet() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("apps", List.of("shop-boot3", "shop-heavy"));
    p.put("runsPerVariant", 10);
    p.put("cds", List.of("default", "off"));
    p.put("variants", List.of("off", "on"));
    p.put("jvmOptions", List.of("-Xms2g", "-Xmx2g", "-XX:+UseG1GC"));
    Map<String, Object> set = new LinkedHashMap<>();
    set.put("kind", "startup");
    set.put("startedAt", "2026-11-03T03:00:00Z");
    set.put("parameters", p);
    set.put("git", Map.of("commit", "4a43e77c16472b2cfcb7566506324b7cea16b128"));
    return set;
  }

  /** Off/on runs of three rounds (A-B-A-B-A-B) with the given CPU seconds and p99. */
  static List<Map<String, Object>> loadRuns(double[] off, double[] on) {
    return ReportTest.runs(
        off, on, new double[] {300.5, 300.6, 300.4}, new double[] {300.7, 300.9, 300.5});
  }

  /** Startup runs: 10 starts per app, CDS mode and variant. */
  static List<Map<String, Object>> startupRuns(long boot3Increase, long heavyIncrease) {
    List<Map<String, Object>> runs = new ArrayList<>();
    for (String cds : List.of("default", "off")) {
      for (int i = 0; i < 10; i++) {
        runs.add(ReportTest.start("shop-boot3", cds, "off", 7000 + i, null));
        runs.add(ReportTest.start("shop-boot3", cds, "on", 7000 + boot3Increase + i, 420.0));
        runs.add(ReportTest.start("shop-heavy", cds, "off", 30_000 + i, null));
        runs.add(ReportTest.start("shop-heavy", cds, "on", 30_000 + heavyIncrease + i, 450.0));
      }
    }
    return runs;
  }

  /**
   * Adds {@code measure.selfDiag} to the on runs as the lane B harness writes it from the agent's
   * self-diagnostics file (C-95): agent threads' CPU seconds of the window, direct memory bytes at
   * its end and premain ms.
   */
  static List<Map<String, Object>> withSelfDiag(
      List<Map<String, Object>> runs, double agentCpuSeconds, long directBytes, double premainMs) {
    for (Map<String, Object> r : runs) {
      if (!"on".equals(r.get("variant"))) {
        continue;
      }
      Map<String, Object> measure = new LinkedHashMap<>(Json.object(r.get("measure")));
      Map<String, Object> selfDiag = new LinkedHashMap<>();
      selfDiag.put("agentThreadsCpuSeconds", agentCpuSeconds);
      selfDiag.put("directMemoryBytes", directBytes);
      selfDiag.put("premainMs", premainMs);
      selfDiag.put("source", "selfdiag-file");
      measure.put("selfDiag", selfDiag);
      r.put("measure", measure);
    }
    return runs;
  }

  /** set.json of a load set measured with the given exporter. */
  static Map<String, Object> loadSet(String exporter) {
    Map<String, Object> set = loadSet("process", false, "default");
    Map<String, Object> p = new LinkedHashMap<>(Json.object(set.get("parameters")));
    p.put("exporter", exporter);
    if (exporter.equals("grpc")) {
      p.put("collector", "loadhost:4317");
    }
    set.put("parameters", p);
    return set;
  }

  /** A bench-env.sh record; {@code problems} non-empty makes it invalid. */
  static Map<String, Object> envRecord(String... problems) {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("tool", "bench-env.sh");
    env.put("machineProfile", "argos-bench-app");
    env.put("app", Map.of("cpuModel", "Test CPU", "governor", "performance"));
    env.put("valid", problems.length == 0);
    env.put("problems", List.of(problems));
    env.put("overrides", List.of());
    return env;
  }

  static BenchReport.SetData setData(
      String name,
      Map<String, Object> set,
      List<Map<String, Object>> runs,
      Map<String, Object> env) {
    Map<String, Object> result =
        "load".equals(set.get("kind"))
            ? LoadReport.analyze(set, runs, Bootstrap.DEFAULT_SEED)
            : StartupReport.analyze(runs);
    return new BenchReport.SetData(
        name, Path.of(name), set, runs, result, BenchReport.role(set), env);
  }

  /** A complete, valid report input: main, vt, container and startup sets within the limits. */
  static List<BenchReport.SetData> completeSets(Map<String, Object> env) {
    double[] off = {600, 606, 594};
    double[] on = {618, 624, 612};
    return new ArrayList<>(
        List.of(
            setData("main", loadSet("process", false, "default"), loadRuns(off, on), env),
            setData("vt", loadSet("process", true, "default"), loadRuns(off, on), env),
            setData("container", loadSet("container", false, "default"), loadRuns(off, on), env),
            setData("startup", startupSet(), startupRuns(1500, 1800), env)));
  }
}
