/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * Evidence for the measurement noise threshold (18 open item 3): per load set the off-run CPU
 * coefficient of variation (01 §7.3 item 4 limit 2%), off-vs-off (A-A) bootstrap intervals of the
 * relative CPU and the p99 difference (what two groups of off runs differ by with no agent at all),
 * the minimal detectable effect (two-sided 95%, power 80%) of the relative CPU and p99 for the
 * set's number of rounds, and the CPU interval of the agent comparison itself. It recommends; the
 * constant {@link LoadReport#CV_LIMIT} stays until the PM decides. Inputs that are INVALID (shared
 * machine, no environment record, harness verdict) give reference values only.
 */
final class NoiseCalibration {
  private NoiseCalibration() {}

  /** z(0.975) + z(0.80): two-sided 5% test with 80% power. */
  static final double Z_MDE = 1.959964 + 0.841621;

  /** {@code noise --sets D[,D...] [--out F] [--seed 42] [--replicates 1000]}. */
  static int run(Options o) throws IOException {
    List<String> dirs = o.list("sets", "");
    if (dirs.isEmpty()) {
      throw new UsageException("--sets DIR[,DIR...] is required");
    }
    Path out = o.optional("out").map(p -> Path.of(p).toAbsolutePath().normalize()).orElse(null);
    long seed = o.integer("seed", (int) Bootstrap.DEFAULT_SEED, 0, Integer.MAX_VALUE);
    int replicates = o.integer("replicates", Bootstrap.REPLICATES, 100, 100_000);
    o.rejectUnknown();
    List<Map<String, Object>> sets = new ArrayList<>();
    for (String d : dirs) {
      sets.add(analyze(Path.of(d).toAbsolutePath().normalize(), seed, replicates));
    }
    Map<String, Object> model = model(sets, seed, replicates);
    String md = markdown(model);
    if (out == null) {
      System.out.print(md);
    } else {
      if (out.getParent() != null) {
        Files.createDirectories(out.getParent());
      }
      Files.writeString(out, md, StandardCharsets.UTF_8);
      String base = out.getFileName().toString();
      String json = (base.endsWith(".md") ? base.substring(0, base.length() - 3) : base) + ".json";
      FileTools.writeJson(out.resolveSibling(json), model);
      LoadSet.log("noise calibration " + out);
    }
    return 0;
  }

  /** Reads a load set directory and analyses its noise. */
  static Map<String, Object> analyze(Path dir, long seed, int replicates) throws IOException {
    Path setFile = dir.resolve("set.json");
    if (!Files.isRegularFile(setFile)) {
      throw new UsageException("no set.json in " + dir);
    }
    Map<String, Object> set = Json.object(FileTools.readJson(setFile));
    if (!"load".equals(set.get("kind"))) {
      throw new UsageException(dir + " is not a load set (noise needs off runs)");
    }
    List<Map<String, Object>> runs = LoadReport.readRuns(dir);
    Map<String, Object> result = LoadReport.analyze(set, runs, seed);
    List<String> invalid = new ArrayList<>();
    if ("INVALID".equals(result.get("verdict"))) {
      invalid.add("harness verdict INVALID " + result.get("reasons"));
    }
    invalid.addAll(EnvRecord.problems(EnvRecord.ofSet(dir)));
    invalid.addAll(Protocol.deviations(set));
    return analyze(
        dir.getFileName().toString(),
        values(runs, "off", r -> Json.number(r, "measure", "appCpuSeconds")),
        values(runs, "off", r -> LoadReport.latency(r, "p99")),
        values(runs, "on", r -> Json.number(r, "measure", "appCpuSeconds")),
        values(runs, "on", r -> LoadReport.latency(r, "p99")),
        invalid,
        seed,
        replicates);
  }

  private static double[] values(
      List<Map<String, Object>> runs, String variant, ToDoubleFunction<Map<String, Object>> f) {
    return runs.stream().filter(r -> variant.equals(r.get("variant"))).mapToDouble(f).toArray();
  }

