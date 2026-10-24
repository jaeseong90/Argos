/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Machine conditions during a run (the bench machines are shared: a rate or a latency without the
 * load at that moment is not comparable). A daemon thread samples the system CPU load every
 * interval (min, mean, max: constant memory); the CPU time of this process and, optionally, of
 * another process (the app under load) is read at start and close. With a timeline limit, the first
 * {@code timelineLimit} samples are also kept one by one (system CPU and the watched process's
 * cores per interval): bench runs only, the demo generator keeps constant memory.
 */
final class MachineSampler implements AutoCloseable {

  private final Duration interval;
  private final OptionalLong watchedPid;
  private final com.sun.management.OperatingSystemMXBean os;
  private final Thread thread;
  private final long startNanos;
  private final long ownCpuStart;
  private final Optional<Duration> watchedCpuStart;
  private final double[] timelineSeconds;
  private final double[] timelineCpu;
  private final double[] timelineWatchedCores;
  private volatile boolean closed;
  private double min = Double.NaN;
  private double max = Double.NaN;
  private double sum;
  private long samples;
  private int timelineSize;
  private long lastSampleNanos;
  private Optional<Duration> lastWatchedCpu;
  private Map<String, Object> result;

  private MachineSampler(Duration interval, OptionalLong watchedPid, int timelineLimit) {
    if (timelineLimit < 0 || timelineLimit > 100_000) {
      throw new IllegalArgumentException("timeline limit must be 0..100000");
    }
    this.interval = interval;
    this.watchedPid = watchedPid;
    OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
    this.os = bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    this.startNanos = System.nanoTime();
    this.lastSampleNanos = startNanos;
    this.ownCpuStart = os == null ? -1 : os.getProcessCpuTime();
    this.watchedCpuStart = cpuOf(watchedPid);
    this.lastWatchedCpu = watchedCpuStart;
    this.timelineSeconds = new double[timelineLimit];
    this.timelineCpu = new double[timelineLimit];
    this.timelineWatchedCores = new double[timelineLimit];
    if (os != null) {
      os.getCpuLoad(); // the first reading primes the measurement
    }
    this.thread = new Thread(this::loop, "demo-load-machine");
    this.thread.setDaemon(true);
  }

  /** Starts sampling every {@code interval}; {@code watchedPid} is the app process, if known. */
  static MachineSampler start(Duration interval, OptionalLong watchedPid) {
    return start(interval, watchedPid, 0);
  }

  /** As {@link #start(Duration, OptionalLong)}, keeping the first {@code timelineLimit} samples. */
  static MachineSampler start(Duration interval, OptionalLong watchedPid, int timelineLimit) {
    MachineSampler sampler = new MachineSampler(interval, watchedPid, timelineLimit);
    sampler.thread.start();
    return sampler;
  }

  private void loop() {
    while (!closed) {
      try {
        Thread.sleep(interval.toMillis());
      } catch (InterruptedException e) {
        return;
      }
      sample();
    }
  }

  private synchronized void sample() {
    if (os == null) {
      return;
    }
    double load = os.getCpuLoad();
    if (load < 0 || Double.isNaN(load)) {
      return;
    }
    double percent = load * 100;
    min = Double.isNaN(min) ? percent : Math.min(min, percent);
    max = Double.isNaN(max) ? percent : Math.max(max, percent);
    sum += percent;
    samples++;
    if (timelineSize < timelineSeconds.length) {
      long now = System.nanoTime();
      double watchedCores = Double.NaN;
      if (watchedPid.isPresent()) {
        Optional<Duration> cpu = cpuOf(watchedPid);
        if (cpu.isPresent() && lastWatchedCpu.isPresent() && now > lastSampleNanos) {
          watchedCores =
              cpu.get().minus(lastWatchedCpu.get()).toNanos() / (double) (now - lastSampleNanos);
        }
        lastWatchedCpu = cpu;
      }
      timelineSeconds[timelineSize] = (now - startNanos) / 1e9;
      timelineCpu[timelineSize] = percent;
      timelineWatchedCores[timelineSize] = watchedCores;
      timelineSize++;
      lastSampleNanos = now;
    }
  }

  /** Stops sampling (idempotent) and returns the result. */
  synchronized Map<String, Object> result() {
    if (result != null) {
      return result;
    }
    closed = true;
    thread.interrupt();
    double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put(
        "os",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"));
    machine.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    if (os != null) {
      machine.put("totalMemoryMb", os.getTotalMemorySize() / (1024 * 1024));
      machine.put("freeMemoryMbAtEnd", os.getFreeMemorySize() / (1024 * 1024));
    }
    Map<String, Object> cpu = new LinkedHashMap<>();
    cpu.put("samples", samples);
    cpu.put("intervalMs", interval.toMillis());
    cpu.put("min", min);
    cpu.put("mean", samples == 0 ? Double.NaN : sum / samples);
    cpu.put("max", max);
    machine.put("systemCpuLoadPercent", cpu);
    machine.put("elapsedSeconds", elapsedSeconds);
    if (os != null && ownCpuStart >= 0) {
      double seconds = (os.getProcessCpuTime() - ownCpuStart) / 1e9;
      machine.put("generatorCpuSeconds", seconds);
      machine.put("generatorCpuCores", elapsedSeconds > 0 ? seconds / elapsedSeconds : Double.NaN);
    }
    if (watchedPid.isPresent()) {
      Map<String, Object> watched = new LinkedHashMap<>();
      watched.put("pid", watchedPid.getAsLong());
      Optional<Duration> end = cpuOf(watchedPid);
      if (watchedCpuStart.isPresent() && end.isPresent()) {
        double seconds = end.get().minus(watchedCpuStart.get()).toNanos() / 1e9;
        watched.put("cpuSeconds", seconds);
        watched.put("cpuCores", elapsedSeconds > 0 ? seconds / elapsedSeconds : Double.NaN);
      } else {
        watched.put("cpuSeconds", null);
        watched.put("note", "CPU time of the process not readable");
      }
      machine.put("watchedProcess", watched);
    }
    if (timelineSeconds.length > 0) {
      // Rows of [seconds since start, system CPU %, watched process cores (null without --pid)].
      List<Object> rows = new ArrayList<>(timelineSize);
      for (int i = 0; i < timelineSize; i++) {
        rows.add(List.of(timelineSeconds[i], timelineCpu[i], timelineWatchedCores[i]));
      }
      machine.put("timelineColumns", List.of("seconds", "systemCpuPercent", "watchedCores"));
      machine.put("timeline", rows);
      machine.put("timelineTruncated", samples > timelineSize);
    }
    result = machine;
    return result;
  }

  @Override
  public void close() {
    var unused = result();
  }

  private static Optional<Duration> cpuOf(OptionalLong pid) {
    if (pid.isEmpty()) {
      return Optional.empty();
    }
    return ProcessHandle.of(pid.getAsLong()).flatMap(h -> h.info().totalCpuDuration());
  }
}
