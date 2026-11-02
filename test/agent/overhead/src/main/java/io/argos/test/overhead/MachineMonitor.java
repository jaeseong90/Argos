/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Machine conditions while something is measured (the machine is shared: a number without the load
 * at that moment is not comparable). A daemon thread samples, every interval, the system CPU load,
 * the available physical memory and optional cumulative CPU counters (nanoseconds) such as the app
 * process and the k6 process.
 */
final class MachineMonitor implements AutoCloseable {
  private static final OperatingSystemMXBean OS =
      ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

  /** One sample: seconds since start, system CPU %, available MB, counters in seconds (or NaN). */
  record Sample(double seconds, double systemCpuPercent, long availableMb, List<Double> counters) {}

  private final Duration interval;
  private final Map<String, LongSupplier> counters;
  private final List<Sample> samples = new ArrayList<>();
  private final long start;
  private final Thread thread;
  private volatile boolean stopped;

  private MachineMonitor(Duration interval, Map<String, LongSupplier> counters) {
    this.interval = interval;
    this.counters = new LinkedHashMap<>(counters);
    this.start = System.nanoTime();
    this.thread =
        Thread.ofPlatform().daemon().name("overhead-machine-monitor").unstarted(this::loop);
  }

  /**
   * Starts sampling. {@code counters} map a name to a cumulative CPU counter in nanoseconds
   * (negative: unknown).
   */
  static MachineMonitor start(Duration interval, Map<String, LongSupplier> counters) {
    MachineMonitor monitor = new MachineMonitor(interval, counters);
    OS.getCpuLoad(); // primes the system CPU measurement
    monitor.addSample();
    monitor.thread.start();
    return monitor;
  }

  /** Names of the counters, in sample order. */
  List<String> counterNames() {
    return new ArrayList<>(counters.keySet());
  }

  private void loop() {
    while (!stopped) {
      try {
        Thread.sleep(interval.toMillis());
      } catch (InterruptedException e) {
        return;
      }
      addSample();
    }
  }

  private void addSample() {
    double load = OS.getCpuLoad();
    List<Double> values = new ArrayList<>();
    for (LongSupplier counter : counters.values()) {
      long nanos;
      try {
        nanos = counter.getAsLong();
      } catch (RuntimeException e) {
        nanos = -1;
      }
      values.add(nanos < 0 ? Double.NaN : nanos / 1e9);
    }
    Sample sample =
        new Sample(
            (System.nanoTime() - start) / 1e9,
            load < 0 ? Double.NaN : load * 100,
            availableMemoryMb(),
            values);
    synchronized (samples) {
      samples.add(sample);
    }
  }

  /** Stops sampling and returns every sample (the first one is taken at start). */
  List<Sample> stop() {
    stopped = true;
    thread.interrupt();
    try {
      thread.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    addSample();
    synchronized (samples) {
      return new ArrayList<>(samples);
    }
  }

  @Override
  public void close() {
    if (!stopped) {
      var unused = stop();
    }
  }

  static long availableMemoryMb() {
    return OS.getFreeMemorySize() / (1024 * 1024);
  }

  static long totalMemoryMb() {
    return OS.getTotalMemorySize() / (1024 * 1024);
  }

  /** System CPU load in percent over the next {@code over} (blocks). */
  static double cpuLoadOver(Duration over) throws InterruptedException {
    OS.getCpuLoad();
    Thread.sleep(over.toMillis());
    double load = OS.getCpuLoad();
    return load < 0 ? Double.NaN : load * 100;
  }

  /** JSON form: summary statistics and the timeline rows. */
  static Map<String, Object> toJson(List<Sample> samples, List<String> counterNames) {
    Map<String, Object> out = new LinkedHashMap<>();
    double[] cpu =
        samples.stream()
            .mapToDouble(Sample::systemCpuPercent)
            .filter(v -> !Double.isNaN(v))
            .toArray();
    double[] memory = samples.stream().mapToDouble(s -> (double) s.availableMb()).toArray();
    out.put("samples", samples.size());
    out.put("systemCpuPercentMean", Stats.mean(cpu));
    out.put("systemCpuPercentMin", Stats.min(cpu));
    out.put("systemCpuPercentMax", Stats.max(cpu));
    out.put("availableMemoryMbMin", Stats.min(memory));
    List<String> columns = new ArrayList<>(List.of("seconds", "systemCpuPercent", "availableMb"));
    for (String name : counterNames) {
      columns.add(name + "CpuSeconds");
    }
    out.put("timelineColumns", columns);
    List<Object> rows = new ArrayList<>();
    for (Sample s : samples) {
      List<Object> row = new ArrayList<>();
      row.add(round3(s.seconds()));
      row.add(round3(s.systemCpuPercent()));
      row.add(s.availableMb());
      for (double c : s.counters()) {
        row.add(round3(c));
      }
      rows.add(row);
    }
    out.put("timeline", rows);
    return out;
  }

  static double round3(double v) {
    return Double.isNaN(v) ? v : Math.round(v * 1000) / 1000.0;
  }
}
