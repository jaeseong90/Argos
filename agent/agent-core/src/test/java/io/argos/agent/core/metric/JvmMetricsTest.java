/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-AG-062, JVM part (F-A09, 10 §11): the JVM series of this test JVM from the platform MXBeans
 * without the platform MBean server (JMX off): memory by area and pool, GC by the name table,
 * threads, classes, process CPU, file descriptors on Unix and uptime; a missing HotSpot extension
 * leaves its series out instead of reporting 0 (C-4).
 */
@Tag("req:F-A09")
@Tag("req:T-AG-062")
class JvmMetricsTest {

  private static Map<String, MetricSample> collect(JvmMetrics jvm, MetricRegistry r, long nanos) {
    jvm.collect(r, nanos);
    return CgroupReaderTest.byName(r.drain(5_000));
  }

  @Test
  @DisplayName(
      "T-AG-062 JVM series of this JVM with JMX off: heap and non-heap, every memory pool, GC"
          + " series per table labels, threads, classes, uptime, process CPU ratio in [0, 1]")
  void thisJvm() {
    MetricRegistry r = new MetricRegistry(500);
    JvmMetrics jvm = new JvmMetrics(new GcNameMapper(() -> {}), OsBean.jvm());
    // A first collection primes the process CPU time: the series under test is then the ratio of
    // the CPU time between two collections (the documented path). The first collection alone
    // falls back to the OS bean's recent load, which the JVM may not have yet (a negative value,
    // so the series is rightly missing): that made this test fail now and then (C-235 (9)).
    long first = System.nanoTime();
    collect(jvm, r, first);
    Map<String, MetricSample> v = collect(jvm, r, Math.max(System.nanoTime(), first + 1));

    assertThat(v.get("jvm_memory_used_bytes{area=\"heap\"}").getValue()).isPositive();
    assertThat(v.get("jvm_memory_used_bytes{area=\"nonheap\"}").getValue()).isPositive();
    long heapMax = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    if (heapMax >= 0) {
      assertThat(v.get("jvm_memory_max_bytes{area=\"heap\"}").getValue()).isPositive();
    } else {
      assertThat(v)
          .as("undefined maximum is missing")
          .doesNotContainKey("jvm_memory_max_bytes{area=\"heap\"}");
    }
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      assertThat(v).containsKey("jvm_memory_pool_used_bytes{pool=\"" + pool.getName() + "\"}");
    }
    assertThat(v).containsKey("jvm_buffer_memory_used_bytes{buffer=\"direct\"}");
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      GcNameMapper.Mapping m = GcNameMapper.lookup(gc.getName());
      assertThat(v)
          .as("collector %s", gc.getName())
          .containsKey(
              "jvm_gc_collection_count_total{gc=\"" + m.gc() + "\",gc_type=\"" + m.gcType() + "\"}")
          .containsKey(
              "jvm_gc_collection_seconds_total{gc=\""
                  + m.gc()
                  + "\",gc_type=\""
                  + m.gcType()
                  + "\"}");
      if (m.pause()) {
        assertThat(v).containsKey("jvm_gc_pause_seconds_total{gc=\"" + m.gc() + "\"}");
      }
    }
    assertThat(v.get("jvm_threads_live").getValue()).isPositive();
    assertThat(v.get("jvm_threads_daemon").getValue()).isNotNegative();
    assertThat(v.get("jvm_threads_peak").getValue())
        .isGreaterThanOrEqualTo(v.get("jvm_threads_live").getValue());
    assertThat(v.get("jvm_classes_loaded").getValue()).isGreaterThan(100);
    assertThat(v).containsKey("jvm_classes_unloaded_total");
    assertThat(v.get("jvm_uptime_seconds").getValue()).isPositive();
    assertThat(v.get("process_cpu_usage_ratio").getValue()).isBetween(0.0, 1.0);
    boolean unix =
        !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    if (unix) {
      assertThat(v.get("process_open_fds").getValue()).isPositive();
      assertThat(v.get("process_max_fds").getValue())
          .isGreaterThanOrEqualTo(v.get("process_open_fds").getValue());
    } else {
      assertThat(v)
          .as("no Unix bean on Windows")
          .doesNotContainKeys("process_open_fds", "process_max_fds");
    }
    assertThat(jvm.collectorNames()).hasSize(ManagementFactory.getGarbageCollectorMXBeans().size());
  }

  @Test
  @DisplayName(
      "T-AG-062 process_cpu_usage_ratio from the process CPU time between collections over wall"
          + " time and available processors")
  void processCpuRatio() {
    int processors = Runtime.getRuntime().availableProcessors();
    MetricRegistry r = new MetricRegistry(500);
    AtomicLong cpu = new AtomicLong(1_000_000_000L);
    JvmMetrics jvm = new JvmMetrics(new GcNameMapper(() -> {}), OsBean.processCpu(cpu, 0.7));
    Map<String, MetricSample> v = collect(jvm, r, 10_000_000_000L);
    assertThat(v.get("process_cpu_usage_ratio").getValue())
        .as("first collection: the JVM's own load")
        .isEqualTo(0.7);
    assertThat(v).doesNotContainKeys("process_open_fds", "process_max_fds");
    // 5 s later the process used 5 s * processors * 0.25 of CPU time.
    cpu.addAndGet((long) (5_000_000_000L * processors * 0.25));
    v = collect(jvm, r, 15_000_000_000L);
    assertThat(v.get("process_cpu_usage_ratio").getValue()).isCloseTo(0.25, within(1e-9));
  }

  @Test
  @DisplayName("T-AG-062 without the HotSpot extensions the process series are missing, not 0")
  void missingExtensions() {
    MetricRegistry r = new MetricRegistry(500);
    JvmMetrics jvm = new JvmMetrics(new GcNameMapper(() -> {}), OsBean.NONE);
    Map<String, MetricSample> v = collect(jvm, r, 1L);
    assertThat(v)
        .doesNotContainKeys("process_cpu_usage_ratio", "process_open_fds", "process_max_fds")
        .containsKey("jvm_threads_live");
    MetricBatch batch = r.drain(10_000);
    assertThat(batch.getSamplesList()).allMatch(s -> s.getTsMs() == 10_000);
  }
}
