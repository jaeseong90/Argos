/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.metric.MetricRuns.Point;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.sample.extstub.ExtStub;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * T-AG-062 end to end (F-A09, F-A10, D-16, 10 §11): every shop jar app of {@code -Pagent.e2e}
 * (shop-boot3@21 by default; shop-boot2@8 with {@code -Pagent.e2e=shop-boot2@8,shop-boot3@21}) with
 * the release agent over gRPC to a TLS {@link FakeCollector}, Spring Boot's defaults (JMX off:
 * {@code spring.jmx.enabled} false, no {@code com.sun.management.jmxremote}, HikariCP without
 * {@code registerMbeans}). The agent's {@code MetricBatch}es carry:
 *
 * <ul>
 *   <li>the JVM series ({@code jvm_memory_used_bytes{area="heap"}}, threads, classes, GC with
 *       labels of the name table, {@code process_cpu_usage_ratio} in [0, 1], {@code
 *       system_cpu_usage_ratio});
 *   <li>Tomcat ({@code tomcat_threads_busy|current|max{connector}}, {@code tomcat_connections_*})
 *       and the pool ({@code jdbc_pool_*{pool}} with {@code jdbc_pool_max} = SAMPLE_POOL_MAX 10,
 *       the configured value read from the pool's getter);
 *   <li>the agent's self metrics, the C-185 (4) registry counters among them;
 *   <li>every sample on the 5 s grid, at most 500 series per batch, counter deltas that add up to
 *       the counter's increase (C-15) and a Hello {@code cgroup} equal to the host's mode.
 * </ul>
 */
@Tag("req:F-A09")
@Tag("req:F-A10")
@Tag("req:D-16")
class MetricsE2eTest {
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  @TempDir Path work;

  static List<String> combos() {
    List<String> out = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      String combo = item.strip();
      if (combo.contains("@") && SHOP_JAR_APPS.contains(combo.substring(0, combo.indexOf('@')))) {
        out.add(combo);
      }
    }
    Assumptions.assumeFalse(out.isEmpty(), "no shop jar app in -Pagent.e2e, e.g. shop-boot3@21");
    return out;
  }

  @TestFactory
  @DisplayName(
      "T-AG-062 JVM, Tomcat and pool metrics of a real app with JMX off: MetricBatch every 5 s on"
          + " the grid, pool max = configured, <= 500 series, self and registry counters")
  Stream<DynamicTest> metrics() {
    return combos().stream()
        .map(
            combo ->
                DynamicTest.dynamicTest(
                    "T-AG-062 " + combo + ": agent metrics with JMX off", () -> check(combo)));
  }

  private void check(String combo) throws Exception {
    String app = combo.substring(0, combo.indexOf('@'));
    int jdk = Integer.parseInt(combo.substring(combo.indexOf('@') + 1));
    try (ExtStub ext =
            ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        FakeCollector collector = FakeCollector.builder().tls(true).start();
        RunningApp running =
            AppLauncher.of(SampleApp.named(app), Jdk.of(jdk))
                .withEnv("SAMPLE_EXT_URL", ext.baseUri().toString())
                .workDir(work.resolve(app + "-jdk" + jdk))
                .readyTimeout(Duration.ofMinutes(4))
                .withAgent(MetricRuns.releaseJar())
                .collector(collector)
                .start(Duration.ofMinutes(4))) {
      assertThat(running.command())
          .as("JMX stays off (Spring Boot default, no remote JMX)")
          .noneMatch(arg -> arg.contains("jmxremote") || arg.contains("spring.jmx.enabled=true"));
      for (int i = 0; i < 20; i++) {
        assertThat(running.get("/api/orders/1").status()).isEqualTo(200);
      }
      long begin = System.nanoTime();
      // Two batches after the requests: the pool exists and has served connections.
      int before = collector.batches(BatchKind.METRICS).size();
      collector.awaitBatches(BatchKind.METRICS, before + 2, Duration.ofSeconds(40));
      List<MetricBatch> batches = MetricRuns.batches(collector);
      System.out.println(
          "[wo16-metrics] "
              + combo
              + ": "
              + batches.size()
              + " metric batches, waited "
              + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
              + " ms, series per batch "
              + batches.stream().map(MetricBatch::getSeriesCount).collect(Collectors.toList()));
      assertThat(batches.size())
          .as("metric batches of %s", combo)
          .isGreaterThanOrEqualTo(before + 2);
      for (MetricBatch batch : batches) {
        assertThat(batch.getSeriesCount()).isBetween(1, 500);
      }
      List<Point> points = MetricRuns.points(collector);
      assertThat(points).allMatch(p -> p.tsMs() % 5_000 == 0, "every sample on the 5 s grid");
      Map<String, Point> last = MetricRuns.latest(points);
      Set<String> bases =
          last.values().stream().map(Point::base).collect(Collectors.toCollection(TreeSet::new));
      System.out.println("[wo16-metrics] " + combo + " families: " + bases);

      assertThat(last.get("jvm_memory_used_bytes{area=\"heap\"}").value()).isPositive();
      assertThat(last.get("jvm_memory_max_bytes{area=\"heap\"}").value()).isPositive();
      assertThat(last.get("jvm_threads_live").value()).isGreaterThan(10);
      assertThat(last.get("jvm_classes_loaded").value()).isGreaterThan(1000);
      assertThat(last.get("jvm_uptime_seconds").value()).isPositive();
      assertThat(last.get("process_cpu_usage_ratio").value()).isBetween(0.0, 1.0);
      assertThat(last.get("system_cpu_usage_ratio").value()).isBetween(0.0, 1.0);
      assertThat(bases)
          .contains(
              "jvm_memory_pool_used_bytes",
              "jvm_buffer_memory_used_bytes",
              "jvm_gc_collection_count_total",
              "jvm_gc_collection_seconds_total",
              "jvm_gc_pause_seconds_total",
              "jvm_classes_unloaded_total");
      assertThat(last.values().stream().filter(p -> p.base().startsWith("jvm_gc_collection")))
          .as("GC labels from the name table (default collector of the JDK)")
          .allMatch(p -> !"unknown".equals(p.label("gc_type")));

      Point threadsMax = only(last, "tomcat_threads_max");
      assertThat(threadsMax.label("connector")).startsWith("http-nio-");
      assertThat(threadsMax.value()).isEqualTo(200);
      assertThat(only(last, "tomcat_threads_busy").value()).isBetween(0.0, 200.0);
      assertThat(only(last, "tomcat_threads_current").value()).isPositive();
      assertThat(only(last, "tomcat_connections_max").value()).isPositive();
      assertThat(bases).contains("tomcat_connections_current");

      Point poolMax = only(last, "jdbc_pool_max");
      assertThat(poolMax.label("pool")).startsWith("HikariPool-");
      assertThat(poolMax.value()).as("SAMPLE_POOL_MAX (default 10)").isEqualTo(10);
      assertThat(only(last, "jdbc_pool_active").value()).isBetween(0.0, 10.0);
      assertThat(only(last, "jdbc_pool_idle").value()).isBetween(0.0, 10.0);
      assertThat(only(last, "jdbc_pool_waiting").value()).isGreaterThanOrEqualTo(0.0);

      assertThat(last)
          .containsKeys(
              "argos_agent_registry_overflow_total{registry=\"pool\"}",
              "argos_agent_registry_getter_errors_total{registry=\"pool\"}",
              "argos_agent_registry_overflow_total{registry=\"tomcat\"}",
              "argos_agent_registry_getter_errors_total{registry=\"tomcat\"}",
              "argos_agent_dropped_total{queue=\"transaction\"}",
              "argos_agent_queue_size{queue=\"metric\"}",
              "argos_agent_metric_series_dropped_total");
      assertThat(last.get("argos_agent_registry_getter_errors_total{registry=\"pool\"}").value())
          .isZero();
      assertThat(last.get("argos_agent_metric_series_dropped_total").value()).isZero();
      assertThat(bases).contains("argos_agent_cpu_seconds_total", "argos_agent_batches_sent_total");

      counterDeltasAddUp(points);

      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).isNotEmpty();
      assertThat(((AgentHello) sessions.get(0).hello()).getCgroup())
          .as("Hello cgroup = the host's mode")
          .isEqualTo(HostCgroup.mode());
    }
  }

  /** The single series of {@code base}. */
  static Point only(Map<String, Point> last, String base) {
    List<Point> matches = last.values().stream().filter(p -> p.base().equals(base)).toList();
    assertThat(matches).as("series of %s", base).hasSize(1);
    return matches.get(0);
  }

  /**
   * C-15: for every counter series, each delta after the first sample is the increase since the
   * previous sample (no reset in a running app), and gauges carry delta 0.
   */
  static void counterDeltasAddUp(List<Point> points) {
    // Up to 4 batches are in flight at once (11 §6), so the collector may receive them out of
    // order (the batches queued before the session are sent together): compare in sample order.
    List<Point> ordered = new ArrayList<>(points);
    ordered.sort(java.util.Comparator.comparingLong(Point::tsMs));
    Map<String, Point> previous = new LinkedHashMap<>();
    int checked = 0;
    for (Point p : ordered) {
      if (p.kind() == MetricKind.GAUGE) {
        assertThat(p.delta()).as("gauge delta of %s", p.series()).isZero();
        continue;
      }
      Point before = previous.put(p.series(), p);
      if (before == null) {
        assertThat(p.delta()).as("first delta of %s", p.series()).isEqualTo(p.value());
      } else if (p.value() >= before.value()) {
        assertThat(p.delta())
            .as("delta of %s", p.series())
            .isCloseTo(p.value() - before.value(), org.assertj.core.data.Offset.offset(1e-6));
        checked++;
      }
    }
    assertThat(checked).as("counter samples after a first one").isPositive();
  }
}
