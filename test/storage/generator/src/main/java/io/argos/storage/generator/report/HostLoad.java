/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Samples the host CPU load (all processes, including a Docker VM that runs ClickHouse) and the
 * generator's own CPU load during a run, so that every measurement records the machine conditions
 * (the machine is shared with other benchmarks).
 */
public final class HostLoad implements AutoCloseable {

  private final com.sun.management.OperatingSystemMXBean os;
  private final ScheduledExecutorService scheduler;
  private final ScheduledFuture<?> task;
  private long samples;
  private double systemSum;
  private double systemMax;
  private double processSum;
  private double processMax;

  private HostLoad(long intervalMs) {
    this.os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-generator-host-load");
              t.setDaemon(true);
              return t;
            });
    this.task =
        scheduler.scheduleAtFixedRate(this::sample, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  /** Starts sampling every {@code intervalMs}. */
  public static HostLoad start(long intervalMs) {
    return new HostLoad(intervalMs);
  }

  /** The current system CPU load (0..1), or -1 when unavailable. */
  public double currentSystemLoad() {
    return os.getCpuLoad();
  }

  private synchronized void sample() {
    double system = os.getCpuLoad();
    double process = os.getProcessCpuLoad();
    if (system < 0 || process < 0) {
      return;
    }
    samples++;
    systemSum += system;
    systemMax = Math.max(systemMax, system);
    processSum += process;
    processMax = Math.max(processMax, process);
  }

  /** Averages and maxima so far (fractions of all logical CPUs). */
  public synchronized Map<String, Object> summary() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("logicalCpus", os.getAvailableProcessors());
    m.put("totalMemoryBytes", os.getTotalMemorySize());
    m.put("samples", samples);
    m.put("systemCpuAvg", samples == 0 ? -1.0 : systemSum / samples);
    m.put("systemCpuMax", samples == 0 ? -1.0 : systemMax);
    m.put("generatorCpuAvg", samples == 0 ? -1.0 : processSum / samples);
    m.put("generatorCpuMax", samples == 0 ? -1.0 : processMax);
    return m;
  }

  @Override
  public void close() {
    task.cancel(false);
    scheduler.shutdownNow();
  }
}
