/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.ModuleHealth;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;

/**
 * Builds {@link SelfDiagnostics} snapshots (18 §19, 10 §12.1 SELF_DIAG subset, ADR-0010 C-95) on an
 * agent thread:
 *
 * <ul>
 *   <li>{@code threadlocal_contexts_live}, {@code active_registry_size}, {@code weakmap_size},
 *       {@code weakmap_entries}: the recording side's gauges ({@code TraceRuntime.selfValues()},
 *       which defines the two weak-map gauges); before reading them the collected keys of the
 *       fallback context stores are expunged (what {@code argos-watch} does every 5 s), so a
 *       snapshot after a GC shows the live entries only;
 *   <li>{@code agent_threads_cpu_seconds_total}: CPU time of the live {@link ArgosThread}s ({@code
 *       ThreadMXBean}); absent when the JVM does not measure thread CPU time;
 *   <li>{@code direct_memory_bytes}: the JVM's {@code direct} buffer pool ({@code
 *       BufferPoolMXBean}) until the transport counts its own (WO-11); absent when unavailable;
 *   <li>{@code premain_ms}: start of premain to the start banner (P8);
 *   <li>every counter of {@link AgentCounters} and the state and error count of every named module.
 * </ul>
 *
 * <p>Missing is not zero (C-4, D-23): a gauge that cannot be measured is left out. The JMX beans
 * are looked up on the first snapshot (never on the premain thread). Bounded: at most {@value
 * #MAX_THREADS} threads are enumerated.
 */
public final class SelfDiagnosticsCollector {
  /** Upper bound of the threads enumerated for the agent CPU time. */
  static final int MAX_THREADS = 65_536;

  /** The recording side's gauges by 18 §19 name (a gauge it cannot measure is absent). */
  public interface Gauges {
    Map<String, Long> values();
  }

  private final Gauges gauges;
  private final AgentCounters counters;
  private final String[] moduleNames;
  private volatile double premainMillis = Double.NaN;

  /**
   * @param gauges {@code TraceRuntime::selfValues}
   * @param counters the agent's counters
   * @param moduleNames module names by module id (null entries are not reported)
   */
  public SelfDiagnosticsCollector(Gauges gauges, AgentCounters counters, String[] moduleNames) {
    this.gauges = gauges;
    this.counters = counters;
    this.moduleNames = moduleNames == null ? new String[0] : moduleNames.clone();
  }

  /** Records {@code premain_ms} (P8). */
  public void premainMillis(double millis) {
    this.premainMillis = millis;
  }

  /** A snapshot now; never throws (a failing source leaves its gauges out). */
  public SelfDiagnostics snapshot() {
    SelfDiagnostics.Builder builder = SelfDiagnostics.builder();
    try {
      ContextStore.expungeAll();
    } catch (Throwable ignored) {
      // The gauges are then read with stale entries; never fail the snapshot.
    }
    Map<String, Long> values = null;
    try {
      values = gauges == null ? null : gauges.values();
    } catch (Throwable ignored) {
      values = null;
    }
    if (values != null) {
      Long value = values.get(SelfDiagnostics.THREADLOCAL_CONTEXTS_LIVE);
      if (value != null) {
        builder.threadLocalContextsLive(value);
      }
      value = values.get(SelfDiagnostics.ACTIVE_REGISTRY_SIZE);
      if (value != null) {
        builder.activeRegistrySize(value);
      }
      value = values.get(SelfDiagnostics.WEAKMAP_SIZE);
      if (value != null) {
        builder.weakMapSize(value);
      }
      value = values.get(SelfDiagnostics.WEAKMAP_ENTRIES);
      if (value != null) {
        builder.weakMapEntries(value);
      }
    }
    Double cpu = agentThreadsCpuSeconds();
    if (cpu != null) {
      builder.agentThreadsCpuSeconds(cpu);
    }
    Long direct = directMemoryBytes();
    if (direct != null) {
      builder.directMemoryBytes(direct);
    }
    double premain = premainMillis;
    if (!Double.isNaN(premain)) {
      builder.premainMs(premain);
    }
    builder.counters(counters.snapshot());
    for (int id = 0; id < moduleNames.length && id < ModuleHealth.MAX_MODULES; id++) {
      if (moduleNames[id] != null) {
        builder.module(id, moduleNames[id], ModuleHealth.state(id), ModuleHealth.errors(id));
      }
    }
    return builder.build();
  }

  /** CPU seconds of the live agent threads, or null when the JVM does not measure them. */
  static Double agentThreadsCpuSeconds() {
    try {
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
        return null;
      }
      long nanos = 0;
      boolean measured = false;
      for (Thread thread : liveThreads()) {
        if (thread instanceof ArgosThread) {
          long cpu = bean.getThreadCpuTime(thread.getId());
          if (cpu >= 0) {
            nanos += cpu;
            measured = true;
          }
        }
      }
      return measured ? nanos / 1e9 : null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** Bytes of the JVM's direct buffer pool, or null when it is not reported. */
  static Long directMemoryBytes() {
    try {
      for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
        if ("direct".equals(pool.getName())) {
          long used = pool.getMemoryUsed();
          return used >= 0 ? Long.valueOf(used) : null;
        }
      }
    } catch (Throwable t) {
      return null;
    }
    return null;
  }

  /** The live threads of the JVM (bounded). */
  static Thread[] liveThreads() {
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root != null && root.getParent() != null) {
      root = root.getParent();
    }
    if (root == null) {
      return new Thread[0];
    }
    int size = Math.min(MAX_THREADS, root.activeCount() * 2 + 16);
    Thread[] threads = new Thread[size];
    int count = root.enumerate(threads, true);
    Thread[] live = new Thread[count];
    System.arraycopy(threads, 0, live, 0, count);
    return live;
  }
}
