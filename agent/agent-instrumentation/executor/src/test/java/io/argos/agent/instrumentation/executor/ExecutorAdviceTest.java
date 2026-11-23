/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.app.AppTasks;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
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
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.implementation.SuperMethodCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pool hooks ({@link BeforeExecuteAdvice}, {@link AfterExecuteAdvice}) and {@link
 * ExecuteAdvice} inlined by ByteBuddy into {@link ThreadPoolExecutor} subclasses of the two shapes
 * the module meets (the same advice classes and method matchers as on the real classes; the JDK
 * {@code ThreadPoolExecutor} itself is instrumented too, by the installed module):
 *
 * <ul>
 *   <li>{@code super}: the overrides call {@code super} (a user subclass), so the hooks run twice
 *       per task, nested;
 *   <li>{@code no-super}: {@code beforeExecute}/{@code afterExecute} overridden without {@code
 *       super}, the shape of the pools Spring's {@code ThreadPoolTaskExecutor} creates (6.1+) and
 *       of Tomcat's own {@code ThreadPoolExecutor}: only the subclass hooks run.
 * </ul>
 *
 * Real pool threads, the real tracer.
 */
@Tag("req:F-A07")
@Timeout(30)
class ExecutorAdviceTest extends ExecutorTestSupport {
  private static final Class<? extends ThreadPoolExecutor> SUPER_POOL =
      new ByteBuddy()
          .subclass(ThreadPoolExecutor.class)
          .name("com.example.app.SuperCallingPool")
          .method(ExecutorInstrumentations.execute())
          .intercept(SuperMethodCall.INSTANCE)
          .method(ExecutorInstrumentations.beforeExecute())
          .intercept(SuperMethodCall.INSTANCE)
          .method(ExecutorInstrumentations.afterExecute())
          .intercept(SuperMethodCall.INSTANCE)
          .visit(
              TestAdvice.of(ExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorInstrumentations.execute()))
          .visit(
              TestAdvice.of(BeforeExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorInstrumentations.beforeExecute()))
          .visit(
              TestAdvice.of(AfterExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorInstrumentations.afterExecute()))
          .make()
          .load(ExecutorAdviceTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
          .getLoaded();

  private static final Class<? extends ThreadPoolExecutor> NO_SUPER_POOL =
      new ByteBuddy()
          .subclass(ThreadPoolExecutor.class)
          .name("com.example.app.HookOverridingPool")
          .method(ExecutorInstrumentations.beforeExecute())
          .intercept(StubMethod.INSTANCE)
          .method(ExecutorInstrumentations.afterExecute())
          .intercept(StubMethod.INSTANCE)
          .visit(
              TestAdvice.of(BeforeExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorInstrumentations.beforeExecute()))
          .visit(
              TestAdvice.of(AfterExecuteAdvice.class, ExecutorModule.MODULE_ID)
                  .on(ExecutorInstrumentations.afterExecute()))
          .make()
          .load(ExecutorAdviceTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
          .getLoaded();

  private ThreadPoolExecutor pool;

  private void newPool(String shape) throws Exception {
    Class<? extends ThreadPoolExecutor> type = "super".equals(shape) ? SUPER_POOL : NO_SUPER_POOL;
    pool =
        type.getConstructor(int.class, int.class, long.class, TimeUnit.class, BlockingQueue.class)
            .newInstance(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16));
  }

  @AfterEach
  void stopPool() throws InterruptedException {
    if (pool != null) {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /** Runs a task on the pool and returns the transaction it saw. */
  private TraceContext seenOnWorker() throws Exception {
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(
        AppTasks.runnable(
            () -> {
              seen.set(tracer.current());
              done.countDown();
            }));
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    return seen.get();
  }

  @ParameterizedTest(
      name =
          "T-AG-052e {0}: first and queued lambda tasks run with the submitter's"
              + " transaction; the worker is restored")
  @ValueSource(strings = {"super", "no-super"})
  void propagatesToFirstAndQueuedTasks(String shape) throws Exception {
    newPool(shape);
    TraceContext ctx = begin("GET /api/async/executor");
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> first = new AtomicReference<TraceContext>();
    final AtomicReference<TraceContext> queued = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(2);
    pool.execute(
        AppTasks.runnable(
            () -> {
              first.set(tracer.current());
              awaitQuietly(release);
              done.countDown();
            }));
    pool.execute(
        AppTasks.runnable(
            () -> {
              queued.set(tracer.current());
              done.countDown();
            }));
    release.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(first.get()).isSameAs(ctx);
    assertThat(queued.get()).isSameAs(ctx);
    Thread.sleep(20);
    Transaction tx = end(ctx);

    // A task submitted outside any transaction sees none on the same worker thread.
    assertThat(seenOnWorker()).isNull();
    assertThat(tx.steps().size()).as("async.step is off by default").isZero();
  }

  @ParameterizedTest(
      name =
          "T-AG-052e {0}: async.step=true, one ASYNC step per task, lambda names"
              + " cut after $$Lambda")
  @ValueSource(strings = {"super", "no-super"})
  void asyncStepPerTask(String shape) throws Exception {
    newPool(shape);
    asyncSteps(true);
    TraceContext ctx = begin("GET /x");
    Future<?> future = pool.submit(AppTasks.runnable(() -> {}));
    future.get(10, TimeUnit.SECONDS);
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(AppTasks.runnable(done::countDown));
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    String supplied =
        CompletableFuture.supplyAsync(
                AppTasks.supplier(() -> String.valueOf(tracer.current() != null)), pool)
            .get(10, TimeUnit.SECONDS);
    assertThat(supplied).isEqualTo("true");
    // afterExecute of the last task closes its step after the future completed: wait until the
    // worker has returned from every task before the transaction ends.
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("pool drained").isTrue();
    Transaction tx = end(ctx);

    StepBuffer steps = tx.steps();
    assertThat(steps.size()).as("one ASYNC step per task, nested hooks included").isEqualTo(3);
    List<String> names = new ArrayList<String>();
    for (int seq = 0; seq < steps.size(); seq++) {
      assertThat(steps.type(seq)).isEqualTo(StepType.ASYNC);
      assertThat(steps.isClosed(seq)).as("closed by afterExecute").isTrue();
      names.add(dictionary.text(steps.nameId(seq)));
    }
    assertThat(names.get(0)).isEqualTo("java.util.concurrent.FutureTask");
    assertThat(names.get(1)).isEqualTo("com.example.app.AppTasks$$Lambda");
    assertThat(names.get(2)).startsWith("java.util.concurrent.CompletableFuture$AsyncSupply");
  }

  @ParameterizedTest(name = "T-AG-051f {0}: no wrapping, remove(task) still finds the queued task")
  @ValueSource(strings = {"super", "no-super"})
  void removeStillWorks(String shape) throws Exception {
    newPool(shape);
    TraceContext ctx = begin("GET /x");
    final CountDownLatch busy = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    pool.execute(
        AppTasks.runnable(
            () -> {
              busy.countDown();
              awaitQuietly(release);
            }));
    assertThat(busy.await(10, TimeUnit.SECONDS)).isTrue();
    Runnable queued = AppTasks.runnable(() -> {});
    pool.execute(queued);
    assertThat(pool.getQueue()).containsExactly(queued);
    assertThat(pool.remove(queued)).isTrue();
    release.countDown();
    end(ctx);
  }

  @ParameterizedTest(
      name = "T-AG-052e {0}: a failing task keeps its exception path, ASYNC step" + " marked error")
  @ValueSource(strings = {"super", "no-super"})
  void failingTask(String shape) throws Exception {
    newPool(shape);
    asyncSteps(true);
    TraceContext ctx = begin("GET /x");
    final CountDownLatch thrown = new CountDownLatch(1);
    pool.setThreadFactory(
        r -> {
          Thread thread = new Thread(r);
          thread.setUncaughtExceptionHandler((t, e) -> thrown.countDown());
          return thread;
        });
    pool.execute(
        AppTasks.runnable(
            () -> {
              throw new IllegalStateException("task failed");
            }));
    assertThat(thrown.await(10, TimeUnit.SECONDS)).isTrue();
    Transaction tx = end(ctx);
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.error(0)).isTrue();
  }

  @ParameterizedTest(
      name =
          "T-AG-052e {0}: ended transactions are not activated; a disabled"
              + " module does nothing")
  @ValueSource(strings = {"super", "no-super"})
  void endedOrDisabled(String shape) throws Exception {
    newPool(shape);
    final TraceContext ctx = begin("GET /x");
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    pool.execute(AppTasks.runnable(() -> awaitQuietly(release)));
    pool.execute(
        AppTasks.runnable(
            () -> {
              seen.set(tracer.current());
              done.countDown();
            }));
    end(ctx);
    release.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(seen.get()).as("transaction ended before the task ran").isNull();

    control().setModuleState(ExecutorModule.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    TraceContext second = begin("GET /y");
    assertThat(seenOnWorker()).isNull();
    end(second);
  }

  static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
