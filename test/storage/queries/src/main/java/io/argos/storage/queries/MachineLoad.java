/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Samples the CPU load of the machine once per second (all processes, including the Docker VM), so
 * that every measurement can report the load it ran under: the PoC machine is shared with other
 * benchmarks (WO-02), and numbers from a busy machine are only reference values. Bounded: keeps the
 * last 24 hours of samples.
 *
 * <p>A sample taken at time t is the load of the second before t, so the load of an interval
 * [start, end] is the mean of the samples from the first one at or after {@code start} up to and
 * including the first one at or after {@code end}. A query shorter than a second therefore has a
 * sample only once the next sample after its end exists: compute the load when the report is
 * written ({@link #awaitSampleAfter}), not when the query returns.
 */
public final class MachineLoad implements AutoCloseable {

  private static final int MAX_SAMPLES = 86_400;

  private final com.sun.management.OperatingSystemMXBean os;
  private final Deque<long[]> samples = new ArrayDeque<>();
  private final Thread thread;
  private volatile boolean running = true;

  private MachineLoad(boolean sampling) {
    this.os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    if (sampling) {
      os.getCpuLoad(); // the first value is meaningless
      this.thread = new Thread(this::loop, "machine-load");
      this.thread.setDaemon(true);
    } else {
      this.thread = null;
    }
  }

  /** Starts sampling. */
  public static MachineLoad start() {
    MachineLoad load = new MachineLoad(true);
    load.thread.start();
    return load;
  }

  /** A load without the sampling thread; samples come from {@link #addSample} (tests). */
  static MachineLoad withoutSampling() {
    return new MachineLoad(false);
  }

  /** Adds a sample (load in [0, 1] of the second before {@code nanos}); samples come in order. */
  synchronized void addSample(long nanos, double load) {
    samples.addLast(new long[] {nanos, Math.round(load * 1e6)});
    if (samples.size() > MAX_SAMPLES) {
      samples.removeFirst();
    }
    notifyAll();
  }

  /** Logical processors of the machine. */
  public int processors() {
    return os.getAvailableProcessors();
  }

  /** Physical memory of the machine (bytes). */
  public long totalMemoryBytes() {
    return os.getTotalMemorySize();
  }

  /** Current load in [0, 1] (sampled now). */
  public double now() {
    double v = os.getCpuLoad();
    return v < 0 ? Double.NaN : v;
  }

  /**
   * Mean load in [0, 1] over the interval of two {@link System#nanoTime()} values: the samples from
   * the first one at or after {@code fromNanos} to the first one at or after {@code toNanos}
   * (inclusive). When no sample covers the interval yet (none at or after {@code fromNanos}), the
   * nearest earlier sample stands in; NaN without any sample.
   */
  public synchronized double mean(long fromNanos, long toNanos) {
    double sum = 0;
    int n = 0;
    long[] before = null;
    for (long[] s : samples) {
      if (s[0] < fromNanos) {
        before = s;
        continue;
      }
      sum += s[1] / 1e6;
      n++;
      if (s[0] >= toNanos) {
        break;
      }
    }
    if (n > 0) {
      return sum / n;
    }
    return before == null ? Double.NaN : before[1] / 1e6;
  }

  /** Highest load in [0, 1] over the same samples as {@link #mean}; NaN without any sample. */
  public synchronized double max(long fromNanos, long toNanos) {
    double max = Double.NaN;
    long[] before = null;
    for (long[] s : samples) {
      if (s[0] < fromNanos) {
        before = s;
        continue;
      }
      double v = s[1] / 1e6;
      max = Double.isNaN(max) ? v : Math.max(max, v);
      if (s[0] >= toNanos) {
        break;
      }
    }
    if (Double.isNaN(max) && before != null) {
      return before[1] / 1e6;
    }
    return max;
  }

  /**
   * Waits until a sample at or after {@code nanos} exists, at most {@code maxWait}; true when one
   * exists. Call before computing the load of the last measurements of a report.
   */
  public synchronized boolean awaitSampleAfter(long nanos, Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    while (samples.isEmpty() || samples.peekLast()[0] < nanos) {
      long left = deadline - System.nanoTime();
      if (left <= 0 || (thread == null && !running)) {
        return false;
      }
      try {
        wait(Math.max(1, left / 1_000_000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  private void loop() {
    while (running) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      double v = os.getCpuLoad();
      if (v >= 0) {
        addSample(System.nanoTime(), v);
      }
    }
  }

  @Override
  public void close() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
