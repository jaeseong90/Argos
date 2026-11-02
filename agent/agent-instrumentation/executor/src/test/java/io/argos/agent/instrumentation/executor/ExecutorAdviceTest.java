/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.SuperMethodCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The executor advice inlined by ByteBuddy into a {@link ThreadPoolExecutor} subclass whose {@code
 * execute}, {@code beforeExecute} and {@code afterExecute} call {@code super} (the same advice
 * classes and method matchers as on the JDK class), run on real pool threads against the real
 * tracer.
 */
@Tag("req:F-A07")
@Timeout(30)
class ExecutorAdviceTest {
  private static final Class<? extends ThreadPoolExecutor> POOL =
      new ByteBuddy()
          .subclass(ThreadPoolExecutor.class)
          .method(ExecutorModule.execute())
          .intercept(SuperMethodCall.INSTANCE)
          .method(ExecutorModule.beforeExecute())
          .intercept(SuperMethodCall.INSTANCE)
          .method(ExecutorModule.afterExecute())
          .intercept(SuperMethodCall.INSTANCE)
          .visit(
              TestAdvice.of(ExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorModule.execute()))
          .visit(
              TestAdvice.of(BeforeExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorModule.beforeExecute()))
          .visit(
              TestAdvice.of(AfterExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorModule.afterExecute()))
          .make()
          .load(ExecutorAdviceTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
          .getLoaded();

  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private final Dictionary dictionary = new Dictionary();
  private TracerImpl tracer;
  private ThreadPoolExecutor pool;
  private int adviceErrorsBefore;

  @BeforeEach
  void setUp() throws Exception {
    tracer =
        new TracerImpl(
            dictionary,
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                ended.add(tx);
              }
            },
            control,
            new TxIdGenerator(),
            1000,
            100);
    control.installTracer(tracer);
    adviceErrorsBefore = ModuleHealth.errors(ExecutorModule.MODULE_ID);
    pool =
        POOL.getConstructor(int.class, int.class, long.class, TimeUnit.class, BlockingQueue.class)
            .newInstance(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16));
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(10, TimeUnit.SECONDS);
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(ModuleHealth.errors(ExecutorModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(adviceErrorsBefore);
    control.setModuleState(ExecutorModule.MODULE_ID, ModuleHealth.ENABLED);
  }

  /** Runs a task on the pool and returns the transaction it saw. */
  private TraceContext seenOnWorker() throws Exception {
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            seen.set(tracer.current());
            done.countDown();
          }
        });
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    return seen.get();
  }

  @Test
  @DisplayName(
      "first and queued tasks run with the submitter's transaction; the worker is restored")
  void propagatesToFirstAndQueuedTasks() throws Exception {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/async/executor", System.nanoTime());
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> first = new AtomicReference<TraceContext>();
    final AtomicReference<TraceContext> queued = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(2);
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            first.set(tracer.current());
            awaitQuietly(release);
            done.countDown();
          }
        });
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            queued.set(tracer.current());
            done.countDown();
          }
        });
    release.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(first.get()).isSameAs(ctx);
    assertThat(queued.get()).isSameAs(ctx);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());

    // A task submitted outside any transaction sees none on the same worker thread.
    assertThat(seenOnWorker()).isNull();
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).steps().size()).as("async.step is off by default").isZero();
  }

  @Test
  @DisplayName("async.step=true: one ASYNC step per task, lambda names cut after $$Lambda")
  void asyncStepPerTask() throws Exception {
    tracer.setCapture(CaptureConfig.DEFAULT.withAsyncStep(true));
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    Runnable lambda =
        () -> {
          // no-op task
        };
    Future<?> future = pool.submit(lambda);
    future.get(10, TimeUnit.SECONDS);
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(done::countDown);
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    String supplied =
        CompletableFuture.supplyAsync(() -> String.valueOf(tracer.current() != null), pool)
            .get(10, TimeUnit.SECONDS);
    assertThat(supplied).isEqualTo("true");
    Thread.sleep(50); // afterExecute of the last task closes its step
    tracer.end(ctx, 200, null, null, null, System.nanoTime());

    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.size()).isEqualTo(3);
    List<String> names = new ArrayList<String>();
    for (int seq = 0; seq < steps.size(); seq++) {
      assertThat(steps.type(seq)).isEqualTo(StepType.ASYNC);
      assertThat(steps.elapsedNanos(seq)).as("closed by afterExecute").isGreaterThanOrEqualTo(0L);
      names.add(dictionary.text(steps.nameId(seq)));
    }
    assertThat(names.get(0)).isEqualTo("java.util.concurrent.FutureTask");
    assertThat(names.get(1)).endsWith("ExecutorAdviceTest$$Lambda");
    assertThat(names.get(2)).startsWith("java.util.concurrent.CompletableFuture$AsyncSupply");
  }

  @Test
  @DisplayName("no wrapping: remove(task) still finds the queued task")
  void removeStillWorks() throws Exception {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    final CountDownLatch busy = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            busy.countDown();
            awaitQuietly(release);
          }
        });
    assertThat(busy.await(10, TimeUnit.SECONDS)).isTrue();
    Runnable queued =
        new Runnable() {
          @Override
          public void run() {}
        };
    pool.execute(queued);
    assertThat(pool.getQueue()).containsExactly(queued);
    assertThat(pool.remove(queued)).isTrue();
    release.countDown();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @DisplayName("a failing task: same exception path, ASYNC step marked error")
  void failingTask() throws Exception {
    tracer.setCapture(CaptureConfig.DEFAULT.withAsyncStep(true));
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    final CountDownLatch thrown = new CountDownLatch(1);
    pool.setThreadFactory(
        r -> {
          Thread thread = new Thread(r);
          thread.setUncaughtExceptionHandler((t, e) -> thrown.countDown());
          return thread;
        });
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            throw new IllegalStateException("task failed");
          }
        });
    assertThat(thrown.await(10, TimeUnit.SECONDS)).isTrue();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.error(0)).isTrue();
  }

  @Test
  @DisplayName("ended transactions are not activated; disabled module does nothing")
  void endedOrDisabled() throws Exception {
    final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            awaitQuietly(release);
          }
        });
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            seen.set(tracer.current());
            done.countDown();
          }
        });
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    release.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(seen.get()).as("transaction ended before the task ran").isNull();

    control.setModuleState(ExecutorModule.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    TraceContext second = tracer.begin(Kind.HTTP, "GET /y", System.nanoTime());
    assertThat(seenOnWorker()).isNull();
    tracer.end(second, 200, null, null, null, System.nanoTime());
  }

  static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
