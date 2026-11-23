/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.metric.MetricRuns.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-ACC-25 (D-25, F-A09, 10 §11 GC name table) and the 10 U-14 check (generational ZGC MXBean
 * names): the trivial app under every collector of every JDK of {@code -Pagent.gc.jdks} (default:
 * the attach JDKs) with the release agent and a {@link FakeCollector}: G1, Parallel and Serial on
 * every JDK, CMS on 8 and 11, Shenandoah where the JDK build has it (skipped with the reason
 * otherwise), single-generation ZGC on 17 and 21, generational ZGC on 21 ({@code
 * -XX:+ZGenerational}) and 25 (the only ZGC). The {@code gc}/{@code gc_type} labels must be the
 * table's, nothing may be {@code unknown}, and {@code jvm_gc_pause_seconds_total} must have exactly
 * the pause collectors (never a ZGC or Shenandoah cycle, never G1's or CMS's concurrent time).
 *
 * <p>The raw MXBean names of each JVM (on JDK 11+, from a single-file program run with the same
 * flags) are printed as {@code [wo16-gc]} lines with the agent's labels: the U-14 record.
 */
@Tag("req:D-25")
@Tag("req:F-A09")
class GcLabelsTest {
  static final String TRIVIAL_MAIN = "example.trivial.TrivialApp";

  /** Time the trivial app stays alive: a session plus at least two 5 s grid points. */
  static final String STAY_ALIVE_MILLIS = "17000";

  @TempDir Path work;

  /**
   * One collector configuration.
   *
   * @param name short name
   * @param flags JVM flags
   * @param labels expected {@code gc/gc_type} pairs of the collection series
   * @param pauses expected {@code gc} labels of {@code jvm_gc_pause_seconds_total}
   */
  record GcCase(String name, List<String> flags, Set<String> labels, Set<String> pauses) {}

