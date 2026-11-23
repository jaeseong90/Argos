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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-063 in a real container (F-A11, 10 §11): the first jar app of {@code -Pagent.e2e} with the
 * agent in the launcher's Linux container ({@code eclipse-temurin}, pinned by digest) under {@code
 * --cpus 1.5 --memory 512m}, Docker's cgroup v2 (Docker Desktop and current Linux hosts). The agent
 * reports {@code cgroup=v2} in its Hello, {@code container_cpu_limit_cores} 1.5, {@code
 * container_memory_limit_bytes} 512 MiB and {@code system_memory_total_bytes} equal to the limit,
 * and a {@code system_cpu_usage_ratio} measured against the limit. Needs Docker; runs with {@code
 * -Pagent.container=true}. A real cgroup v1 host is a matrix run (WO-50, 사람 확인 필요); v1 parsing is
 * {@code CgroupReaderTest} over fixtures.
 */
@Tag("req:F-A11")
class CgroupContainerTest {
  static final double LIMIT_BYTES = 512.0 * 1024 * 1024;

  @TempDir Path work;

  @Test
  @DisplayName(
      "T-AG-063 container --cpus 1.5 --memory 512m: Hello cgroup=v2, container_cpu_limit_cores"
          + " 1.5, container_memory_limit_bytes 512 MiB, system_memory_total_bytes = the limit")
  void limits() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true (Docker)");
    String combo =
        Arrays.stream(System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1))
            .map(String::strip)
            .filter(item -> item.contains("@"))
            .filter(item -> !SampleApp.named(item.substring(0, item.indexOf('@'))).isWar())
            .findFirst()
            .orElse(null);
    Assumptions.assumeTrue(combo != null, "container mode needs a jar app in -Pagent.e2e");
    String app = combo.substring(0, combo.indexOf('@'));
    int jdk = Integer.parseInt(combo.substring(combo.indexOf('@') + 1));
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).bindAddress("0.0.0.0").start();
        RunningApp running =
            AppLauncher.of(SampleApp.named(app), Jdk.of(jdk))
                .withAgent(MetricRuns.releaseJar())
                .collector(collector)
                .container(true, "1.5", "512m")
                .workDir(work.resolve("cgroup-v2"))
                .start(Duration.ofMinutes(5))) {
      assertThat(running.get("/api/orders/1").status()).isEqualTo(200);
      int before = collector.batches(BatchKind.METRICS).size();
      collector.awaitBatches(BatchKind.METRICS, before + 2, Duration.ofSeconds(45));
      List<Point> points = MetricRuns.points(collector);
      Map<String, Point> last = MetricRuns.latest(points);
      System.out.println(
          "[wo16-cgroup] "
              + combo
              + " container_cpu_limit_cores="
              + value(last, "container_cpu_limit_cores")
              + " container_memory_limit_bytes="
              + value(last, "container_memory_limit_bytes")
              + " system_memory_total_bytes="
              + value(last, "system_memory_total_bytes")
              + " system_memory_used_bytes="
              + value(last, "system_memory_used_bytes")
              + " system_cpu_usage_ratio="
              + value(last, "system_cpu_usage_ratio")
              + " throttled="
              + value(last, "container_cpu_throttled_seconds_total"));

      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).isNotEmpty();
      assertThat(((AgentHello) sessions.get(0).hello()).getCgroup()).isEqualTo("v2");
      assertThat(last.get("container_cpu_limit_cores").value()).isEqualTo(1.5);
      assertThat(last.get("container_memory_limit_bytes").value()).isEqualTo(LIMIT_BYTES);
      assertThat(last.get("system_memory_total_bytes").value()).isEqualTo(LIMIT_BYTES);
      assertThat(last.get("system_memory_used_bytes").value()).isBetween(1.0, LIMIT_BYTES);
      assertThat(last.get("system_cpu_usage_ratio").value()).isBetween(0.0, 1.0);
      assertThat(last).containsKey("container_cpu_throttled_seconds_total");
      assertThat(last.keySet())
          .anyMatch(name -> name.startsWith("system_disk_total_bytes{mount=\"/\"}"));
      assertThat(last.keySet())
          .anyMatch(name -> name.startsWith("system_network_receive_bytes_total{"));
    }
  }

  private static Object value(Map<String, Point> last, String series) {
    Point p = last.get(series);
    return p == null ? "missing" : p.value();
  }
}
