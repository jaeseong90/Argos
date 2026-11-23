/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JVM rows of the 10 §11 metric table, from the platform MXBeans looked up once (never the
 * platform {@code MBeanServer}, so JMX stays off and no JMX connector or {@code
 * PlatformLoggingMXBean} is touched):
 *
 * <ul>
 *   <li>memory: {@code jvm_memory_used_bytes{area}}, {@code jvm_memory_max_bytes{area}} ({@code
 *       heap}, {@code nonheap}), {@code jvm_memory_pool_used_bytes{pool}}, {@code
 *       jvm_memory_pool_max_bytes{pool}}, {@code jvm_buffer_memory_used_bytes{buffer}} ({@code
 *       direct}, {@code mapped}); a maximum the JVM does not define (-1) is missing;
 *   <li>GC ({@link GcNameMapper}): {@code jvm_gc_collection_count_total{gc,gc_type}}, {@code
 *       jvm_gc_collection_seconds_total{gc,gc_type}}, {@code jvm_gc_pause_seconds_total{gc}} (pause
 *       beans only); beans of one label pair are added up;
 *   <li>threads and classes: {@code jvm_threads_live}, {@code jvm_threads_daemon}, {@code
 *       jvm_threads_peak}, {@code jvm_classes_loaded}, {@code jvm_classes_unloaded_total};
 *   <li>process: {@code process_cpu_usage_ratio} (process CPU time over wall time and available
 *       processors since the previous collection; the JVM's own {@code getProcessCpuLoad} for the
 *       first one), {@code process_open_fds}, {@code process_max_fds} (Unix), {@code
 *       jvm_uptime_seconds}. Without the HotSpot extensions ({@link OsBean}) these are missing.
 * </ul>
 *
 * {@code jvm_threads_virtual_started_total} of the table is not reported: no JDK MXBean counts
 * started virtual threads ({@code jdk.management.VirtualThreadSchedulerMXBean}, JDK 24+, reports
 * pool size, mounted and queued threads only). Bounded: at most {@value #MAX_POOLS} memory pools,
 * {@value #MAX_COLLECTORS} collectors and the two buffer pools. Metrics thread only.
 */
final class JvmMetrics {
  static final int MAX_POOLS = 32;
  static final int MAX_COLLECTORS = 16;

  private final MemoryMXBean memory;
  private final List<MemoryPoolMXBean> pools;
  private final List<BufferPoolMXBean> buffers;
  private final List<GarbageCollectorMXBean> collectors;
  private final List<GcNameMapper.Mapping> mappings;
  private final ThreadMXBean threads;
  private final ClassLoadingMXBean classes;
  private final RuntimeMXBean runtime;
  private final OsBean os;
  private final int processors;
  private long lastCpuNanos = -1;
  private long lastWallNanos;

  JvmMetrics(GcNameMapper mapper, OsBean os) {
    this.memory = memoryBean();
    this.pools = bounded(poolBeans(), MAX_POOLS);
    this.buffers = bufferBeans();
    this.collectors = bounded(collectorBeans(), MAX_COLLECTORS);
    List<GcNameMapper.Mapping> m = new ArrayList<GcNameMapper.Mapping>(collectors.size());
    for (GarbageCollectorMXBean gc : collectors) {
      m.add(mapper.map(name(gc)));
    }
    this.mappings = Collections.unmodifiableList(m);
    this.threads = threadBean();
    this.classes = classBean();
    this.runtime = runtimeBean();
    this.os = os;
    this.processors = Math.max(1, Runtime.getRuntime().availableProcessors());
  }

  /** The labels of every collector bean, in bean order (tests and the [wo16-gc] report). */
  List<String> collectorNames() {
    List<String> names = new ArrayList<String>(collectors.size());
    for (GarbageCollectorMXBean gc : collectors) {
      names.add(name(gc));
    }
    return names;
  }

  /** Records every value of this collection in {@code registry}; never throws. */
  void collect(MetricRegistry registry, long nowNanos) {
    memory(registry);
    gc(registry);
    threadsAndClasses(registry);
    process(registry, nowNanos);
  }

  private void memory(MetricRegistry r) {
    if (memory != null) {
      try {
        usage(r, "heap", memory.getHeapMemoryUsage());
      } catch (Throwable ignored) {
        // Missing this time.
      }
      try {
        usage(r, "nonheap", memory.getNonHeapMemoryUsage());
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
    for (MemoryPoolMXBean pool : pools) {
      try {
        MemoryUsage u = pool.getUsage();
        if (u == null) {
          continue;
        }
        String name = pool.getName();
        r.gauge(MetricRegistry.name("jvm_memory_pool_used_bytes", "pool", name), u.getUsed());
        if (u.getMax() >= 0) {
          r.gauge(MetricRegistry.name("jvm_memory_pool_max_bytes", "pool", name), u.getMax());
        }
      } catch (Throwable ignored) {
        // An invalid pool (its memory manager went away) is missing.
      }
    }
    for (BufferPoolMXBean buffer : buffers) {
      try {
        long used = buffer.getMemoryUsed();
        if (used >= 0) {
          r.gauge(
              MetricRegistry.name("jvm_buffer_memory_used_bytes", "buffer", buffer.getName()),
              used);
        }
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
  }

  private static void usage(MetricRegistry r, String area, MemoryUsage u) {
    if (u == null) {
      return;
    }
    r.gauge(MetricRegistry.name("jvm_memory_used_bytes", "area", area), u.getUsed());
    if (u.getMax() >= 0) {
      r.gauge(MetricRegistry.name("jvm_memory_max_bytes", "area", area), u.getMax());
    }
  }

  private void gc(MetricRegistry r) {
    int n = collectors.size();
    long[] counts = new long[n];
    long[] millis = new long[n];
    for (int i = 0; i < n; i++) {
      try {
        counts[i] = collectors.get(i).getCollectionCount();
        millis[i] = collectors.get(i).getCollectionTime();
      } catch (Throwable t) {
        counts[i] = -1;
        millis[i] = -1;
      }
    }
    recordGc(r, mappings, counts, millis);
  }

  /**
   * Records the GC series of beans with {@code mappings} and their collection counts and times in
   * ms (-1: missing): one count and one time series per label pair (beans of one pair added up),
   * one pause series per {@code gc} over its pause beans only. A sum with a missing part is left
   * out, so a counter never seems to go down.
   */
  static void recordGc(
      MetricRegistry r, List<GcNameMapper.Mapping> mappings, long[] counts, long[] millis) {
    // Label pair -> {count, millis}; pause {count, millis} per gc. Bounded by the collector cap.
    Map<String, long[]> byType = new LinkedHashMap<String, long[]>();
    Map<String, GcNameMapper.Mapping> labels = new LinkedHashMap<String, GcNameMapper.Mapping>();
    Map<String, long[]> pauses = new LinkedHashMap<String, long[]>();
    for (int i = 0; i < mappings.size(); i++) {
      GcNameMapper.Mapping m = mappings.get(i);
      String key = m.gc() + "|" + m.gcType();
      long[] sum = byType.get(key);
      if (sum == null) {
        sum = new long[] {0, 0};
        byType.put(key, sum);
        labels.put(key, m);
      }
      add(sum, counts[i], millis[i]);
      if (m.pause()) {
        long[] pause = pauses.get(m.gc());
        if (pause == null) {
          pause = new long[] {0, 0};
          pauses.put(m.gc(), pause);
        }
        add(pause, counts[i], millis[i]);
      }
    }
    for (Map.Entry<String, long[]> e : byType.entrySet()) {
      long[] v = e.getValue();
      GcNameMapper.Mapping m = labels.get(e.getKey());
      if (v[0] >= 0) {
        r.counter(
            MetricRegistry.name(
                "jvm_gc_collection_count_total", "gc", m.gc(), "gc_type", m.gcType()),
            v[0]);
      }
      if (v[1] >= 0) {
        r.counter(
            MetricRegistry.name(
                "jvm_gc_collection_seconds_total", "gc", m.gc(), "gc_type", m.gcType()),
            v[1] / 1000.0);
      }
    }
    for (Map.Entry<String, long[]> e : pauses.entrySet()) {
      long[] v = e.getValue();
      if (v[1] >= 0) {
        r.counter(
            MetricRegistry.name("jvm_gc_pause_seconds_total", "gc", e.getKey()), v[1] / 1000.0);
      }
    }
  }

  /** Adds a bean's values; a missing one (-1) makes the sum missing. */
  private static void add(long[] sum, long count, long millis) {
    sum[0] = count < 0 || sum[0] < 0 ? -1 : sum[0] + count;
    sum[1] = millis < 0 || sum[1] < 0 ? -1 : sum[1] + millis;
  }

  private void threadsAndClasses(MetricRegistry r) {
    if (threads != null) {
      try {
        r.gauge("jvm_threads_live", threads.getThreadCount());
        r.gauge("jvm_threads_daemon", threads.getDaemonThreadCount());
        r.gauge("jvm_threads_peak", threads.getPeakThreadCount());
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
    if (classes != null) {
      try {
        r.gauge("jvm_classes_loaded", classes.getLoadedClassCount());
        r.counter("jvm_classes_unloaded_total", classes.getUnloadedClassCount());
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
  }

  private void process(MetricRegistry r, long nowNanos) {
    long cpu = os.processCpuNanos();
    if (cpu >= 0 && lastCpuNanos >= 0 && nowNanos > lastWallNanos) {
      double ratio = (cpu - lastCpuNanos) / ((double) (nowNanos - lastWallNanos) * processors);
      r.gauge("process_cpu_usage_ratio", clamp(ratio));
    } else {
      double load = os.processCpuLoad();
      if (load >= 0) {
        r.gauge("process_cpu_usage_ratio", clamp(load));
      }
    }
    lastCpuNanos = cpu;
    lastWallNanos = nowNanos;
    long open = os.openFds();
    if (open >= 0) {
      r.gauge("process_open_fds", open);
    }
    long max = os.maxFds();
    if (max >= 0) {
      r.gauge("process_max_fds", max);
    }
    if (runtime != null) {
      try {
        r.gauge("jvm_uptime_seconds", runtime.getUptime() / 1000.0);
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
  }

  static double clamp(double ratio) {
    return ratio < 0 ? 0 : ratio > 1 ? 1 : ratio;
  }

  // --- bean lookup (each never throws) --------------------------------------------------------

  private static String name(GarbageCollectorMXBean gc) {
    try {
      String name = gc.getName();
      return name == null ? "" : name;
    } catch (Throwable t) {
      return "";
    }
  }

  private static MemoryMXBean memoryBean() {
    try {
      return ManagementFactory.getMemoryMXBean();
    } catch (Throwable t) {
      return null;
    }
  }

  private static <T> List<T> bounded(List<T> list, int max) {
    if (list.size() <= max) {
      return list;
    }
    return Collections.unmodifiableList(new ArrayList<T>(list.subList(0, max)));
  }

  private static List<MemoryPoolMXBean> poolBeans() {
    try {
      return Collections.unmodifiableList(
          new ArrayList<MemoryPoolMXBean>(ManagementFactory.getMemoryPoolMXBeans()));
    } catch (Throwable t) {
      return Collections.emptyList();
    }
  }

  private static List<BufferPoolMXBean> bufferBeans() {
    List<BufferPoolMXBean> out = new ArrayList<BufferPoolMXBean>(2);
    try {
      for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
        String name = pool.getName();
        if ("direct".equals(name) || "mapped".equals(name)) {
          out.add(pool);
        }
      }
    } catch (Throwable t) {
      // No buffer pools.
    }
    return Collections.unmodifiableList(out);
  }

  private static List<GarbageCollectorMXBean> collectorBeans() {
    try {
      return Collections.unmodifiableList(
          new ArrayList<GarbageCollectorMXBean>(ManagementFactory.getGarbageCollectorMXBeans()));
    } catch (Throwable t) {
      return Collections.emptyList();
    }
  }

  private static ThreadMXBean threadBean() {
    try {
      return ManagementFactory.getThreadMXBean();
    } catch (Throwable t) {
      return null;
    }
  }

  private static ClassLoadingMXBean classBean() {
    try {
      return ManagementFactory.getClassLoadingMXBean();
    } catch (Throwable t) {
      return null;
    }
  }

  private static RuntimeMXBean runtimeBean() {
    try {
      return ManagementFactory.getRuntimeMXBean();
    } catch (Throwable t) {
      return null;
    }
  }
}
