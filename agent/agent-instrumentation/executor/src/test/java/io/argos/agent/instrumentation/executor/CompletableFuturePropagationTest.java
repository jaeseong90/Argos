/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.app.AppTasks;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-AG-052 (unit part, 10 §6.6 "CompletableFuture"): the eight {@code *Async} methods run their
 * function with the transaction of the thread that called them, on the default executor (common
 * pool), on an allow-listed pool and on an executor outside the allow-list, with the source stage
 * already complete or not; a synchronous {@code thenApply} is left alone. The advice runs inside
 * the real {@code java.util.concurrent.CompletableFuture} of the test JVM (JDK 8, 11, 17, 21, 25).
 */
@Tag("req:F-A07")
@Timeout(60)
class CompletableFuturePropagationTest extends ExecutorTestSupport {
  private static final List<String> EIGHT =
      Arrays.asList(
          "supplyAsync",
          "runAsync",
          "thenApplyAsync",
          "thenAcceptAsync",
          "thenRunAsync",
          "thenComposeAsync",
          "whenCompleteAsync",
          "handleAsync");

  private ExecutorService pool;
  private final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());

  @BeforeEach
  void startPool() {
    pool = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void stopPool() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(10, TimeUnit.SECONDS);
  }

  /** Runs the eight methods; each function records a step named after its method. */
  private void runEight(Executor executor, boolean completedSource) {
    CompletableFuture<String> source =
        completedSource ? CompletableFuture.completedFuture("v") : new CompletableFuture<String>();
    List<CompletableFuture<?>> stages = new ArrayList<CompletableFuture<?>>();
    stages.add(
        executor == null
            ? CompletableFuture.supplyAsync(AppTasks.supplier(() -> work("supplyAsync")))
            : CompletableFuture.supplyAsync(
                AppTasks.supplier(() -> work("supplyAsync")), executor));
    stages.add(
        executor == null
            ? CompletableFuture.runAsync(AppTasks.runnable(() -> work("runAsync")))
            : CompletableFuture.runAsync(AppTasks.runnable(() -> work("runAsync")), executor));
    if (executor == null) {
      stages.add(source.thenApplyAsync(AppTasks.function(v -> work("thenApplyAsync"))));
      stages.add(source.thenAcceptAsync(AppTasks.consumer(v -> work("thenAcceptAsync"))));
      stages.add(source.thenRunAsync(AppTasks.runnable(() -> work("thenRunAsync"))));
      stages.add(
          source.thenComposeAsync(
              AppTasks.function(v -> CompletableFuture.completedFuture(work("thenComposeAsync")))));
      stages.add(
          source.whenCompleteAsync(AppTasks.biConsumer((v, e) -> work("whenCompleteAsync"))));
      stages.add(source.handleAsync(AppTasks.biFunction((v, e) -> work("handleAsync"))));
    } else {
      stages.add(source.thenApplyAsync(AppTasks.function(v -> work("thenApplyAsync")), executor));
      stages.add(source.thenAcceptAsync(AppTasks.consumer(v -> work("thenAcceptAsync")), executor));
      stages.add(source.thenRunAsync(AppTasks.runnable(() -> work("thenRunAsync")), executor));
      stages.add(
          source.thenComposeAsync(
              AppTasks.function(v -> CompletableFuture.completedFuture(work("thenComposeAsync"))),
              executor));
      stages.add(
          source.whenCompleteAsync(
              AppTasks.biConsumer((v, e) -> work("whenCompleteAsync")), executor));
      stages.add(source.handleAsync(AppTasks.biFunction((v, e) -> work("handleAsync")), executor));
    }
    if (!completedSource) {
      // Completed by a thread outside any transaction: the stages still carry their own.
      Thread completer = new Thread(() -> source.complete("v"), "unrelated-completer");
      completer.start();
    }
    CompletableFuture.allOf(stages.toArray(new CompletableFuture<?>[0])).join();
  }

  private String work(String name) {
    threads.add(Thread.currentThread());
    record(name);
    return name;
  }

  private void assertEightJoined(Executor executor, boolean completedSource) {
    TraceContext ctx = begin("GET /api/async/cf");
    runEight(executor, completedSource);
    Transaction tx = end(ctx);
    List<String> names = stepNames(tx, StepType.METHOD);
    Collections.sort(names);
    List<String> expected = new ArrayList<String>(EIGHT);
    Collections.sort(expected);
    assertThat(names)
        .as("one step per *Async method, on the parent transaction")
        .isEqualTo(expected);
    assertThat(threads).as("the work ran on other threads").doesNotContain(Thread.currentThread());
  }

  @Test
  @DisplayName(
      "T-AG-052a eight *Async methods on the default executor join the caller's transaction")
  void defaultExecutor() {
    assertEightJoined(null, false);
    threads.clear();
    assertEightJoined(null, true);
  }

  @Test
  @DisplayName("T-AG-052a eight *Async methods on an allow-listed ThreadPoolExecutor join it")
  void allowListedPool() {
    assertEightJoined(pool, false);
    threads.clear();
    assertEightJoined(pool, true);
  }

  @Test
  @DisplayName(
      "T-AG-052a eight *Async methods on an executor outside the allow-list join it (stage-level"
          + " propagation)")
  void foreignExecutor() {
    Executor foreign = new AppTasks.NewThreadExecutor();
    assertEightJoined(foreign, false);
    threads.clear();
    assertEightJoined(foreign, true);
  }

  @Test
  @DisplayName(
      "T-AG-052a async.step=true: each *Async function's step sits under an ASYNC step of its stage")
  void asyncStepParents() throws InterruptedException {
    asyncSteps(true);
    TraceContext ctx = begin("GET /api/async/cf");
    runEight(pool, false);
    // join() returns once each stage is complete, which happens inside the run/tryFire body,
    // before the exit advice closes the ASYNC step on the worker; wait until the workers have
    // returned from every task so the transaction ends after the exits it asserts on.
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("pool drained").isTrue();
    Transaction tx = end(ctx);
    StepBuffer steps = tx.steps();
    int methods = 0;
    for (int seq = 0; seq < steps.size(); seq++) {
      if (steps.type(seq) != StepType.METHOD) {
        continue;
      }
      methods++;
      int parent = steps.parent(seq);
      assertThat(parent)
          .as("parent of %s", dictionary.text(steps.nameId(seq)))
          .isGreaterThanOrEqualTo(0);
      assertThat(steps.type(parent)).isEqualTo(StepType.ASYNC);
      assertThat(steps.isClosed(parent)).isTrue();
    }
    assertThat(methods).isEqualTo(8);
  }

  @Test
  @DisplayName(
      "T-AG-052b a synchronous thenApply runs on the completing thread without the registering"
          + " transaction and leaves no store entry")
  void synchronousStageUntouched() throws Exception {
    ContextStore<Runnable, TraceContext> store =
        ContextStore.of(Runnable.class, TraceContext.class);
    CompletableFuture<String> source = new CompletableFuture<String>();
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    TraceContext ctx = begin("GET /api/async/sync");
    int before = store.size();
    CompletableFuture<String> dependent =
        source.thenApply(
            AppTasks.function(
                v -> {
                  seen.set(tracer.current());
                  done.countDown();
                  return v;
                }));
    assertThat(store.size()).as("no entry for a synchronous stage").isEqualTo(before);
    Transaction tx = end(ctx);
    Thread completer = new Thread(() -> source.complete("v"), "unrelated-completer");
    completer.start();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(dependent.join()).isEqualTo("v");
    assertThat(seen.get())
        .as("synchronous stage: the completing thread's (no) transaction")
        .isNull();
    assertThat(tx.steps().size()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-052b outside a transaction nothing is attached; an ended transaction is not activated")
  void outsideAndEnded() throws Exception {
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>(begin("x"));
    tracer.end(tracer.current(), 200, null, null, null, System.nanoTime());
    CompletableFuture.runAsync(AppTasks.runnable(() -> seen.set(tracer.current())), pool).join();
    assertThat(seen.get()).isNull();

    final AtomicReference<TraceContext> late = new AtomicReference<TraceContext>();
    CompletableFuture<String> gate = new CompletableFuture<String>();
    TraceContext ctx = begin("GET /api/async/ended");
    CompletableFuture<Void> stage =
        gate.thenRunAsync(AppTasks.runnable(() -> late.set(tracer.current())), pool);
    end(ctx);
    gate.complete("v");
    stage.get(10, TimeUnit.SECONDS);
    assertThat(late.get()).as("the transaction ended before the stage ran").isNull();
  }
}
