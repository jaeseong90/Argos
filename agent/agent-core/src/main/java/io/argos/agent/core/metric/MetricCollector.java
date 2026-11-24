/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnostics;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.log.JulLogger;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.proto.v1.MetricBatch;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The 5-second metric collector (10 §11, F-A09-F-A11, D-16, D-25): one {@link #collect} per grid
 * point on {@code argos-metrics} builds one {@code MetricBatch} (11 §4.4) of every series of the 10
 * §11 table that this JVM and host can measure:
 *
 * <ol>
 *   <li>JVM ({@link JvmMetrics}, {@link GcNameMapper});
 *   <li>connection pools ({@code jdbc_pool_active|idle|waiting|max{pool}} from {@link
 *       PoolRegistry#snapshot()}, {@code jdbc_pool_wait_count_total{pool}} and {@code
 *       jdbc_pool_wait_seconds_total{pool}} from the {@link PoolWaitSource}) and Tomcat connectors
 *       ({@code tomcat_threads_busy|current|max{connector}}, {@code
 *       tomcat_connections_current|max{connector}} from {@link TomcatRegistry#snapshot()}); a value
 *       whose getter is missing (-1) is left out;
 *   <li>system and container ({@link SystemMetrics}, {@link CgroupReader});
 *   <li>the agent itself: every counter of {@link AgentCounters} (or, when attached, the
 *       self-diagnostics counters, which add the log and transport metrics), {@code
 *       argos_agent_cpu_seconds_total} (CPU time of the {@link ArgosThread}s), the {@code
 *       argos_agent_*} gauges of {@link TraceRuntime#selfValues()} (queue sizes and estimated
 *       bytes), the registry counters {@code argos_agent_registry_overflow_total{registry}} and
 *       {@code argos_agent_registry_getter_errors_total{registry}} (ADR-0010 C-185 (4)), the {@link
 *       CounterSource}s and {@value MetricRegistry#SERIES_DROPPED}.
 * </ol>
 *
 * The registry ({@link MetricRegistry}) caps the series at {@code metrics.max-series}; earlier
 * groups win when the cap is reached. MXBeans are looked up on the first collection (never on the
 * premain thread); the platform {@code MBeanServer} and the logging MXBean are never touched (JMX
 * off, no JUL initialisation). Not thread-safe: the metrics thread only.
 */
public final class MetricCollector {
  /** Upper bound of the {@link CounterSource}s. */
  static final int MAX_COUNTER_SOURCES = 8;

  static final String AGENT_CPU = "argos_agent_cpu_seconds_total";
  static final String AGENT_PREFIX = "argos_agent_";
  static final int MAX_THREADS = 65_536;
  static final int MAX_AGENT_THREADS = 64;

  private final MetricRegistry registry;
  private final TraceRuntime runtime;
  private final PoolWaitSource poolWaits;
  private final CgroupReader cgroup;
  private final Path root;
  private final boolean linux;
  private final OsBean osOverride;
  private final GcNameMapper gcNames;
  private final AtomicReferenceArray<CounterSource> sources =
      new AtomicReferenceArray<CounterSource>(MAX_COUNTER_SOURCES);
  private volatile SelfDiagnosticsCollector diagnostics;
  private JvmMetrics jvm;
  private SystemMetrics system;
  private boolean failedOnce;
  private final Map<Long, Long> agentThreadCpu = new HashMap<Long, Long>();
  private long agentCpuNanos;

  MetricCollector(
      int maxSeries,
      TraceRuntime runtime,
      PoolWaitSource poolWaits,
      Path root,
      boolean linux,
      OsBean os,
      GcNameMapper gcNames) {
    this.registry = new MetricRegistry(maxSeries);
    this.runtime = runtime;
    this.poolWaits = poolWaits;
    this.root = root;
    this.linux = linux;
    this.cgroup = root == null ? CgroupReader.system() : new CgroupReader(root);
    this.osOverride = os;
    this.gcNames = gcNames;
  }

  /**
   * The collector of {@code config} ({@code metrics.max-series}) over the parts of {@code runtime}
   * (null: no pool, Tomcat or agent series) and {@code poolWaits} (null: no pool wait series).
   * Reads the cgroup mode; looks nothing else up yet. Never throws.
   */
  public static MetricCollector create(
      AgentConfig config, TraceRuntime runtime, PoolWaitSource poolWaits) {
    int max = 500;
    try {
      max = config == null ? 500 : config.metricsMaxSeries();
    } catch (Throwable ignored) {
      // The default cap.
    }
    return new MetricCollector(max, runtime, poolWaits, null, false, null, new GcNameMapper());
  }

  /** Adds the self-diagnostics counters (agent, log and transport counters) to every batch. */
  public void selfDiagnostics(SelfDiagnosticsCollector value) {
    this.diagnostics = value;
  }

  /** Adds {@code source} (at most {@value #MAX_COUNTER_SOURCES}; further ones are ignored). */
  public boolean addCounterSource(CounterSource source) {
    if (source == null) {
      return false;
    }
    for (int i = 0; i < MAX_COUNTER_SOURCES; i++) {
      if (sources.compareAndSet(i, null, source)) {
        return true;
      }
    }
    return false;
  }

  /** {@code v1}, {@code v2} or {@code none} ({@code AgentHello.cgroup}). */
  public String cgroupMode() {
    return cgroup.mode();
  }

  /** The registry (tests). */
  MetricRegistry registry() {
    return registry;
  }

  /** The collector MXBean names of this JVM, in bean order (null before the first collection). */
  List<String> collectorNames() {
    JvmMetrics j = jvm;
    return j == null ? null : j.collectorNames();
  }

  /**
   * The batch of the grid point {@code gridTsMs}: the series dictionary and one sample per series,
   * all at {@code gridTsMs}. Null only when the collection failed as a whole (logged once). Never
   * throws.
   */
  public MetricBatch collect(long gridTsMs) {
    return collect(gridTsMs, System.nanoTime());
  }

  MetricBatch collect(long gridTsMs, long nowNanos) {
    try {
      if (jvm == null) {
        OsBean os = osOverride != null ? osOverride : OsBean.jvm();
        jvm = new JvmMetrics(gcNames, os);
        boolean onLinux = root == null ? CgroupReader.linux() : linux;
        Path base = root == null ? Paths.get("/") : root;
        system = new SystemMetrics(base, onLinux, cgroup, os);
      }
      jvm.collect(registry, nowNanos);
      poolsAndConnectors();
      system.collect(registry, nowNanos);
      self();
      return registry.drain(gridTsMs);
    } catch (Throwable t) {
      if (!failedOnce) {
        failedOnce = true;
        AgentLogger.warn(
            "metric collection failed with {}: some metrics of this interval are missing",
            t.getClass());
      }
      try {
        return registry.drain(gridTsMs);
      } catch (Throwable ignored) {
        return null;
      }
    }
  }

  // --- pools and connectors -------------------------------------------------------------------

  private void poolsAndConnectors() {
    TracerImpl tracer = runtime == null ? null : runtime.tracerImpl();
    if (tracer != null) {
      try {
        for (PoolSample pool : tracer.pools().snapshot()) {
          String name = pool.name();
          positive("jdbc_pool_active", "pool", name, pool.active());
          positive("jdbc_pool_idle", "pool", name, pool.idle());
          positive("jdbc_pool_waiting", "pool", name, pool.waiting());
          positive("jdbc_pool_max", "pool", name, pool.max());
        }
      } catch (Throwable ignored) {
        // Missing this time.
      }
      try {
        for (ConnectorSample c : tracer.connectors().snapshot()) {
          String name = c.name();
          positive("tomcat_threads_busy", "connector", name, c.threadsBusy());
          positive("tomcat_threads_current", "connector", name, c.threadsCurrent());
          positive("tomcat_threads_max", "connector", name, c.threadsMax());
          positive("tomcat_connections_current", "connector", name, c.connectionsCurrent());
          positive("tomcat_connections_max", "connector", name, c.connectionsMax());
        }
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
    PoolWaitSource waits = poolWaits;
    if (waits != null) {
      try {
        Map<String, long[]> values = waits.poolWaits();
        if (values != null) {
          int pools = 0;
          for (Map.Entry<String, long[]> e : values.entrySet()) {
            long[] v = e.getValue();
            if (e.getKey() == null || v == null || v.length < 2) {
              continue;
            }
            registry.counter(
                MetricRegistry.name("jdbc_pool_wait_count_total", "pool", e.getKey()), v[0]);
            registry.counter(
                MetricRegistry.name("jdbc_pool_wait_seconds_total", "pool", e.getKey()),
                v[1] / 1e9);
            if (++pools >= PoolRegistry.MAX_POOLS) {
              break;
            }
          }
        }
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
  }

  /** A gauge of a registry value; a missing value (-1) is left out. */
  private void positive(String base, String label, String value, long sample) {
    if (sample >= 0) {
      registry.gauge(MetricRegistry.name(base, label, value), sample);
    }
  }

  // --- agent self metrics ---------------------------------------------------------------------

  private void self() {
    TracerImpl tracer = runtime == null ? null : runtime.tracerImpl();
    Map<String, Long> counters = null;
    SelfDiagnosticsCollector d = diagnostics;
    if (d != null) {
      try {
        SelfDiagnostics snapshot = d.snapshot();
        counters = snapshot.counters();
      } catch (Throwable ignored) {
        counters = null;
      }
    }
    if (counters == null && tracer != null) {
      try {
        Map<String, Long> own = new LinkedHashMap<String, Long>(tracer.counters().snapshot());
        own.put(SelfDiagnosticsCollector.LOG_DROPPED, AgentLogger.droppedRecords());
        own.put(SelfDiagnosticsCollector.LIBRARY_LOG_SUPPRESSED, JulLogger.suppressedTotal());
        counters = own;
      } catch (Throwable ignored) {
        counters = null;
      }
    }
    values(counters);
    Double cpu = agentThreadsCpuSeconds();
    if (cpu != null) {
      registry.counter(AGENT_CPU, cpu);
    }
    if (tracer != null) {
      // WO-19: the slow-stack sampler's cost (10 §11, §13.1), seconds with sub-second resolution.
      registry.counter(
          AgentCounters.STACK_CAPTURE_SECONDS, tracer.counters().stackCaptureSeconds());
    }
    if (runtime != null) {
      try {
        Map<String, Long> gauges = runtime.selfValues();
        for (Map.Entry<String, Long> e : gauges.entrySet()) {
          if (e.getKey().startsWith(AGENT_PREFIX) && e.getValue() != null) {
            registry.gauge(e.getKey(), e.getValue());
          }
        }
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
    if (tracer != null) {
      try {
        values(tracer.pools().counters());
        values(tracer.connectors().counters());
      } catch (Throwable ignored) {
        // Missing this time.
      }
    }
    for (int i = 0; i < MAX_COUNTER_SOURCES; i++) {
      CounterSource source = sources.get(i);
      if (source != null) {
        try {
          values(source.counters());
        } catch (Throwable ignored) {
          // That source is missing this time.
        }
      }
    }
  }

  /** Records named values: {@code ..._total} (before the labels) as counters, others as gauges. */
  private void values(Map<String, Long> values) {
    if (values == null) {
      return;
    }
    for (Map.Entry<String, Long> e : values.entrySet()) {
      String name = e.getKey();
      Long value = e.getValue();
      if (name == null || value == null) {
        continue;
      }
      if (isCounter(name)) {
        registry.counter(name, value);
      } else {
        registry.gauge(name, value);
      }
    }
  }

  /** True when the base name (the part before the labels) ends in {@code _total}. */
  static boolean isCounter(String series) {
    int brace = series.indexOf('{');
    String base = brace < 0 ? series : series.substring(0, brace);
    return base.endsWith("_total");
  }

  /**
   * CPU seconds of the agent threads since the start: the increase of every live {@link
   * ArgosThread} since the previous collection is added up, so a thread that ends keeps its share
   * and the counter never goes down. Null when the JVM does not measure thread CPU time. At most
   * {@value #MAX_AGENT_THREADS} agent threads are followed.
   */
  Double agentThreadsCpuSeconds() {
    try {
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
        return null;
      }
      ThreadGroup group = Thread.currentThread().getThreadGroup();
      while (group != null && group.getParent() != null) {
        group = group.getParent();
      }
      if (group == null) {
        return null;
      }
      Thread[] threads = new Thread[Math.min(MAX_THREADS, group.activeCount() * 2 + 16)];
      int count = group.enumerate(threads, true);
      Map<Long, Long> seen = new HashMap<Long, Long>();
      boolean measured = agentCpuNanos > 0;
      for (int i = 0; i < count && seen.size() < MAX_AGENT_THREADS; i++) {
        if (threads[i] instanceof ArgosThread) {
          long id = threads[i].getId();
          long cpu = bean.getThreadCpuTime(id);
          if (cpu >= 0) {
            Long before = agentThreadCpu.get(id);
            long increase = before == null || cpu < before ? cpu : cpu - before;
            agentCpuNanos += increase;
            seen.put(id, cpu);
            measured = true;
          }
        }
      }
      agentThreadCpu.clear();
      agentThreadCpu.putAll(seen);
      return measured ? agentCpuNanos / 1e9 : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
