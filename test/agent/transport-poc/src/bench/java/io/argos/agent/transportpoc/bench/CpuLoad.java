/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Machine CPU load sampler (whole host, all processes; same quantity as Win32_Processor
 * LoadPercentage): one sample per second on a daemon thread, kept for the last 30 minutes. Shared
 * machine: every run records the load right before it and the mean during it.
 */
final class CpuLoad implements AutoCloseable {
  private static final int MAX_SAMPLES = 1800;

  private record Sample(long atMillis, double percent) {}

  private final OperatingSystemMXBean os =
      (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
  private final Deque<Sample> samples = new ArrayDeque<>();
  private final Thread thread;
  private volatile boolean running = true;

  CpuLoad() {
    os.getCpuLoad();
    thread = new Thread(this::loop, "cpu-load");
    thread.setDaemon(true);
    thread.start();
  }

  private void loop() {
    while (running) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      }
      double load = os.getCpuLoad();
      if (load >= 0) {
        synchronized (samples) {
          samples.addLast(new Sample(System.currentTimeMillis(), load * 100.0));
          if (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
          }
        }
      }
    }
  }

  /** The most recent sample in percent, or -1 before the first one. */
  double latest() {
    synchronized (samples) {
      return samples.isEmpty() ? -1 : samples.getLast().percent();
    }
  }

  /** Mean of the samples taken in [from, to] (epoch ms), or -1 when there is none. */
  double mean(long from, long to) {
    double sum = 0;
    int count = 0;
    synchronized (samples) {
      for (Sample sample : samples) {
        if (sample.atMillis() >= from && sample.atMillis() <= to) {
          sum += sample.percent();
          count++;
        }
      }
    }
    return count == 0 ? -1 : sum / count;
  }

  /** Blocks until the latest sample is below {@code percent} or {@code maxWaitMillis} passed. */
  double waitForQuiet(double percent, long maxWaitMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + maxWaitMillis;
    double load = latest();
    while ((load < 0 || load > percent) && System.currentTimeMillis() < deadline) {
      Thread.sleep(1000);
      load = latest();
    }
    return load;
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
  }
}
