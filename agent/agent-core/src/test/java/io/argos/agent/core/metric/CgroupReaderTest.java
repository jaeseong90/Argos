/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-063 (F-A11, 10 §11 "시스템(Linux)"): {@link CgroupReader} and the system series of {@link
 * SystemMetrics} over fixture roots ({@code src/test/resources/cgroup/<case>} standing for {@code
 * /}): cgroup v2 with limits, v2 with the limit on a parent group, v2 without limits ({@code max}),
 * v1 with limits, v1 without limits (the kernel's page-rounded LONG_MAX, quota -1) and a host
 * without any cgroup file system. A real container with cgroup v2 is {@code CgroupContainerTest}
 * (agent-testing, {@code -Pagent.container=true}); a real cgroup v1 host is a matrix run (WO-50).
 */
@Tag("req:F-A11")
@Tag("req:T-AG-063")
class CgroupReaderTest {
  private static final double MIB = 1024.0 * 1024.0;

  static Path fixture(String name) {
    URL url = CgroupReaderTest.class.getResource("/cgroup/" + name);
    assertThat(url).as("fixture %s", name).isNotNull();
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Values of one batch by series name. */
  static Map<String, MetricSample> byName(MetricBatch batch) {
    Map<Integer, String> names = new HashMap<Integer, String>();
    for (MetricSeries s : batch.getSeriesList()) {
      names.put(s.getIndex(), s.getName());
    }
    Map<String, MetricSample> out = new HashMap<String, MetricSample>();
    for (MetricSample sample : batch.getSamplesList()) {
      out.put(names.get(sample.getSeriesIndex()), sample);
    }
    return out;
  }

  private static MetricBatch system(Path root, OsBean os, long nanos) {
    MetricRegistry registry = new MetricRegistry(500);
    new SystemMetrics(root, true, new CgroupReader(root), os).collect(registry, nanos);
    return registry.drain(1_700_000_000_000L);
  }

  @Test
  @DisplayName("T-AG-063 cgroup v2: cpu.max 150000/100000 = 1.5 cores, memory.max 512 MiB, usage")
  void v2() {
    CgroupReader reader = new CgroupReader(fixture("v2"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.V2);
    assertThat(reader.cpuLimitCores()).isEqualTo(1.5);
    assertThat(reader.memoryLimitBytes()).isEqualTo(512L * 1024 * 1024);
    assertThat(reader.memoryUsageBytes()).isEqualTo(100L * 1024 * 1024);
    assertThat(reader.cpuUsageNanos()).isEqualTo(2_000_000_000L);
    assertThat(reader.throttledNanos()).isEqualTo(250_000_000L);
  }

  @Test
  @DisplayName(
      "T-AG-063 cgroup v2 nested: the process group from /proc/self/cgroup, the smallest limit up"
          + " the hierarchy (parent cpu.max 2 cores, own memory.max 256 MiB)")
  void v2Nested() {
    CgroupReader reader = new CgroupReader(fixture("v2-nested"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.V2);
    assertThat(reader.cpuLimitCores()).isEqualTo(2.0);
    assertThat(reader.memoryLimitBytes()).isEqualTo(256L * 1024 * 1024);
    assertThat(reader.memoryUsageBytes()).isEqualTo(50L * 1024 * 1024);
    assertThat(reader.cpuUsageNanos()).isEqualTo(500_000_000L);
  }

  @Test
  @DisplayName("T-AG-063 cgroup v2 without limits: cpu.max 'max', memory.max 'max' are no limit")
  void v2Unlimited() {
    CgroupReader reader = new CgroupReader(fixture("v2-unlimited"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.V2);
    assertThat(reader.cpuLimitCores()).isNaN();
    assertThat(reader.memoryLimitBytes()).isEqualTo(-1);
    assertThat(reader.memoryUsageBytes()).isEqualTo(70L * 1024 * 1024);
    assertThat(reader.cpuUsageNanos()).isEqualTo(900_000_000L);
  }

  @Test
  @DisplayName(
      "T-AG-063 cgroup v1: cfs_quota 50000/period 100000 = 0.5 cores, limit_in_bytes 1 GiB,"
          + " cpuacct.usage, throttled_time (joined cpu,cpuacct directory)")
  void v1() {
    CgroupReader reader = new CgroupReader(fixture("v1"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.V1);
    assertThat(reader.cpuLimitCores()).isEqualTo(0.5);
    assertThat(reader.memoryLimitBytes()).isEqualTo(1024L * 1024 * 1024);
    assertThat(reader.memoryUsageBytes()).isEqualTo(200L * 1024 * 1024);
    assertThat(reader.cpuUsageNanos()).isEqualTo(3_000_000_000L);
    assertThat(reader.throttledNanos()).isEqualTo(120_000_000L);
  }

  @Test
  @DisplayName(
      "T-AG-063 cgroup v1 without limits: quota -1 and limit_in_bytes 9223372036854771712 are no"
          + " limit")
  void v1Unlimited() {
    CgroupReader reader = new CgroupReader(fixture("v1-unlimited"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.V1);
    assertThat(reader.cpuLimitCores()).isNaN();
    assertThat(reader.memoryLimitBytes()).isEqualTo(-1);
    assertThat(reader.memoryUsageBytes()).isEqualTo(300L * 1024 * 1024);
    assertThat(reader.cpuUsageNanos()).isEqualTo(7_000_000_000L);
    assertThat(reader.throttledNanos()).as("no cpu.stat").isEqualTo(-1);
  }

  @Test
  @DisplayName("T-AG-063 no cgroup file system: mode none, every value missing")
  void none() {
    CgroupReader reader = new CgroupReader(fixture("none"));
    assertThat(reader.mode()).isEqualTo(CgroupReader.NONE);
    assertThat(reader.cpuLimitCores()).isNaN();
    assertThat(reader.memoryLimitBytes()).isEqualTo(-1);
    assertThat(reader.memoryUsageBytes()).isEqualTo(-1);
    assertThat(reader.cpuUsageNanos()).isEqualTo(-1);
    assertThat(reader.throttledNanos()).isEqualTo(-1);
  }

  @Test
  @DisplayName(
      "T-AG-063 container series under a v2 limit: container_cpu_limit_cores 1.5,"
          + " container_memory_limit_bytes 512 MiB, system_memory_total_bytes = the limit,"
          + " used = the working set (memory.current 100 MiB - inactive_file 30 MiB, C-229 (8)),"
          + " throttled seconds; disks and network from /proc")
  void containerSeries() {
    Map<String, MetricSample> v =
        byName(system(fixture("v2"), OsBean.fixed(-1, -1, 0.9, -1, -1), 1_000_000_000L));
    assertThat(v.get("container_cpu_limit_cores").getValue()).isEqualTo(1.5);
    assertThat(v.get("container_memory_limit_bytes").getValue()).isEqualTo(512 * MIB);
    assertThat(v.get("system_memory_total_bytes").getValue()).isEqualTo(512 * MIB);
    assertThat(v.get("system_memory_used_bytes").getValue()).isEqualTo(70 * MIB);
    assertThat(v.get("container_cpu_throttled_seconds_total").getValue()).isEqualTo(0.25);
    assertThat(v)
        .as("first collection under a CPU limit: no ratio yet (needs two cgroup samples)")
        .doesNotContainKey("system_cpu_usage_ratio");
    assertThat(v.get("system_network_receive_bytes_total{iface=\"eth0\"}").getValue())
        .isEqualTo(9876543);
    assertThat(v.get("system_network_transmit_bytes_total{iface=\"eth0\"}").getValue())
        .isEqualTo(1234567);
    assertThat(v).doesNotContainKey("system_network_receive_bytes_total{iface=\"lo\"}");
    assertThat(v.get("system_disk_total_bytes{mount=\"/\"}").getValue()).isPositive();
    assertThat(v.get("system_disk_total_bytes{mount=\"/data\"}").getValue()).isPositive();
    assertThat(v.get("system_disk_used_bytes{mount=\"/\"}").getValue()).isNotNegative();
    assertThat(v.keySet())
        .as("pseudo, network and bind-mounted file entries are no disks")
        .noneMatch(
            name -> name.contains("/proc") || name.contains("nfs") || name.contains("hosts"));
  }

  @Test
  @DisplayName(
      "T-AG-063 system_cpu_usage_ratio under a cgroup limit: group CPU time over wall time and the"
          + " limit (0.75 s of CPU in 1 s with 1.5 cores = 0.5), one series")
  void cpuRatioUnderLimit(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("root");
    copyTree(fixture("v2"), root);
    MetricRegistry registry = new MetricRegistry(500);
    SystemMetrics metrics =
        new SystemMetrics(root, true, new CgroupReader(root), OsBean.fixed(-1, -1, 0.9, -1, -1));
    metrics.collect(registry, 1_000_000_000L);
    registry.drain(0);
    Path stat = root.resolve("sys/fs/cgroup/cpu.stat");
    Files.write(
        stat, "usage_usec 2750000\nthrottled_usec 250000\n".getBytes(StandardCharsets.UTF_8));
    metrics.collect(registry, 2_000_000_000L);
    Map<String, MetricSample> v = byName(registry.drain(5_000));
    assertThat(v.get("system_cpu_usage_ratio").getValue()).isCloseTo(0.5, within(1e-9));
  }

  @Test
  @DisplayName(
      "T-AG-063 without a limit: host values (system_cpu_usage_ratio from the OS bean,"
          + " /proc/meminfo total and used), no container_* limit series")
  void hostValues() {
    Map<String, MetricSample> unlimited =
        byName(system(fixture("v2-unlimited"), OsBean.fixed(-1, -1, 0.25, -1, -1), 1L));
    assertThat(unlimited.get("system_cpu_usage_ratio").getValue()).isEqualTo(0.25);
    assertThat(unlimited.get("system_memory_total_bytes").getValue()).isEqualTo(8_000_000 * 1024.0);
    assertThat(unlimited.get("system_memory_used_bytes").getValue()).isEqualTo(5_000_000 * 1024.0);
    assertThat(unlimited)
        .doesNotContainKeys("container_cpu_limit_cores", "container_memory_limit_bytes");

    Map<String, MetricSample> none =
        byName(system(fixture("none"), OsBean.fixed(-1, -1, 0.4, -1, -1), 1L));
    assertThat(none.get("system_cpu_usage_ratio").getValue()).isEqualTo(0.4);
    assertThat(none.get("system_memory_total_bytes").getValue()).isEqualTo(4_000_000 * 1024.0);
    assertThat(none.get("system_memory_used_bytes").getValue())
        .as("no MemAvailable: MemFree + Buffers + Cached")
        .isEqualTo((4_000_000 - 1_000_000) * 1024.0);
    assertThat(none.keySet()).noneMatch(name -> name.startsWith("container_"));
    assertThat(none.get("system_network_receive_bytes_total{iface=\"wlan0\"}").getValue())
        .isEqualTo(5000);
  }

  @Test
  @DisplayName(
      "T-AG-063 not Linux (Windows, macOS): only system_cpu_usage_ratio from the OS bean, no /proc"
          + " series")
  void notLinux() {
    MetricRegistry registry = new MetricRegistry(500);
    new SystemMetrics(
            fixture("v2"),
            false,
            new CgroupReader(fixture("none")),
            OsBean.fixed(-1, -1, 0.3, -1, -1))
        .collect(registry, 1L);
    Map<String, MetricSample> v = byName(registry.drain(0));
    assertThat(v.get("system_cpu_usage_ratio").getValue()).isEqualTo(0.3);
    assertThat(v.keySet()).containsOnly("system_cpu_usage_ratio", MetricRegistry.SERIES_DROPPED);
  }

  @Test
  @DisplayName(
      "T-AG-063 working set (C-229 (8)): v2 memory.current - memory.stat inactive_file, v1"
          + " usage_in_bytes - total_inactive_file; without memory.stat the usage; 0 when the"
          + " cache exceeds the usage")
  void workingSet(@TempDir Path dir) throws Exception {
    assertThat(new CgroupReader(fixture("v2")).memoryWorkingSetBytes())
        .isEqualTo((100L - 30L) * 1024 * 1024);
    assertThat(new CgroupReader(fixture("v1")).memoryWorkingSetBytes())
        .as("total_inactive_file (hierarchy), not the group's own inactive_file")
        .isEqualTo((200L - 50L) * 1024 * 1024);
    assertThat(new CgroupReader(fixture("v2-nested")).memoryWorkingSetBytes())
        .as("no memory.stat")
        .isEqualTo(50L * 1024 * 1024);
    assertThat(new CgroupReader(fixture("none")).memoryWorkingSetBytes()).isEqualTo(-1);

    Path root = dir.resolve("root");
    copyTree(fixture("v2"), root);
    Files.write(
        root.resolve("sys/fs/cgroup/memory.stat"),
        "inactive_file 209715200\n".getBytes(StandardCharsets.UTF_8));
    assertThat(new CgroupReader(root).memoryWorkingSetBytes()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-063 v1 container series: system_memory_used_bytes is the working set (200 - 50 MiB),"
          + " total the 1 GiB limit (C-229 (8))")
  void v1WorkingSetSeries() {
    Map<String, MetricSample> v =
        byName(system(fixture("v1"), OsBean.fixed(-1, -1, 0.9, -1, -1), 1_000_000_000L));
    assertThat(v.get("system_memory_total_bytes").getValue()).isEqualTo(1024 * MIB);
    assertThat(v.get("system_memory_used_bytes").getValue()).isEqualTo(150 * MIB);
  }

  @Test
  @DisplayName(
      "T-AG-063 mount labels name no user (C-229 (9)): /home/<name>, /Users/<name> and"
          + " C:\\Users\\<name> become ~; other paths are kept")
  void mountLabels() {
    assertThat(SystemMetrics.mountLabel("/")).isEqualTo("/");
    assertThat(SystemMetrics.mountLabel("/data")).isEqualTo("/data");
    assertThat(SystemMetrics.mountLabel("/home")).as("no user segment").isEqualTo("/home");
    assertThat(SystemMetrics.mountLabel("/home/alice")).isEqualTo("~");
    assertThat(SystemMetrics.mountLabel("/home/alice/data")).isEqualTo("~/data");
    assertThat(SystemMetrics.mountLabel("/Users/bob/Volumes/x")).isEqualTo("~/Volumes/x");
    assertThat(SystemMetrics.mountLabel("/var/home/alice")).isEqualTo("/var/~");
    assertThat(SystemMetrics.mountLabel("/mnt/a/home/carol/b")).isEqualTo("/mnt/a/~/b");
    assertThat(SystemMetrics.mountLabel("C:\\Users\\dave")).isEqualTo("~");
    assertThat(SystemMetrics.mountLabel("C:\\Users\\dave\\data")).isEqualTo("~\\data");
    assertThat(SystemMetrics.mountLabel("D:\\builds")).isEqualTo("D:\\builds");
  }

  @Test
  @DisplayName(
      "T-AG-063 system_disk_*_bytes{mount} of a home directory mount carry ~, not the user name"
          + " (C-229 (9))")
  void homeMountSeries(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("root");
    copyTree(fixture("v2"), root);
    Files.createDirectories(root.resolve("home/alice"));
    Files.write(
        root.resolve("proc/self/mounts"),
        ("overlay / overlay rw 0 0\n" + "/dev/sdb1 /home/alice ext4 rw 0 0\n")
            .getBytes(StandardCharsets.UTF_8));
    Map<String, MetricSample> v =
        byName(system(root, OsBean.fixed(-1, -1, 0.9, -1, -1), 1_000_000_000L));
    assertThat(v.get("system_disk_total_bytes{mount=\"~\"}").getValue()).isPositive();
    assertThat(v.keySet()).noneMatch(name -> name.contains("alice"));
  }

  @Test
  @DisplayName("T-AG-063 /proc/mounts octal escapes and the mount cap")
  void mounts() {
    assertThat(SystemMetrics.unescapeMount("/mnt/my\\040disk")).isEqualTo("/mnt/my disk");
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      many.append("/dev/sd").append(i).append(" /m").append(i).append(" ext4 rw 0 0\n");
    }
    assertThat(SystemMetrics.mounts(many.toString())).hasSize(SystemMetrics.MAX_MOUNTS);
  }

  private static void copyTree(Path from, Path to) throws Exception {
    try (Stream<Path> files = Files.walk(from)) {
      for (Path p : (Iterable<Path>) files::iterator) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
