/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TraceClock;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The slow-stack sampler of {@code argos-watch} (10 §13.1, F-A19, T-AG-064). */
@Tag("req:F-A19")
class SlowStackWatcherTest {
  static final long SECOND = TimeUnit.SECONDS.toNanos(1);

  /** A clock the test moves. */
  static final class ManualClock implements TraceClock {
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    void advance(long delta) {
      nanos.addAndGet(delta);
    }

    @Override
    public long nanoTime() {
      return nanos.get();
    }

    @Override
    public long currentTimeMillis() {
      return 1_800_000_000_000L + TimeUnit.NANOSECONDS.toMillis(nanos.get());
    }
  }

  /** The configuration the watcher reads at each tick. */
  static final class TestSettings implements SlowStackWatcher.Settings {
    volatile AgentConfig config;
    volatile Object version = new Object();
    volatile boolean remoteEnabled;

    TestSettings(Map<String, String> values) {
      config = AgentConfig.of(values);
    }

    void publish(Map<String, String> values, boolean remote) {
      config = AgentConfig.of(values);
      remoteEnabled = remote;
      version = new Object();
    }

    @Override
    public AgentConfig config() {
      return config;
    }

    @Override
    public Object version() {
      return version;
    }

    @Override
    public boolean remoteEnabled() {
      return remoteEnabled;
    }
  }

  /** The platform ThreadMXBean, counting its getThreadInfo calls (the batch seam of T-AG-064b). */
  static final class CountingBean implements InvocationHandler {
    final ThreadMXBean real = ManagementFactory.getThreadMXBean();
    final List<long[]> batches = Collections.synchronizedList(new ArrayList<long[]>());
    final List<Integer> depths = Collections.synchronizedList(new ArrayList<Integer>());
    final AtomicLong otherThreadInfoCalls = new AtomicLong();
    volatile Runnable onBatch;