  /**
   * The noise numbers of one set. Pure and deterministic for a seed: the A-A intervals resample two
   * groups of n off runs (n = number of off runs, with replacement) {@code replicates} times.
   */
  static Map<String, Object> analyze(
      String name,
      double[] offCpu,
      double[] offP99,
      double[] onCpu,
      double[] onP99,
      List<String> invalid,
      long seed,
      int replicates) {
    Map<String, Object> out = new LinkedHashMap<>();
    int n = offCpu.length;
    double cv = Stats.cv(offCpu);
    out.put("set", name);
    out.put("offRuns", n);
    out.put("offCpuSeconds", offCpu);
    out.put("offP99Ms", offP99);
    out.put("offCpuCv", cv);
    out.put("offCpuCvOk", n >= 2 && cv <= LoadReport.CV_LIMIT);
    double[] aaCpu = aa(offCpu, true, seed, replicates);
    double[] aaP99 = aa(offP99, false, seed, replicates);
    out.put("aaRelativeCpuCi95", List.of(aaCpu[0], aaCpu[1]));
    out.put("aaRelativeCpuHalfWidth", (aaCpu[1] - aaCpu[0]) / 2);
    out.put("aaP99DiffCi95", List.of(aaP99[0], aaP99[1]));
    out.put("aaP99DiffHalfWidth", (aaP99[1] - aaP99[0]) / 2);
    double factor = n >= 2 ? Z_MDE * Math.sqrt(2.0 / n) : Double.NaN;
    double mdeCpu = factor * cv;
    double mdeP99 = factor * Stats.sd(offP99);
    out.put("mdeRelativeCpu", mdeCpu);
    out.put("mdeP99Ms", mdeP99);
    out.put("warnCpuDetectable", mdeCpu <= Regression.CPU_POINTS);
    out.put("warnP99Detectable", mdeP99 <= Regression.P99_MS);
    // CV at which the 18 §7.7 WARN step (+1.5 pp) is detectable with n rounds, and the rounds
    // needed at the observed CV.
    out.put("cvForWarnStep", n >= 2 ? Regression.CPU_POINTS / factor : Double.NaN);
    out.put(
        "roundsForWarnStep",
        Double.isNaN(cv)
            ? Double.NaN
            : Math.max(2, Math.ceil(2 * Math.pow(Z_MDE * cv / Regression.CPU_POINTS, 2))));
    // The agent comparison itself: t interval of the per-round relative CPU (on / off - 1).
    int pairs = Math.min(onCpu.length, offCpu.length);
    if (pairs >= 2) {
      double[] rel = new double[pairs];
      for (int i = 0; i < pairs; i++) {
        rel[i] = onCpu[i] / offCpu[i] - 1;
      }
      Stats.Interval ci = Stats.meanInterval(rel);
      out.put("pairRelativeCpuCi95", List.of(ci.low(), ci.high()));
      out.put("pairRelativeCpuHalfWidth", (ci.high() - ci.low()) / 2);
      Stats.Interval d = Stats.meanInterval(Stats.pairedDifferences(onP99, offP99));
      out.put("pairP99DiffHalfWidth", (d.high() - d.low()) / 2);
    }
    out.put("invalidInputs", invalid);
    out.put("reference", !invalid.isEmpty());
    return out;
  }

  /**
   * The 2.5% and 97.5% nearest-rank percentiles of the A-A statistic: two groups of n values drawn
   * with replacement, {@code mean(B) / mean(A) - 1} (relative) or {@code mean(B) - mean(A)}. NaN
   * below two values.
   */
  static double[] aa(double[] values, boolean relative, long seed, int replicates) {
    int n = values.length;
    if (n < 2 || Arrays.stream(values).anyMatch(Double::isNaN)) {
      return new double[] {Double.NaN, Double.NaN};
    }
    SplittableRandom random = new SplittableRandom(seed);
    double[] stats = new double[replicates];
    for (int r = 0; r < replicates; r++) {
      double a = 0;
      double b = 0;
      for (int i = 0; i < n; i++) {
        a += values[random.nextInt(n)];
        b += values[random.nextInt(n)];
      }
      stats[r] = relative ? b / a - 1 : (b - a) / n;
    }
    Arrays.sort(stats);
    return new double[] {Bootstrap.nearestRank(stats, 0.025), Bootstrap.nearestRank(stats, 0.975)};
  }

  /** All sets with the recommendation. */
  static Map<String, Object> model(List<Map<String, Object>> sets, long seed, int replicates) {
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("format", "argos-bench-noise/1");
    model.put("seed", seed);
    model.put("replicates", replicates);
    model.put("cvLimit", LoadReport.CV_LIMIT);
    model.put("sets", sets);
    boolean allReference = sets.stream().allMatch(s -> Boolean.TRUE.equals(s.get("reference")));
    model.put("referenceOnly", allReference);
    // What the current limit allows with the protocol's 3 rounds.
    double factor3 = Z_MDE * Math.sqrt(2.0 / 3);
    model.put("mdeAtCvLimitThreeRounds", factor3 * LoadReport.CV_LIMIT);
    model.put("cvForWarnStepThreeRounds", Regression.CPU_POINTS / factor3);
    model.put(
        "roundsForWarnStepAtCvLimit",
        Math.ceil(2 * Math.pow(Z_MDE * LoadReport.CV_LIMIT / Regression.CPU_POINTS, 2)));
    model.put(
        "recommendation",
        List.of(
            "Keep the off-run CPU CV limit at 2% (01 §7.3 item 4) as the validity gate of a set;"
                + " the constant stays until the PM decides 18 open item 3.",
            "Add an auxiliary condition for the 18 §7.7 regression check: the 95% CI half-width"
                + " of the relative CPU (t over the round pairs) must be <= 1.5 percentage points,"
                + " else the WARN step is below the resolution and the comparison says so (reported"
                + " only for now: publish prints the half-width in the regression notes).",
            "Recalibrate both after the first valid run on the dedicated machine: run `bench.sh"
                + " noise` on its sets; a shared machine gives reference values only."));
    return model;
  }

