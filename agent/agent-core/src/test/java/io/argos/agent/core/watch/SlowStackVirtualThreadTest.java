/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TestVirtualThreads;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The slow-stack sampler on real virtual threads (10 §13.1, §8, F-A19, F-A30): runs in the JDK 21
 * and 25 test tasks ({@code testJdk21}, {@code testJdk25}; skipped on the Java 8 {@code test}).
 */
@Tag("req:F-A19")
@Tag("req:F-A30")
class SlowStackVirtualThreadTest {
  @Test
  @DisplayName(
      "T-AG-064d a transaction on a virtual thread is sampled with Thread.getStackTrace, outside"
          + " the getThreadInfo batch (which does not report virtual threads); <= 64 frames")
  void virtualThreadSample() throws Exception {
    assumeTrue(VirtualThreads.supported(), "virtual threads need JDK 21+ (testJdk21, testJdk25)");
    SlowStackWatcherTest.ManualClock clock = new SlowStackWatcherTest.ManualClock();
    ActiveRegistry registry = new ActiveRegistry(16);
    Dictionary dictionary = new Dictionary();
    AgentCounters counters = new AgentCounters();
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
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch ready = new CountDownLatch(1);
    final AtomicReference<TraceContext> context = new AtomicReference<TraceContext>();
    final SlowStackWatcherTest.ManualClock begin = clock;
    Thread vt =
        TestVirtualThreads.start(
            "slow-vt",
            new Runnable() {
              @Override
              public void run() {
                TraceContext ctx = tracer.begin(Kind.HTTP, "GET /vt", begin.nanoTime());
                context.set(ctx);
                ready.countDown();
                deep(100, release);
                tracer.end(ctx, 200, null, null, null, begin.nanoTime());
              }
            });
    SlowStackWatcherTest.CountingBean bean = new SlowStackWatcherTest.CountingBean();
    try {
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (vt.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertThat(context.get().virtualThread).isTrue();
      SlowStackWatcherTest.TestSettings settings =
          new SlowStackWatcherTest.TestSettings(new HashMap<String, String>());
      SlowStackWatcher watcher =
          new SlowStackWatcher(registry, tracer, clock, counters, settings, bean.proxy(), null);
      clock.advance(TimeUnit.SECONDS.toNanos(2));
      watcher.run();
      assertThat(bean.batches).as("no getThreadInfo for a virtual thread").isEmpty();
      assertThat(TracerImpl.stackCaptures(context.get())).isEqualTo(1);
    } finally {
      release.countDown();
      vt.join(10_000);
    }
    assertThat(ended).hasSize(1);
    StepBuffer steps = ended.get(0).steps();
    long[] frames = null;
    for (int seq = 0; seq < steps.size(); seq++) {
      if (steps.type(seq) == StepType.STACK) {
        frames = steps.stack(seq);
      }
    }
    assertThat(frames).isNotNull().hasSize(StepBuffer.MAX_STACK_FRAMES);
    List<String> texts = new ArrayList<String>();
    for (long id : frames) {
      texts.add(dictionary.text(id));
    }
    assertThat(texts).anyMatch(t -> t.contains("SlowStackVirtualThreadTest.deep("));
    assertThat(counters.stackCaptures()).isEqualTo(1);
    // AgentConfig defaults: depth 64.
    assertThat(AgentConfig.defaults().slowStackDepth()).isEqualTo(64);
  }

  static void deep(int frames, CountDownLatch release) {
    if (frames > 0) {
      deep(frames - 1, release);
      return;
    }
    try {
      release.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
