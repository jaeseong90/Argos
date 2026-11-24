/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.log.AgentLogger;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The agent's own CPU guard (10 §13.1 {@code OverheadMonitor}, T-AG-064f): on the {@code
 * argos-watch} tick it measures the CPU time of the agent threads ({@link ArgosThread}) over
 * one-minute windows. When a window used more than {@value #LIMIT_PERCENT}% of the host's CPU
 * (available processors × the window, the "share of the whole host" of 18 §7.4; WO-19 plan
 * assumption 6, no configuration key), the profile {@code normal-rate} is halved ({@link Lowering},
 * the overhead cap of {@code SamplingControl}, which never raises the configured rate or lifts the
 * server's throttle), with one {@code AgentEvent SELF_CPU_HIGH} and one WARN. After {@value
 * #CALM_WINDOWS} consecutive windows (5 minutes) at or below the limit the rate is restored (one
 * INFO). A window over the limit while lowered keeps it lowered and starts the 5 minutes anew. The
 * first window after the start is measured but not evaluated: it holds the one-off start work of
 * the agent threads (transport and TLS set-up, the first dictionary sends), not their steady cost.
 *
 * <p>Works with every exporter: the measurement and the cap need no transport; the event goes to
 * the collector session when there is one ({@link Events}). The CPU time is cumulative over the
 * agent threads seen so far ({@link #agentThreadsCpu()}): a thread that ends keeps its share, the
 * same rule as {@code argos_agent_cpu_seconds_total}. Runs on {@code argos-watch} only; at most
 * {@value #MAX_THREADS} agent threads are followed.
 */
public final class OverheadMonitor implements Runnable {
  /** The share of the host CPU in percent above which the rate is lowered (10 §13.1). */
  public static final double LIMIT_PERCENT = 1.0;

  /** Window length (10 §13.1 "1분 창"). */
  public static final long WINDOW_SECONDS = 60L;

  /** Calm windows before the rate is restored (10 §13.1 "복귀는 5분 후"). */
  public static final int CALM_WINDOWS = 5;

  static final int MAX_THREADS = 256;

  private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(WINDOW_SECONDS);

  /** Cumulative CPU nanoseconds of the agent threads, or -1 when not measurable. */
  public interface CpuSource {
    long agentCpuNanos();
  }

  /** The overhead cap of the profile sampler ({@code SamplingControl.overheadLowered}). */
  public interface Lowering {
    void lowered(boolean on);
  }

  /** Where {@code AgentEvent SELF_CPU_HIGH} goes (the collector session, if any). */
  public interface Events {
    void selfCpuHigh(String detail);
  }

  private final CpuSource cpu;
  private final LongSupplier nanoClock;
  private final int processors;
  private final Lowering lowering;
  private final Events events;

  private long windowStart = -1L;
  private boolean warmedUp;
  private long cpuAtStart = -1L;
  private boolean lowered;
  private int calm;
  private long lowerings;
  private double lastPercent = Double.NaN;

  /**
   * @param cpu the agent threads' cumulative CPU time; null: {@link #agentThreadsCpu()}
   * @param nanoClock {@code System.nanoTime} equivalent; null: the system clock
   * @param processors available processors of the host (at least 1)
   * @param lowering the sampler's overhead cap
   * @param events {@code SELF_CPU_HIGH} sink, may be null
   */
  public OverheadMonitor(
      CpuSource cpu, LongSupplier nanoClock, int processors, Lowering lowering, Events events) {
    this.cpu = cpu != null ? cpu : agentThreadsCpu();
    this.nanoClock =
        nanoClock != null
            ? nanoClock
            : new LongSupplier() {
              @Override
              public long getAsLong() {
                return System.nanoTime();
              }
            };
    this.processors = Math.max(1, processors);
    this.lowering = lowering;
    this.events = events;
  }

  /** True while the normal rate is halved. */
  public boolean lowered() {
    return lowered;
  }

  /** How often the rate was lowered (one event and one WARN each). */
  public long lowerings() {
    return lowerings;
  }

  /** Host CPU share of the agent threads in the last full window, in percent; NaN before. */
  public double lastPercent() {
    return lastPercent;
  }

  @Override
  public void run() {
    long now = nanoClock.getAsLong();
    if (windowStart < 0) {
      start(now);
      return;
    }
    long length = now - windowStart;
    if (length < WINDOW_NANOS) {
      return;
    }
    long used = cpu.agentCpuNanos();
    long before = cpuAtStart;
    windowStart = now;
    cpuAtStart = used;
    if (used < 0 || before < 0) {
      return;
    }
    double percent = 100.0 * Math.max(0L, used - before) / ((double) length * processors);
    lastPercent = percent;
    if (!warmedUp) {
      warmedUp = true;
      return;
    }
    evaluate(percent);
  }

  private void start(long now) {
    windowStart = now;
    cpuAtStart = cpu.agentCpuNanos();
  }

  private void evaluate(double percent) {
    if (percent > LIMIT_PERCENT) {
      calm = 0;
      if (!lowered) {
        lowered = true;
        lowerings++;
        if (lowering != null) {
          lowering.lowered(true);
        }
        if (events != null) {
          try {
            events.selfCpuHigh(
                "agent threads used "
                    + Math.round(percent * 100.0) / 100.0
                    + "% of the host CPU in one minute (limit "
                    + LIMIT_PERCENT
                    + "%); profile normal-rate halved");
          } catch (Throwable t) {
            AgentLogger.warnOnce("overhead.event", "self CPU event failed: {}", t.toString());
          }
        }
        AgentLogger.warn(
            "agent threads used {}% of the host CPU in the last minute (limit {}%); the profile"
                + " normal-rate is halved until {} calm minutes",
            Math.round(percent * 100.0) / 100.0, LIMIT_PERCENT, CALM_WINDOWS);
      }
      return;
    }
    if (lowered && ++calm >= CALM_WINDOWS) {
      lowered = false;
      calm = 0;
      if (lowering != null) {
        lowering.lowered(false);
      }
      AgentLogger.info(
          "agent CPU back under {}% of the host for {} minutes; the profile normal-rate is restored",
          LIMIT_PERCENT, CALM_WINDOWS);
    }
  }

  /**
   * The cumulative CPU time of the live {@link ArgosThread}s ({@code ThreadMXBean}): the increase
   * of each thread since the previous call is added up, so a thread that ends keeps its share; -1
   * when the JVM does not measure thread CPU time. At most {@value #MAX_THREADS} threads are
   * followed.
   */
  public static CpuSource agentThreadsCpu() {
    return new CpuSource() {
      private Map<Long, Long> last = new HashMap<Long, Long>();
      private long total;

      @Override
      public long agentCpuNanos() {
        try {
          ThreadMXBean bean = ManagementFactory.getThreadMXBean();
          if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
            return -1L;
          }
          Map<Long, Long> seen = new HashMap<Long, Long>();
          for (Thread thread : SelfDiagnosticsCollector.liveThreads()) {
            if (thread instanceof ArgosThread && seen.size() < MAX_THREADS) {
              long id = thread.getId();
              long now = bean.getThreadCpuTime(id);
              if (now >= 0) {
                Long before = last.get(id);
                total += before == null || now < before ? now : now - before;
                seen.put(id, now);
              }
            }
          }
          last = seen;
          return total;
        } catch (Throwable t) {
          return -1L;
        }
      }
    };
  }
}
