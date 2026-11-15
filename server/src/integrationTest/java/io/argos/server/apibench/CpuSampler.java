/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.apibench;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The CPU load of the whole machine during the bench (every process, the Docker VM with ClickHouse
 * included), sampled every 500 ms from {@code com.sun.management.OperatingSystemMXBean#getCpuLoad}:
 * a busy shared machine makes the numbers worthless, so the report shows it next to them.
 */
final class CpuSampler implements AutoCloseable {

  /** One sample: when (nanoTime) and the load (0..1). */
  record Sample(long nanos, double load) {}

  private final com.sun.management.OperatingSystemMXBean os =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
  private final List<Sample> samples = new ArrayList<>(); // guarded by this
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "api-bench-cpu");
            t.setDaemon(true);
            return t;
          });

  private final ScheduledFuture<?> task;

  CpuSampler() {
    task = timer.scheduleAtFixedRate(this::sample, 0, 500, TimeUnit.MILLISECONDS);
  }

  private void sample() {
    double load = os.getCpuLoad();
    if (load >= 0) {
      synchronized (this) {
        samples.add(new Sample(System.nanoTime(), load));
      }
    }
  }

  /**
   * The load between two {@code System.nanoTime()} values.
   *
   * @param mean mean load (0..1), or -1 without samples
   * @param max highest sample, or -1
   * @param count samples
   */
  record Window(double mean, double max, int count) {

    /** {@code 37 % (max 81 %, 12 samples)}, or {@code n/a}. */
    String text() {
      if (count == 0) {
        return "n/a";
      }
      return String.format(
          java.util.Locale.ROOT, "%.0f %% (max %.0f %%, %d samples)", mean * 100, max * 100, count);
    }
  }

  /** The samples between {@code fromNanos} and {@code toNanos}. */
  synchronized Window window(long fromNanos, long toNanos) {
    double sum = 0;
    double max = -1;
    int n = 0;
    for (Sample s : samples) {
      if (s.nanos() >= fromNanos && s.nanos() <= toNanos) {
        sum += s.load();
        max = Math.max(max, s.load());
        n++;
      }
    }
    return n == 0 ? new Window(-1, -1, 0) : new Window(sum / n, max, n);
  }

  /** The logical processors of the machine. */
  int processors() {
    return os.getAvailableProcessors();
  }

  /** The physical memory of the machine in bytes. */
  long totalMemory() {
    return os.getTotalMemorySize();
  }

  @Override
  public void close() {
    task.cancel(false);
    timer.shutdownNow();
  }
}