  static List<Integer> jdks() {
    return Arrays.stream(System.getProperty("argos.test.gc.jdks", "8,21").split(",", -1))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Integer::parseInt)
        .toList();
  }

  static List<GcCase> cases(int jdk) {
    List<GcCase> out = new ArrayList<>();
    Set<String> g1 =
        jdk >= 20 ? set("g1/young", "g1/old", "g1/concurrent") : set("g1/young", "g1/old");
    out.add(new GcCase("G1", List.of("-XX:+UseG1GC"), g1, set("g1")));
    out.add(
        new GcCase(
            "Parallel",
            List.of("-XX:+UseParallelGC"),
            set("parallel/young", "parallel/old"),
            set("parallel")));
    out.add(
        new GcCase(
            "Serial",
            List.of("-XX:+UseSerialGC"),
            set("serial/young", "serial/old"),
            set("serial")));
    if (jdk <= 11) {
      out.add(
          new GcCase(
              "CMS", List.of("-XX:+UseConcMarkSweepGC"), set("cms/young", "cms/old"), set("cms")));
    }
    out.add(
        new GcCase(
            "Shenandoah",
            List.of("-XX:+UseShenandoahGC"),
            set("shenandoah/pause", "shenandoah/cycle"),
            set("shenandoah")));
    if (jdk == 17 || jdk == 21) {
      out.add(new GcCase("ZGC", List.of("-XX:+UseZGC"), set("zgc/pause", "zgc/cycle"), set("zgc")));
    }
    if (jdk == 21) {
      out.add(
          new GcCase(
              "ZGC generational",
              List.of("-XX:+UseZGC", "-XX:+ZGenerational"),
              set("zgc/pause", "zgc/cycle"),
              set("zgc")));
    }
    if (jdk >= 24) {
      out.add(
          new GcCase(
              "ZGC generational",
              List.of("-XX:+UseZGC"),
              set("zgc/pause", "zgc/cycle"),
              set("zgc")));
    }
    return out;
  }

  static Set<String> set(String... values) {
    return new TreeSet<>(Arrays.asList(values));
  }

  @TestFactory
  @DisplayName(
      "T-ACC-25 GC labels under G1, Parallel, Serial, CMS, Shenandoah and ZGC (single and"
          + " generational): the name table's gc/gc_type, pauses only in jvm_gc_pause_seconds_total"
          + " (U-14 MXBean names as [wo16-gc])")
  Stream<DynamicTest> gcLabels() {
    List<DynamicTest> tests = new ArrayList<>();
    for (int jdk : jdks()) {
      for (GcCase gc : cases(jdk)) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-ACC-25 JDK " + jdk + " " + gc.name() + " " + String.join(" ", gc.flags()),
                () -> check(jdk, gc)));
      }
    }
    return tests.stream();
  }

  private void check(int jdk, GcCase gc) throws Exception {
    Jdk java = Jdk.of(jdk);
    Assumptions.assumeTrue(
        supported(java, gc.flags()),
        "JDK "
            + jdk
            + " ("
            + java.java()
            + ") does not start with "
            + gc.flags()
            + ": this build lacks the collector");
    List<String> raw = rawNames(java, gc.flags());
    Path dir = work.resolve("jdk" + jdk + "-" + gc.name().replace(' ', '-'));
    long begin = System.nanoTime();
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(
                  SampleApp.mainClass(
                      "trivial", TRIVIAL_MAIN, MetricRuns.required("argos.test.trivialAppClasses")),
                  java)
              .withAgent(MetricRuns.releaseJar())
              .collector(collector)
              .withJvmOptions(gc.flags().toArray(new String[0]))
              .args(dir.resolve("out").resolve("results.properties").toString(), STAY_ALIVE_MILLIS)
              .workDir(dir);
      ExitedApp run = launcher.runToExit(Duration.ofMinutes(2));
      assertThat(run.timedOut()).as(run.describe()).isFalse();
      assertThat(run.exitCode()).as(run.describe()).isZero();
      List<Point> points = MetricRuns.points(collector);
      Map<String, Point> last = MetricRuns.latest(points);
      Set<String> labels = new TreeSet<>();
      Set<String> pauses = new TreeSet<>();
      for (Point p : last.values()) {
        if (p.base().equals("jvm_gc_collection_count_total")) {
          labels.add(p.label("gc") + "/" + p.label("gc_type"));
        } else if (p.base().equals("jvm_gc_pause_seconds_total")) {
          pauses.add(p.label("gc"));
        }
      }
      System.out.println(
          "[wo16-gc] JDK "
              + jdk
              + " "
              + gc.name()
              + " flags="
              + gc.flags()
              + " mxbeans="
              + (raw == null ? "(JDK 8: no single-file launch; see labels)" : raw)
              + " labels="
              + labels
              + " pause="
              + pauses
              + " batches="
              + MetricRuns.batches(collector).size()
              + " in "
              + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
              + " ms");
      assertThat(points).as("metric samples of %s", run.describe()).isNotEmpty();
      assertThat(labels).as("gc/gc_type labels").isEqualTo(gc.labels());
      assertThat(pauses).as("gc labels of jvm_gc_pause_seconds_total").isEqualTo(gc.pauses());
      assertThat(last.keySet())
          .as("no cycle or concurrent series in the pause metric")
          .noneMatch(
              name -> name.startsWith("jvm_gc_pause_seconds_total") && name.contains("gc_type"));
      if (raw != null && gc.name().equals("ZGC generational")) {
        assertThat(raw)
            .as("U-14: generational ZGC MXBean names")
            .containsExactlyInAnyOrder(
                "ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses");
      }
    }
  }

  /** True when {@code java <flags> -version} exits 0. */
  static boolean supported(Jdk java, List<String> flags) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(java.java().toString());
    command.addAll(flags);
    command.add("-version");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
  }

  /**
   * The GarbageCollectorMXBean names of a JVM with {@code flags} (a single-file program, JDK 11+);
   * null on JDK 8.
   */
  List<String> rawNames(Jdk java, List<String> flags) throws IOException, InterruptedException {
    if (java.feature() < 11) {
      return null;
    }
    Path source = work.resolve("GcNames.java");
    if (!Files.exists(source)) {
      Files.writeString(
          source,
          """
          public class GcNames {
            public static void main(String[] args) {
              for (java.lang.management.GarbageCollectorMXBean gc :
                  java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                System.out.println("gc-name=" + gc.getName());
              }
            }
          }
          """,
          StandardCharsets.UTF_8);
    }
    List<String> command = new ArrayList<>();
    command.add(java.java().toString());
    command.addAll(flags);
    command.add(source.toString());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(2, TimeUnit.MINUTES)).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    return output
        .lines()
        .filter(line -> line.startsWith("gc-name="))
        .map(line -> line.substring("gc-name=".length()).strip())
        .collect(Collectors.toList());
  }
}