    ThreadMXBean proxy() {
      return (ThreadMXBean)
          Proxy.newProxyInstance(
              ThreadMXBean.class.getClassLoader(), new Class<?>[] {ThreadMXBean.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getName().equals("getThreadInfo")) {
        if (args.length == 2 && args[0] instanceof long[] && args[1] instanceof Integer) {
          batches.add(((long[]) args[0]).clone());
          depths.add((Integer) args[1]);
          Runnable hook = onBatch;
          if (hook != null) {
            hook.run();
          }
        } else {
          otherThreadInfoCalls.incrementAndGet();
        }
      }
      try {
        return method.invoke(real, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  /** Threads that each run one transaction and wait {@code frames} calls deep until released. */
  final class Workers {
    final List<TraceContext> contexts = Collections.synchronizedList(new ArrayList<TraceContext>());
    final List<Thread> threads = new ArrayList<Thread>();
    final CountDownLatch release = new CountDownLatch(1);

    Workers(int count, final int frames) throws InterruptedException {
      final CountDownLatch ready = new CountDownLatch(count);
      for (int i = 0; i < count; i++) {
        final int n = i;
        Thread thread =
            new Thread(
                new Runnable() {
                  @Override
                  public void run() {
                    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /slow/" + n, clock.nanoTime());
                    contexts.add(ctx);
                    ready.countDown();
                    deep(frames);
                    tracer.end(ctx, 200, null, null, null, clock.nanoTime());
                  }
                },
                "slow-worker-" + i);
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      // Every worker parked in the deepest frame.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (Thread thread : threads) {
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
          Thread.sleep(2);
        }
      }
    }

    void deep(int frames) {
      if (frames > 0) {
        deep(frames - 1);
        return;
      }
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void finish() throws InterruptedException {
      release.countDown();
      for (Thread thread : threads) {
        thread.join(10_000);
      }
    }
  }

  final ManualClock clock = new ManualClock();
  final AgentCounters counters = new AgentCounters();
  final ActiveRegistry registry = new ActiveRegistry(64);
  final Dictionary dictionary = new Dictionary();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  final TracerImpl tracer =
      TracerImpl.builder()
          .clock(clock)
          .counters(counters)
          .activeRegistry(registry)
          .dictionary(dictionary)
          .sink(
              new TracerImpl.Sink() {
                @Override
                public void accept(Transaction tx) {
                  ended.add(tx);
                }
              })
          .build();
  final CountingBean bean = new CountingBean();
  Workers workers;

  @AfterEach
  void release() throws InterruptedException {
    if (workers != null) {
      workers.finish();
    }
  }

  private SlowStackWatcher watcher(TestSettings settings, Predicate<Thread> virtual) {
    return new SlowStackWatcher(registry, tracer, clock, counters, settings, bean.proxy(), virtual);
  }

  private static Map<String, String> values(String... keysAndValues) {
    Map<String, String> out = new HashMap<String, String>();
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      out.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }

  /** STACK steps of each ended transaction, by worker index (thread name). */
  private Map<String, List<long[]>> stacksByThread() throws InterruptedException {
    workers.finish();
    Map<String, List<long[]>> out = new HashMap<String, List<long[]>>();
    for (Transaction tx : ended) {
      StepBuffer steps = tx.steps();
      List<long[]> stacks = new ArrayList<long[]>();
      for (int seq = 0; seq < steps.size(); seq++) {
        if (steps.type(seq) == StepType.STACK) {
          assertThat(steps.parent(seq)).as("STACK steps are top level").isEqualTo(-1);
          assertThat(steps.elapsedNanos(seq)).isZero();
          assertThat(steps.threadName(seq)).isEqualTo(tx.threadName());
          stacks.add(steps.stack(seq));
        }
      }
      out.put(tx.threadName(), stacks);
    }
    return out;
  }

  private int stackSteps() {
    int total = 0;
    for (TraceContext ctx : workers.contexts) {
      total += Math.max(0, TracerImpl.stackCaptures(ctx));
    }
    return total;
  }

  @Test
  @DisplayName(
      "T-AG-064a at most 10 transactions per cycle, at most 5 samples each, a new sample only"
          + " interval-ms after the last; nothing below the slow threshold")
  void capsPerCycleAndPerTransaction() throws Exception {
    workers = new Workers(15, 8);
    SlowStackWatcher watcher = watcher(new TestSettings(values()), null);

    clock.advance(SECOND / 2);
    watcher.run();
    assertThat(bean.batches).as("below the 1,000 ms slow threshold: no sample").isEmpty();

    clock.advance(SECOND);
    watcher.run();
    assertThat(bean.batches).hasSize(1);
    assertThat(bean.batches.get(0)).as("first cycle: max-threads-per-cycle 10").hasSize(10);
    assertThat(stackSteps()).isEqualTo(10);

    watcher.run();
    assertThat(bean.batches.get(1)).as("second cycle: the 5 not sampled yet").hasSize(5);
    assertThat(stackSteps()).isEqualTo(15);

    watcher.run();
    clock.advance(SECOND);
    watcher.run();
    assertThat(bean.batches).as("within interval-ms 3,000 of their last sample: none").hasSize(2);

    for (int i = 0; i < 40; i++) {
      clock.advance(SECOND);
      watcher.run();
    }
    assertThat(stackSteps()).as("max-captures 5 per transaction").isEqualTo(15 * 5);
    for (long[] ids : bean.batches) {
      assertThat(ids.length).isBetween(1, 10);
    }

    Map<String, List<long[]>> stacks = stacksByThread();
    assertThat(stacks).hasSize(15);
    for (List<long[]> perTx : stacks.values()) {
      assertThat(perTx).hasSize(5);
    }
    assertThat(counters.stackCaptures()).isEqualTo(75);
  }

  @Test
  @DisplayName(
      "T-AG-064b one batched getThreadInfo(long[], depth) per cycle for the platform threads, no"
          + " per-thread call; the ids are the transactions' threads")
  void oneBatchedCallPerCycle() throws Exception {
    workers = new Workers(6, 4);
    SlowStackWatcher watcher = watcher(new TestSettings(values()), null);
    clock.advance(2 * SECOND);
    watcher.run();
    assertThat(bean.batches).hasSize(1);
    assertThat(bean.otherThreadInfoCalls.get()).isZero();
    Set<Long> expected = new HashSet<Long>();
    for (Thread thread : workers.threads) {
      expected.add(thread.getId());
    }
    Set<Long> asked = new HashSet<Long>();
    for (long id : bean.batches.get(0)) {
      asked.add(id);
    }
    assertThat(asked).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "T-AG-064c depth: getThreadInfo asks for transaction.slow-stack.depth (64), a STACK step"
          + " keeps at most 64 frames as class.method(file:line), the top frames first")
  void depthAndFrameText() throws Exception {
    workers = new Workers(1, 100);
    SlowStackWatcher watcher = watcher(new TestSettings(values()), null);
    clock.advance(2 * SECOND);
    watcher.run();
    assertThat(bean.depths).containsExactly(64);
    List<long[]> stacks = stacksByThread().get("slow-worker-0");
    assertThat(stacks).hasSize(1);
    long[] frames = stacks.get(0);
    assertThat(frames).hasSize(StepBuffer.MAX_STACK_FRAMES);
    List<String> texts = new ArrayList<String>();
    for (long id : frames) {
      texts.add(dictionary.text(id));
    }
    assertThat(texts).anyMatch(t -> t.contains("CountDownLatch.await("));
    assertThat(texts)
        .anyMatch(
            t ->
                t.matches(
                    "io\\.argos\\.agent\\.core\\.watch\\.SlowStackWatcherTest\\$Workers"
                        + "\\.deep\\(SlowStackWatcherTest\\.java:\\d+\\)"));
    assertThat(texts.indexOf(texts.stream().filter(t -> t.contains(".deep(")).findFirst().get()))
        .as("the waiting frames come before the recursion")
        .isGreaterThan(
            texts.indexOf(
                texts.stream().filter(t -> t.contains("CountDownLatch.await(")).findFirst().get()));

    TestSettings shallow = new TestSettings(values(ConfigKeys.SLOW_STACK_DEPTH, "5"));
    Workers more = new Workers(1, 100);
    try {
      SlowStackWatcher small = watcher(shallow, null);
      clock.advance(2 * SECOND);
      small.run();
      assertThat(bean.depths.get(bean.depths.size() - 1)).isEqualTo(5);
      assertThat(TracerImpl.stackCaptures(more.contexts.get(0))).isEqualTo(1);
    } finally {
      more.finish();
    }
  }

  @Test
  @DisplayName(
      "T-AG-064d virtual threads: never in the getThreadInfo batch, sampled one by one with"
          + " Thread.getStackTrace (VirtualThreads fallback), same depth cap")
  void virtualThreadFallback() throws Exception {
    workers = new Workers(4, 100);
    final Set<Thread> virtual = new HashSet<Thread>(workers.threads.subList(0, 2));
    SlowStackWatcher watcher =
        watcher(
            new TestSettings(values()),
            new Predicate<Thread>() {
              @Override
              public boolean test(Thread thread) {
                return virtual.contains(thread);
              }
            });
    clock.advance(2 * SECOND);
    watcher.run();
    assertThat(bean.batches).hasSize(1);
    Set<Long> asked = new HashSet<Long>();
    for (long id : bean.batches.get(0)) {
      asked.add(id);
    }
    assertThat(asked)
        .containsExactlyInAnyOrder(workers.threads.get(2).getId(), workers.threads.get(3).getId());
    Map<String, List<long[]>> stacks = stacksByThread();
    for (int i = 0; i < 4; i++) {
      List<long[]> perTx = stacks.get("slow-worker-" + i);
      assertThat(perTx).as("worker %d", i).hasSize(1);
      assertThat(perTx.get(0)).hasSize(StepBuffer.MAX_STACK_FRAMES);
    }
  }

  @Test
  @DisplayName(
      "T-AG-064e cost: each cycle's capture time goes to argos_agent_stack_capture_seconds_total;"
          + " 10 consecutive cycles over 50 ms disable the sampler with one WARN; a remote"
          + " transaction.slow-stack.enabled=true or false then true enables it again")
  void costBudgetAndReEnable() throws Exception {
    workers = new Workers(2, 4);
    Map<String, String> fast =
        values(ConfigKeys.SLOW_STACK_INTERVAL_MS, "100", ConfigKeys.SLOW_STACK_MAX_CAPTURES, "100");
    TestSettings settings = new TestSettings(fast);
    SlowStackWatcher watcher = watcher(settings, null);
    clock.advance(2 * SECOND);

    // Cheap cycles count, but never towards the budget.
    watcher.run();
    assertThat(counters.stackCaptureSeconds()).isGreaterThanOrEqualTo(0.0);
    long cheapBatches = bean.batches.size();

    bean.onBatch =
        new Runnable() {
          @Override
          public void run() {
            clock.advance(TimeUnit.MILLISECONDS.toNanos(60));
          }
        };
    for (int i = 0; i < 9; i++) {
      clock.advance(SECOND);
      watcher.run();
    }
    assertThat(watcher.autoDisabled()).as("9 costly cycles").isFalse();
    // One cheap cycle in between resets the run of costly cycles.
    bean.onBatch = null;
    clock.advance(SECOND);
    watcher.run();
    bean.onBatch =
        new Runnable() {
          @Override
          public void run() {
            clock.advance(TimeUnit.MILLISECONDS.toNanos(60));
          }
        };
    for (int i = 0; i < 9; i++) {
      clock.advance(SECOND);
      watcher.run();
    }
    assertThat(watcher.autoDisabled()).isFalse();
    clock.advance(SECOND);
    watcher.run();
    assertThat(watcher.autoDisabled()).as("10 consecutive cycles over 50 ms").isTrue();
    assertThat(watcher.disabledTimes()).as("one WARN").isEqualTo(1);
    assertThat(counters.stackCaptureSeconds()).as("19 cycles of 60 ms").isGreaterThan(1.13);
    assertThat(bean.batches).hasSize((int) cheapBatches + 20);

    int before = bean.batches.size();
    for (int i = 0; i < 3; i++) {
      clock.advance(SECOND);
      watcher.run();
    }
    assertThat(bean.batches).as("disabled: no capture").hasSize(before);
    assertThat(watcher.disabledTimes()).isEqualTo(1);

    // An unrelated new configuration state does not enable it.
    settings.publish(fast, false);
    clock.advance(SECOND);
    watcher.run();
    assertThat(watcher.autoDisabled()).isTrue();

    // A remote transaction.slow-stack.enabled=true does (next tick).
    bean.onBatch = null;
    settings.publish(fast, true);
    clock.advance(SECOND);
    watcher.run();
    assertThat(watcher.autoDisabled()).isFalse();
    assertThat(bean.batches).hasSize(before + 1);

    // Disabled again, then enabled by false followed by true (any configuration source).
    bean.onBatch =
        new Runnable() {
          @Override
          public void run() {
            clock.advance(TimeUnit.MILLISECONDS.toNanos(60));
          }
        };
    for (int i = 0; i < 10; i++) {
      clock.advance(SECOND);
      watcher.run();
    }
    assertThat(watcher.autoDisabled()).isTrue();
    assertThat(watcher.disabledTimes()).isEqualTo(2);
    bean.onBatch = null;
    Map<String, String> off = new HashMap<String, String>(fast);
    off.put(ConfigKeys.SLOW_STACK_ENABLED, "false");
    settings.config = AgentConfig.of(off);
    clock.advance(SECOND);
    watcher.run();
    assertThat(watcher.autoDisabled()).isTrue();
    settings.config = AgentConfig.of(fast);
    int beforeOn = bean.batches.size();
    clock.advance(SECOND);
    watcher.run();
    assertThat(watcher.autoDisabled()).isFalse();
    assertThat(bean.batches).hasSize(beforeOn + 1);
  }

  @Test
  @DisplayName(
      "T-AG-064e remote changes apply at the next tick: enabled=false stops sampling,"
          + " max-threads-per-cycle and the per-service slow threshold are read per tick")
  void runningConfigurationPerTick() throws Exception {
    workers = new Workers(5, 4);
    TestSettings settings = new TestSettings(values(ConfigKeys.SLOW_STACK_ENABLED, "false"));
    SlowStackWatcher watcher = watcher(settings, null);
    clock.advance(2 * SECOND);
    watcher.run();
    assertThat(bean.batches).isEmpty();

    settings.publish(values(ConfigKeys.SLOW_STACK_MAX_THREADS_PER_CYCLE, "3"), true);
    watcher.run();
    assertThat(bean.batches).hasSize(1);
    assertThat(bean.batches.get(0)).hasSize(3);

    // A per-service threshold above the elapsed time keeps the rest out.
    settings.publish(values(ConfigKeys.SLOW_THRESHOLD_SERVICES + "GET /slow/3", "60000"), true);
    for (TraceContext ctx : workers.contexts) {
      ctx.serviceName = "GET /slow/3";
    }
    watcher.run();
    assertThat(bean.batches).as("every remaining one is below its service threshold").hasSize(1);
  }

  @Test
  @DisplayName(
      "T-AG-064 STACK samples are never summarized: none in summary mode, none after the end,"
          + " none for a request in servlet async mode (its thread runs another request)")
  void summaryEndAndAsync() throws Exception {
    workers = new Workers(3, 2);
    TraceContext summarized = workers.contexts.get(0);
    TraceContext async = workers.contexts.get(1);
    TraceContext normal = workers.contexts.get(2);
    for (int i = 0; i < 1100; i++) {
      int seq = tracer.stepStart(summarized, StepType.METHOD, 1, 0, clock.nanoTime());
      tracer.stepEnd(summarized, seq, 0, false, 0, clock.nanoTime());
    }
    Transaction full = (Transaction) summarized.coreRef;
    assertThat(full.steps().summarized()).isTrue();
    int summaryKeys = full.steps().summaryKeys();
    async.asyncStarted = true;
    SlowStackWatcher watcher = watcher(new TestSettings(values()), null);
    clock.advance(2 * SECOND);
    watcher.run();
    assertThat(TracerImpl.stackCaptures(summarized))
        .as("summary mode: no further sample")
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(full.steps().summaryKeys()).isEqualTo(summaryKeys);
    assertThat(TracerImpl.stackCaptures(async)).isZero();
    assertThat(TracerImpl.stackCaptures(normal)).isEqualTo(1);

    workers.finish();
    assertThat(TracerImpl.stackCaptures(normal)).as("ended").isEqualTo(-1);
    StackTraceElement[] frames = Thread.currentThread().getStackTrace();
    assertThat(tracer.recordStack(normal, workers.threads.get(2), frames, 64, clock.nanoTime()))
        .isFalse();
  }

  @Test
  @DisplayName("frame text: class.method(file:line), line-free form, unknown source, native")
  void frameText() {
    assertThat(TracerImpl.frameText(new StackTraceElement("a.B", "m", "B.java", 12), true))
        .isEqualTo("a.B.m(B.java:12)");
    assertThat(TracerImpl.frameText(new StackTraceElement("a.B", "m", "B.java", 12), false))
        .isEqualTo("a.B.m(B.java)");
    assertThat(TracerImpl.frameText(new StackTraceElement("a.B", "m", null, -1), true))
        .isEqualTo("a.B.m(Unknown Source)");
    assertThat(TracerImpl.frameText(new StackTraceElement("a.B", "m", "B.java", -2), true))
        .isEqualTo("a.B.m(Native Method)");
    assertThat(TracerImpl.frameText(new StackTraceElement("a.B", "m", "B.java", -1), true))
        .isEqualTo("a.B.m(B.java)");
  }
}