  static String markdown(Map<String, Object> model) {
    StringBuilder md = new StringBuilder("# Bench noise calibration (18 open item 3)\n\n");
    md.append("Generated by `test/bench/bench.sh noise`: off-run CPU CV (limit ")
        .append(pct(Json.number(model.get("cvLimit"))))
        .append(", 01 §7.3 item 4), off-vs-off (A-A) bootstrap 95% intervals (")
        .append(model.get("replicates"))
        .append(" replicates, seed ")
        .append(model.get("seed"))
        .append("), minimal detectable effect (MDE, two-sided 95%, power 80%) for the set's")
        .append(" number of off runs, and the agent comparison's own 95% CI half-width.\n\n");
    if (Boolean.TRUE.equals(model.get("referenceOnly"))) {
      md.append(
          "**Every input is INVALID (see the last column): all numbers are reference values"
              + " only, not a basis for the threshold.**\n\n");
    }
    md.append(
        "| set | off runs | off CPU CV | CV <= 2% | A-A relative CPU 95% | A-A half-width | A-A"
            + " p99 difference 95% (ms) | MDE relative CPU | MDE p99 ms | WARN step detectable"
            + " (CPU / p99) | CV for the WARN step | rounds for the WARN step | on/off relative"
            + " CPU CI half-width | input |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Object s : Json.array(model.get("sets"))) {
      List<Object> cpu = Json.array(Json.at(s, "aaRelativeCpuCi95"));
      List<Object> p99 = Json.array(Json.at(s, "aaP99DiffCi95"));
      List<Object> invalid = Json.array(Json.at(s, "invalidInputs"));
      md.append("| ")
          .append(Json.at(s, "set"))
          .append(" | ")
          .append(Json.at(s, "offRuns"))
          .append(" | ")
          .append(pct(Json.number(s, "offCpuCv")))
          .append(" | ")
          .append(Boolean.TRUE.equals(Json.at(s, "offCpuCvOk")) ? "yes" : "no")
          .append(" | ")
          .append(signedPct(Json.number(cpu.get(0))))
          .append(" .. ")
          .append(signedPct(Json.number(cpu.get(1))))
          .append(" | ")
          .append(pp(Json.number(s, "aaRelativeCpuHalfWidth")))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(p99.get(0)), 3))
          .append(" .. ")
          .append(LoadReport.fmt(Json.number(p99.get(1)), 3))
          .append(" | ")
          .append(pp(Json.number(s, "mdeRelativeCpu")))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(s, "mdeP99Ms"), 3))
          .append(" | ")
          .append(Boolean.TRUE.equals(Json.at(s, "warnCpuDetectable")) ? "yes" : "no")
          .append(" / ")
          .append(Boolean.TRUE.equals(Json.at(s, "warnP99Detectable")) ? "yes" : "no")
          .append(" | ")
          .append(pct(Json.number(s, "cvForWarnStep")))
          .append(" | ")
          .append(LoadReport.fmt(Json.number(s, "roundsForWarnStep"), 0))
          .append(" | ")
          .append(pp(Json.number(s, "pairRelativeCpuHalfWidth")))
          .append(" | ")
          .append(
              invalid.isEmpty()
                  ? "valid"
                  : "INVALID, reference only: "
                      + BenchReport.cell(
                          String.join("; ", invalid.stream().map(String::valueOf).toList())))
          .append(" |\n");
    }
    md.append("\n## What the 2% limit resolves\n\n");
    md.append("- With 3 rounds and an off-run CV of exactly 2%, the MDE of the relative CPU is ")
        .append(pp(Json.number(model.get("mdeAtCvLimitThreeRounds"))))
        .append(" (18 §7.7 WARN step: 1.50 percentage points).\n");
    md.append("- The WARN step is detectable with 3 rounds only at an off-run CV of ")
        .append(pct(Json.number(model.get("cvForWarnStepThreeRounds"))))
        .append(" or less; at CV 2% it needs ")
        .append(LoadReport.fmt(Json.number(model.get("roundsForWarnStepAtCvLimit")), 0))
        .append(" rounds.\n");
    md.append(
        "- The A-B design pairs every on run with the off run just before it, so slow drift"
            + " cancels and the pair interval (last numeric column) is usually narrower than the"
            + " independent-runs MDE; it is the number the auxiliary condition reads.\n\n");
    md.append("## Recommendation\n\n");
    for (Object r : Json.array(model.get("recommendation"))) {
      md.append("- ").append(r).append('\n');
    }
    return md.toString();
  }

  private static String pct(double v) {
    return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.2f%%", v * 100);
  }

  private static String signedPct(double v) {
    return LoadReport.pct(v);
  }

  private static String pp(double v) {
    return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.2f pp", v * 100);
  }
}
