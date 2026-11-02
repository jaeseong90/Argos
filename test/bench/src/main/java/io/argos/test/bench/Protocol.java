/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fixed parameters of the 01 §7.3 protocol and the deviations of a set from them. A set that
 * deviates (shortened windows for a smoke run, fewer rounds, the load generator on the app machine,
 * ...) still yields numbers, but no PASS or FAIL: the report judges its requirements INVALID.
 */
final class Protocol {
  private Protocol() {}

  /** 01 §7.3 item 4: three off and three on runs. */
  static final int ROUNDS = 3;

  /** 01 §7.3 item 4 and 18 §7.2: 3 minutes of warm-up excluded. */
  static final int WARMUP_SECONDS = 180;

  /** 01 §7.3 item 4: 10 minutes measured. */
  static final int WINDOW_SECONDS = 600;

  /** 01 §7.3 item 3: 1,000 requests per second, constant arrival rate. */
  static final int RATE = 1000;

  /** 01 §7.3 item 2 (identical for off and on). */
  static final List<String> LOAD_JVM_OPTIONS =
      List.of("-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch");

  /** 01 §7.3 item 8: median of 10 starts; both CDS modes (N-04); the heavy app (N-04, N-27). */
  static final int STARTUP_RUNS = 10;

  static final String HEAVY_APP = StartupReport.HEAVY_APP;

  /** Deviations of a set (set.json) from the protocol; empty when it follows it. */
  static List<String> deviations(Map<String, Object> set) {
    Map<String, Object> p = Json.object(set.get("parameters"));
    List<String> out = new ArrayList<>();
    if ("startup".equals(set.get("kind"))) {
      double runs = Json.number(p.get("runsPerVariant"));
      if (!(runs >= STARTUP_RUNS)) {
        out.add(String.format(Locale.ROOT, "%s starts per variant (protocol %d)", n(runs), 10));
      }
      List<Object> apps = Json.array(p.get("apps"));
      if (!apps.contains(HEAVY_APP)) {
        out.add("no " + HEAVY_APP + " starts (N-04 heavy app, N-27)");
      }
      List<Object> cds = Json.array(p.get("cds"));
      if (!cds.contains("default") || !cds.contains("off")) {
        out.add("CDS modes " + cds + " (protocol: default and off)");
      }
      List<Object> jvm = Json.array(p.get("jvmOptions"));
      for (String option : List.of("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")) {
        if (!jvm.contains(option)) {
          out.add("JVM options without " + option + " (01 §7.3 item 2)");
        }
      }
      return out;
    }
    double rounds = Json.number(p.get("rounds"));
    if (!(rounds >= ROUNDS)) {
      out.add(String.format(Locale.ROOT, "%s rounds (protocol %d)", n(rounds), ROUNDS));
    }
    double warmup = Json.number(p.get("warmupSeconds"));
    if (!(warmup >= WARMUP_SECONDS)) {
      out.add(String.format(Locale.ROOT, "warm-up %s s (protocol 180 s)", n(warmup)));
    }
    double window = Json.number(p.get("durationSeconds"));
    if (!(window >= WINDOW_SECONDS)) {
      out.add(String.format(Locale.ROOT, "window %s s (protocol 600 s)", n(window)));
    }
    double rate = Json.number(p.get("rate"));
    if (rate != RATE) {
      out.add(String.format(Locale.ROOT, "rate %s/s (protocol 1,000/s)", n(rate)));
    }
    List<Object> jvm = Json.array(p.get("jvmOptions"));
    for (String option : LOAD_JVM_OPTIONS) {
      if (!jvm.contains(option)) {
        out.add("JVM options without " + option + " (01 §7.3 item 2)");
      }
    }
    if ("container".equals(p.get("mode"))
        && (Json.number(p.get("cpus")) != 2 || !"4g".equals(p.get("memory")))) {
      out.add(
          "container limits --cpus "
              + p.get("cpus")
              + " --memory "
              + p.get("memory")
              + " (01 §7.3 item 9: --cpus=2 --memory=4g)");
    }
    if ("process".equals(p.get("mode")) && p.get("k6Ssh") == null) {
      out.add(
          "load generator on the app machine (01 §7.3 item 1: k6 on another machine, --k6-ssh)");
    }
    if (p.get("exporter") == null) {
      out.add("exporter not recorded (set made before WO-05)");
    }
    return out;
  }

  /**
   * Notes about a set that do not invalidate it: the container set's k6 and ext-stub run on the
   * same host, outside the app container's CPUs (docs/poc/agent-overhead.md 9.3 step 4).
   */
  static List<String> notes(Map<String, Object> set) {
    Map<String, Object> p = Json.object(set.get("parameters"));
    List<String> out = new ArrayList<>();
    if ("container".equals(p.get("mode"))) {
      out.add(
          "container mode: k6 and ext-stub run in containers on the same host, outside the app"
              + " container's CPU limit (01 §7.3 item 1 deviation accepted for item 9)");
    }
    return out;
  }

  private static String n(double v) {
    return Double.isNaN(v)
        ? "n/a"
        : v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
  }
}
