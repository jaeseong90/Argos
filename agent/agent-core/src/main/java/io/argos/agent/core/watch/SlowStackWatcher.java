/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.RuntimeConfig;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TraceClock;
import io.argos.agent.core.trace.TracerImpl;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Stack samples of slow transactions while they run (10 §13.1 {@code SlowStackWatcher}, F-A19,
 * T-AG-064): on every 1 s tick of {@code argos-watch}, the transactions of the {@link
 * ActiveRegistry} that have run at least the slow threshold of their service ({@code
 * sampling.profile.slow-threshold-ms}, per service, default 1,000 ms), have fewer than {@code
 * transaction.slow-stack.max-captures} (5) samples and whose last sample is at least {@code
 * interval-ms} (3,000) old are candidates. At most {@code max-threads-per-cycle} (10) of them are
 * sampled per cycle, those with the fewest samples first and, among those, the longest running:
 *
 * <ul>
 *   <li>platform threads with <b>one</b> batched {@link ThreadMXBean#getThreadInfo(long[], int)}
 *       call per cycle, at most {@code transaction.slow-stack.depth} (64) frames;
 *   <li>virtual threads (which {@code ThreadMXBean} does not report) one by one with {@link
 *       Thread#getStackTrace()}, cut to the same depth (10 §8, {@link VirtualThreads}).
 * </ul>
 *
 * Each sample becomes a top-level {@code STACK} step of its transaction ({@link
 * TracerImpl#recordStack}: frames {@code class.method(file:line)}; ignored once the transaction
 * ended, and never kept in summary mode). Only transactions whose begin thread still runs them are
 * sampled: a request in servlet async mode ({@code TraceContext.asyncStarted}) has handed its
 * thread back to the pool, whose stack would show another request.
 *
 * <p><b>Cost</b>: the time of the capture part of each cycle and the number of stacks go to {@link
 * AgentCounters#addStackCapture} ({@code argos_agent_stack_capture_seconds_total}, self-diagnostics
 * {@code stack_capture_seconds_total}). After {@value #OVER_BUDGET_CYCLES} consecutive cycles over
 * {@value #BUDGET_MILLIS} ms the sampler disables itself with one WARN; it runs again when the
 * configuration enables it anew: {@code transaction.slow-stack.enabled} seen {@code false} and then
 * {@code true}, or a new remote configuration state that sets it to {@code true} (a {@code
 * ConfigPush} of {@code transaction.slow-stack.enabled=true}).
 *
 * <p>The {@code transaction.slow-stack.*} values and the slow thresholds are read from the
 * configuration in force at each tick ({@link RuntimeConfig#current()}, else the start
 * configuration), so a remote change applies at the next tick. Runs on {@code argos-watch} only;
 * never touches or waits for an application thread (reading another thread's stack is the JVM's
 * work, at a safepoint for platform threads); every array is bounded by {@code
 * max-threads-per-cycle} (at most 100).
 */
public final class SlowStackWatcher implements Runnable {
  /** Cost budget of one cycle (10 §13.1). */
  public static final long BUDGET_MILLIS = 50L;

  /** Consecutive cycles over {@link #BUDGET_MILLIS} that disable the sampler (10 §13.1). */
  public static final int OVER_BUDGET_CYCLES = 10;

  private static final long BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS);

  /** Where the sampler reads its configuration (seam for tests). */
  public interface Settings {
    /** The configuration in force now. */
    AgentConfig config();

    /** An object that changes (by identity) whenever a new configuration state is published. */
    Object version();

    /**
     * True when the state in force carries a remote {@code transaction.slow-stack.enabled=true}.
     */
    boolean remoteEnabled();
  }

  private final ActiveRegistry registry;
  private final TracerImpl tracer;
  private final TraceClock clock;
  private final AgentCounters counters;
  private final Settings settings;
  private final Predicate<Thread> virtual;
  private ThreadMXBean threads;

  private boolean lastEnabled = true;
  private boolean autoDisabled;
  private Object disabledVersion;
  private int overBudget;
  private long disabledTimes;

  // The candidates of one cycle (argos-watch only), bounded by max-threads-per-cycle.
  private TraceContext[] picked = new TraceContext[0];
  private Thread[] pickedThreads = new Thread[0];
  private int[] pickedCaptures = new int[0];
  private long[] pickedElapsed = new long[0];
  private int count;

  /**
   * The sampler of the agent: the running configuration ({@link RuntimeConfig#current()}, else
   * {@code startConfig}), the platform {@code ThreadMXBean} (looked up at the first capture, never
   * on the premain thread) and {@link VirtualThreads#isVirtual}.
   */
  public SlowStackWatcher(ActiveRegistry registry, TracerImpl tracer, AgentConfig startConfig) {
    this(
        registry,
        tracer,
        TraceClock.SYSTEM,
        tracer == null ? null : tracer.counters(),
        runningSettings(startConfig),
        null,
        null);
  }

  /**
   * @param threads the bean of the batched platform stacks; null: the platform bean at the first
   *     capture
   * @param virtual which threads are virtual; null: {@link VirtualThreads#isVirtual}
   */
  public SlowStackWatcher(
      ActiveRegistry registry,
      TracerImpl tracer,
      TraceClock clock,
      AgentCounters counters,
      Settings settings,
      ThreadMXBean threads,
      Predicate<Thread> virtual) {
    this.registry = registry;
    this.tracer = tracer;
    this.clock = clock == null ? TraceClock.SYSTEM : clock;
    this.counters = counters;
    this.settings = settings;
    this.threads = threads;
    this.virtual =
        virtual != null
            ? virtual
            : new Predicate<Thread>() {
              @Override
              public boolean test(Thread thread) {
                return VirtualThreads.isVirtual(thread);
              }
            };
  }

  /** {@link Settings} of the running agent: {@link RuntimeConfig#current()}, else {@code start}. */
  public static Settings runningSettings(final AgentConfig start) {
    return new Settings() {
      @Override
      public AgentConfig config() {
        RuntimeConfig running = RuntimeConfig.current();
        return running == null ? start : running.config();
      }

      @Override
      public Object version() {
        RuntimeConfig running = RuntimeConfig.current();
        return running == null ? start : running.state();
      }

      @Override
      public boolean remoteEnabled() {
        RuntimeConfig running = RuntimeConfig.current();
        return running != null
            && "true"
                .equalsIgnoreCase(running.state().overlay().get(ConfigKeys.SLOW_STACK_ENABLED));
      }
    };
  }

  /** True while the sampler is off after too many cycles over its budget. */
  public boolean autoDisabled() {
    return autoDisabled;
  }

  /** How often the sampler disabled itself (one WARN each). */
  public long disabledTimes() {
    return disabledTimes;
  }

  @Override
  public void run() {
    if (registry == null || tracer == null || settings == null) {
      return;
    }
    AgentConfig config = settings.config();
    if (config == null) {
      return;
    }
    boolean enabled = config.slowStackEnabled();
    if (autoDisabled
        && ((enabled && !lastEnabled)
            || (!Objects.equals(settings.version(), disabledVersion)
                && settings.remoteEnabled()))) {
      autoDisabled = false;
      overBudget = 0;
      AgentLogger.info("slow stack sampling enabled again by the configuration");
    }
    lastEnabled = enabled;
    if (!enabled || autoDisabled) {
      return;
    }
    int maxCaptures = config.slowStackMaxCaptures();
    int perCycle = Math.max(1, Math.min(100, config.slowStackMaxThreadsPerCycle()));
    int depth = Math.max(1, Math.min(StepBuffer.MAX_STACK_FRAMES, config.slowStackDepth()));
    if (maxCaptures <= 0) {
      return;
    }
    select(
        config,
        clock.nanoTime(),
        maxCaptures,
        TimeUnit.MILLISECONDS.toNanos(Math.max(0, config.slowStackIntervalMs())),
        perCycle);
    if (count == 0) {
      overBudget = 0;
      return;
    }
    long start = clock.nanoTime();
    int taken = capture(depth);
    long cost = Math.max(0L, clock.nanoTime() - start);
    if (counters != null) {
      counters.addStackCapture(cost, taken);
    }
    budget(cost);
  }

  /** Counts a cycle against the budget; disables the sampler after too many costly ones. */
  private void budget(long cost) {
    if (cost <= BUDGET_NANOS) {
      overBudget = 0;
      return;
    }
    if (++overBudget >= OVER_BUDGET_CYCLES) {
      autoDisabled = true;
      disabledVersion = settings.version();
      overBudget = 0;
      disabledTimes++;
      AgentLogger.warn(
          "slow stack sampling disabled: {} consecutive cycles took more than {} ms each; set"
              + " transaction.slow-stack.enabled=true (remote configuration) to enable it again",
          OVER_BUDGET_CYCLES,
          BUDGET_MILLIS);
    }
  }

  // --- selection --------------------------------------------------------------------------------

  private void select(
      final AgentConfig config,
      final long now,
      final int maxCaptures,
      final long intervalNanos,
      int perCycle) {
    if (picked.length != perCycle) {
      picked = new TraceContext[perCycle];
      pickedThreads = new Thread[perCycle];
      pickedCaptures = new int[perCycle];
      pickedElapsed = new long[perCycle];
    }
    count = 0;
    registry.forEach(
        new Consumer<TraceContext>() {
          @Override
          public void accept(TraceContext context) {
            consider(context, config, now, maxCaptures, intervalNanos);
          }
        });
  }

  private void consider(
      TraceContext context, AgentConfig config, long now, int maxCaptures, long intervalNanos) {
    if (context.isEnded() || context.asyncStarted) {
      return;
    }
    long elapsed = now - context.startNanos;
    if (elapsed < TimeUnit.MILLISECONDS.toNanos(config.slowThresholdMs(context.serviceName))) {
      return;
    }
    int captures = TracerImpl.stackCaptures(context);
    if (captures < 0 || captures >= maxCaptures) {
      return;
    }
    if (captures > 0 && now - TracerImpl.lastStackNanos(context) < intervalNanos) {
      return;
    }
    Thread thread = context.thread.get();
    if (thread == null || !thread.isAlive()) {
      return;
    }
    insert(context, thread, captures, elapsed);
  }

  /** Keeps the best {@code picked.length} candidates: fewest samples, then longest running. */
  private void insert(TraceContext context, Thread thread, int captures, long elapsed) {
    int at = count;
    while (at > 0 && before(captures, elapsed, at - 1)) {
      at--;
    }
    if (at >= picked.length) {
      return;
    }
    int last = Math.min(count, picked.length - 1);
    for (int i = last; i > at; i--) {
      picked[i] = picked[i - 1];
      pickedThreads[i] = pickedThreads[i - 1];
      pickedCaptures[i] = pickedCaptures[i - 1];
      pickedElapsed[i] = pickedElapsed[i - 1];
    }
    picked[at] = context;
    pickedThreads[at] = thread;
    pickedCaptures[at] = captures;
    pickedElapsed[at] = elapsed;
    if (count < picked.length) {
      count++;
    }
  }

  private boolean before(int captures, long elapsed, int index) {
    return captures < pickedCaptures[index]
        || (captures == pickedCaptures[index] && elapsed > pickedElapsed[index]);
  }

  // --- capture ----------------------------------------------------------------------------------

  /** Samples the picked transactions; returns the STACK steps recorded. */
  private int capture(int depth) {
    int platform = 0;
    for (int i = 0; i < count; i++) {
      if (!isVirtual(i)) {
        platform++;
      }
    }
    int taken = 0;
    if (platform > 0) {
      long[] ids = new long[platform];
      int[] index = new int[platform];
      int p = 0;
      for (int i = 0; i < count; i++) {
        if (!isVirtual(i)) {
          ids[p] = pickedThreads[i].getId();
          index[p++] = i;
        }
      }
      ThreadInfo[] infos = platformStacks(ids, depth);
      long at = clock.nanoTime();
      for (int k = 0; infos != null && k < infos.length && k < platform; k++) {
        ThreadInfo info = infos[k];
        if (info != null && info.getThreadId() == ids[k]) {
          taken += record(index[k], info.getStackTrace(), depth, at);
        }
      }
    }
    for (int i = 0; i < count; i++) {
      if (isVirtual(i)) {
        StackTraceElement[] frames;
        try {
          frames = pickedThreads[i].getStackTrace();
        } catch (Throwable t) {
          AgentLogger.warnOnce(
              "slow-stack.virtual", "virtual thread stack sample failed: {}", t.toString());
          frames = null;
        }
        taken += record(i, frames, depth, clock.nanoTime());
      }
    }
    for (int i = 0; i < count; i++) {
      picked[i] = null;
      pickedThreads[i] = null;
    }
    return taken;
  }

  private boolean isVirtual(int i) {
    return picked[i].virtualThread || virtual.test(pickedThreads[i]);
  }

  private int record(int i, StackTraceElement[] frames, int depth, long at) {
    if (frames == null || frames.length == 0) {
      return 0;
    }
    return tracer.recordStack(picked[i], pickedThreads[i], frames, depth, at) ? 1 : 0;
  }

  /** The one batched {@code getThreadInfo} of the cycle; null when it failed. */
  private ThreadInfo[] platformStacks(long[] ids, int depth) {
    try {
      ThreadMXBean bean = threads;
      if (bean == null) {
        bean = ManagementFactory.getThreadMXBean();
        threads = bean;
      }
      return bean.getThreadInfo(ids, depth);
    } catch (Throwable t) {
      AgentLogger.warnOnce("slow-stack.platform", "slow stack sample failed: {}", t.toString());
      return null;
    }
  }
}
