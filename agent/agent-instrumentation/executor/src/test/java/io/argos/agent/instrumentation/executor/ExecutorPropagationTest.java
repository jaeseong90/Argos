/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.app.AppTasks;
import io.argos.agent.bootstrap.ContextCarrier;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Transaction;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-AG-051 and T-AG-052 (unit parts, 10 §6.6): the module installed into the real JDK executor
 * classes of the test JVM (JDK 8, 11, 17, 21, 25), real threads, real tracer. Tasks are created in
 * an application package ({@link AppTasks}).
 */
@Tag("req:F-A07")
@Timeout(120)
class ExecutorPropagationTest extends ExecutorTestSupport {

  /** Submits {@code task}; returns the executor's result (a future or null). */
  private interface Submission {
    Future<?> submit(Runnable task) throws Exception;
  }

  private static Submission executing(java.util.concurrent.Executor executor) {
    return task -> {
      executor.execute(task);
      return null;
    };
  }

  private TraceContext seenThrough(Submission submission) throws Exception {
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final CountDownLatch done = new CountDownLatch(1);
    Future<?> future =
        submission.submit(
            AppTasks.runnable(
                () -> {
                  seen.set(tracer.current());
                  record("task");
                  done.countDown();
                }));
    assertThat(done.await(20, TimeUnit.SECONDS)).as("task ran").isTrue();
    if (future != null) {
      future.get(20, TimeUnit.SECONDS);
    }
    return seen.get();
  }

  /** Inside a new transaction: the task sees it, its step lands on it; outside: nothing. */
  private void assertPropagates(String what, Submission submission) throws Exception {
    TraceContext ctx = begin("GET /api/async/" + what);
    assertThat(seenThrough(submission)).as(what + ": the submitter's transaction").isSameAs(ctx);
    Thread.sleep(20); // the worker leaves the task's scope after it counted down
    Transaction tx = end(ctx);
    assertThat(stepNames(tx, StepType.METHOD))
        .as(what + ": step on the parent")
        .containsExactly("task");
    assertThat(seenThrough(submission)).as(what + ": outside a transaction").isNull();
  }

  @Test
  @DisplayName(
      "T-AG-051e ThreadPoolExecutor execute/submit (Runnable, Callable) and invokeAll propagate")
  void threadPoolExecutor() throws Exception {
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      assertPropagates("tpe-execute", executing(pool));
      assertPropagates("tpe-submit-runnable", task -> pool.submit(task));
      assertPropagates(
          "tpe-submit-callable", task -> pool.submit(AppTasks.callable(Executors.callable(task))));
      assertPropagates(
          "tpe-invokeAll",
          task -> {
            pool.invokeAll(Collections.singletonList(AppTasks.callable(Executors.callable(task))));
            return null;
          });
      assertPropagates("tpe-app-runnable", task -> executing(pool).submit(freshRunnable(task)));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051e ScheduledThreadPoolExecutor schedule (Runnable, Callable), execute and submit"
          + " propagate")
  void scheduledThreadPoolExecutor() throws Exception {
    final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      assertPropagates("stpe-schedule", task -> scheduler.schedule(task, 5, TimeUnit.MILLISECONDS));
      assertPropagates(
          "stpe-schedule-callable",
          task ->
              scheduler.schedule(
                  AppTasks.callable(Executors.callable(task)), 5, TimeUnit.MILLISECONDS));
      assertPropagates("stpe-execute", executing(scheduler));
      assertPropagates("stpe-submit", task -> scheduler.submit(task));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051f no wrapping: ThreadPoolExecutor.remove(task) and ScheduledFuture.cancel()/remove()"
          + " keep their results and queue sizes")
  void removeAndCancelPreserved() throws Exception {
    TraceContext ctx = begin("GET /api/async/executor");
    final CountDownLatch busy = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>());
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      pool.execute(
          AppTasks.runnable(
              () -> {
                busy.countDown();
                ExecutorAdviceTest.awaitQuietly(release);
              }));
      assertThat(busy.await(10, TimeUnit.SECONDS)).isTrue();
      final AtomicInteger ran = new AtomicInteger();
      Runnable queued = AppTasks.runnable(ran::incrementAndGet);
      pool.execute(queued);
      assertThat(pool.getQueue()).containsExactly(queued);
      assertThat(pool.remove(queued)).isTrue();
      assertThat(pool.getQueue()).isEmpty();

      scheduler.setRemoveOnCancelPolicy(true);
      ScheduledFuture<?> cancelled =
          scheduler.schedule(AppTasks.runnable(ran::incrementAndGet), 1, TimeUnit.HOURS);
      assertThat(scheduler.getQueue()).containsExactly((Runnable) cancelled);
      assertThat(cancelled.cancel(false)).isTrue();
      assertThat(cancelled.isCancelled()).isTrue();
      assertThat(scheduler.getQueue()).as("queue after cancel").isEmpty();

      scheduler.setRemoveOnCancelPolicy(false);
      ScheduledFuture<?> removed =
          scheduler.schedule(AppTasks.runnable(ran::incrementAndGet), 1, TimeUnit.HOURS);
      assertThat(scheduler.remove((Runnable) removed)).isTrue();
      assertThat(scheduler.getQueue()).isEmpty();
      release.countDown();
      assertThat(ran.get()).as("removed and cancelled tasks never ran").isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
      scheduler.shutdownNow();
      end(ctx);
    }
  }

  @Test
  @DisplayName(
      "T-AG-051g ForkJoinPool execute/submit (Runnable, Callable, ForkJoinTask), invoke and fork"
          + " propagate")
  void forkJoinPool() throws Exception {
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      assertPropagates("fjp-execute", executing(pool));
      assertPropagates("fjp-submit-runnable", task -> pool.submit(task));
      assertPropagates(
          "fjp-submit-callable", task -> pool.submit(AppTasks.callable(Executors.callable(task))));

      final TraceContext ctx = begin("GET /api/async/fjp-invoke");
      final AtomicInteger without = new AtomicInteger();
      final Set<Thread> threads =
          Collections.newSetFromMap(new ConcurrentHashMap<Thread, Boolean>());
      int count =
          pool.invoke(
              new AppTasks.SplitTask(
                  0,
                  64,
                  AppTasks.runnable(
                      () -> {
                        if (tracer.current() != ctx) {
                          without.incrementAndGet();
                        }
                        threads.add(Thread.currentThread());
                        sleepQuietly(2);
                      })));
      end(ctx);
      assertThat(count).isEqualTo(64);
      assertThat(without.get()).as("forked subtasks without the transaction").isZero();
      assertThat(threads).anyMatch(t -> t instanceof ForkJoinWorkerThread);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051h commonPool parallel stream: every element sees the transaction, workers included")
  void commonPoolParallelStream() {
    assumeTrue(
        ForkJoinPool.getCommonPoolParallelism() > 1, "common pool needs at least two workers");
    TraceContext ctx = begin("GET /api/async/parallel");
    final AtomicInteger without = new AtomicInteger();
    final AtomicInteger onWorkers = new AtomicInteger();
    final Thread caller = Thread.currentThread();
    IntStream.range(0, 4_000)
        .parallel()
        .forEach(
            i -> {
              if (tracer.current() != ctx) {
                without.incrementAndGet();
              }
              if (Thread.currentThread() != caller) {
                onWorkers.incrementAndGet();
              }
              if (i % 50 == 0) {
                sleepQuietly(1);
              }
            });
    end(ctx);
    assertThat(without.get()).as("elements without the transaction").isZero();
    assertThat(onWorkers.get()).as("elements processed by common pool workers").isPositive();
  }

  @Test
  @DisplayName(
      "T-AG-051i ThreadPerTaskExecutor (JDK 21+): virtual and platform thread-per-task executors"
          + " propagate execute and submit")
  void threadPerTaskExecutor() throws Exception {
    Method virtual;
    try {
      virtual = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
    } catch (NoSuchMethodException e) {
      assumeTrue(false, "ThreadPerTaskExecutor exists from JDK 21");
      return;
    }
    final ExecutorService vts = (ExecutorService) virtual.invoke(null);
    Method perTask = Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
    final ExecutorService platform =
        (ExecutorService) perTask.invoke(null, Executors.defaultThreadFactory());
    try {
      assertPropagates("vt-execute", executing(vts));
      assertPropagates("vt-submit-runnable", task -> vts.submit(task));
      assertPropagates(
          "vt-submit-callable", task -> vts.submit(AppTasks.callable(Executors.callable(task))));
      assertPropagates("tpt-execute", executing(platform));
      assertPropagates(
          "tpt-submit-callable",
          task -> platform.submit(AppTasks.callable(Executors.callable(task))));

      TraceContext ctx = begin("GET /api/async/vt");
      final AtomicReference<Boolean> isVirtual = new AtomicReference<Boolean>();
      Future<?> future =
          vts.submit(
              AppTasks.runnable(
                  () -> {
                    try {
                      isVirtual.set(
                          (Boolean)
                              Thread.class.getMethod("isVirtual").invoke(Thread.currentThread()));
                    } catch (ReflectiveOperationException e) {
                      throw new IllegalStateException(e);
                    }
                    record("on-virtual");
                  }));
      future.get(10, TimeUnit.SECONDS);
      Thread.sleep(20);
      Transaction tx = end(ctx);
      assertThat(isVirtual.get()).isTrue();
      assertThat(stepNames(tx, StepType.METHOD)).containsExactly("on-virtual");
    } finally {
      vts.shutdownNow();
      platform.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-AG-052c pool thread reuse: 1,000 alternating tasks with and without a transaction, no"
          + " contamination (ThreadPoolExecutor, ForkJoinPool, CompletableFuture)")
  void poolReuseWithoutContamination() throws Exception {
    final ExecutorService single = Executors.newSingleThreadExecutor();
    final ForkJoinPool fjp = new ForkJoinPool(1);
    try {
      int contaminated = 0;
      int missing = 0;
      for (int i = 0; i < 1_000; i++) {
        boolean inside = i % 2 == 0;
        TraceContext ctx = inside ? begin("GET /api/async/reuse") : null;
        TraceContext[] seen = new TraceContext[4];
        final AtomicReference<TraceContext> a = new AtomicReference<TraceContext>();
        final AtomicReference<TraceContext> b = new AtomicReference<TraceContext>();
        final AtomicReference<TraceContext> c = new AtomicReference<TraceContext>();
        final AtomicReference<TraceContext> d = new AtomicReference<TraceContext>();
        final CountDownLatch done = new CountDownLatch(1);
        single.execute(
            AppTasks.runnable(
                () -> {
                  a.set(tracer.current());
                  done.countDown();
                }));
        single
            .submit(
                AppTasks.callable(
                    () -> {
                      b.set(tracer.current());
                      return null;
                    }))
            .get(10, TimeUnit.SECONDS);
        fjp.submit(AppTasks.runnable(() -> c.set(tracer.current()))).get(10, TimeUnit.SECONDS);
        runAsyncAndWait(single, d);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        seen[0] = a.get();
        seen[1] = b.get();
        seen[2] = c.get();
        seen[3] = d.get();
        for (TraceContext s : seen) {
          if (inside && s != ctx) {
            missing++;
          }
          if (!inside && s != null) {
            contaminated++;
          }
        }
        if (inside) {
          end(ctx);
        }
      }
      assertThat(contaminated).as("tasks outside a transaction that saw one").isZero();
      assertThat(missing).as("tasks inside a transaction that missed it").isZero();
    } finally {
      single.shutdownNow();
      fjp.shutdownNow();
    }
  }

  private static void runAsyncAndWait(ExecutorService pool, AtomicReference<TraceContext> seen) {
    java.util.concurrent.CompletableFuture.runAsync(
            AppTasks.runnable(() -> seen.set(io.argos.agent.bootstrap.Argos.tracer().current())),
            pool)
        .join();
  }

  @Test
  @DisplayName(
      "T-AG-051j an executor outside the allow-list does not propagate a plain task, and the"
          + " execution side of an application Runnable/Callable takes an attached context once")
  void foreignExecutorAndApplicationTasks() throws Exception {
    final AppTasks.NewThreadExecutor foreign = new AppTasks.NewThreadExecutor();
    TraceContext ctx = begin("GET /api/async/foreign");
    assertThat(seenThrough(executing(foreign))).as("lambda on a foreign executor").isNull();
    assertThat(seenThrough(task -> executing(foreign).submit(freshRunnable(task))))
        .as("application Runnable on a foreign executor: no submit side")
        .isNull();

    // A context attached by a submit point (servlet-async AsyncContext.start, Spring
    // SimpleAsyncTaskExecutor.execute) is taken by the task's own run()/call(), once.
    final AtomicReference<TraceContext> seen = new AtomicReference<TraceContext>();
    final List<TraceContext> calls = Collections.synchronizedList(new ArrayList<TraceContext>());
    Runnable runnable = freshRunnable(AppTasks.runnable(() -> seen.set(tracer.current())));
    Callable<?> callable =
        freshCallable(
            AppTasks.callable(
                () -> {
                  calls.add(tracer.current());
                  return null;
                }));
    assertThat(runnable)
        .as("DSN-02-05: a task class defined after the install carries the context field")
        .isInstanceOf(ContextCarrier.class);
    assertThat(callable).isInstanceOf(ContextCarrier.class);
    ContextStore<Runnable, TraceContext> store =
        ContextStore.of(Runnable.class, TraceContext.class);
    store.put(runnable, ctx);
    @SuppressWarnings({"unchecked", "rawtypes"})
    ContextStore<Object, TraceContext> objects = (ContextStore) store;
    objects.put(callable, ctx);
    runOnNewThread(runnable);
    assertThat(seen.get()).isSameAs(ctx);
    runOnNewThread(runnable);
    assertThat(seen.get()).as("second run: the context was removed by the first").isNull();
    callOnNewThread(callable);
    callOnNewThread(callable);
    assertThat(calls).containsExactly(ctx, null);
    end(ctx);
  }

  @Test
  @DisplayName(
      "T-AG-051k Netty, Reactor, container and JDK-internal task classes never get a context")
  void excludedTaskClasses() throws Exception {
    final ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      for (String name :
          new String[] {
            "io.netty.argostest.FakeTask",
            "reactor.argostest.FakeTask",
            "org.apache.tomcat.argostest.FakeTask",
            "org.apache.catalina.argostest.FakeTask",
            "org.apache.coyote.argostest.FakeTask"
          }) {
        final Class<? extends AppTasks.NamedTaskBase> type =
            new ByteBuddy()
                .subclass(
                    AppTasks.NamedTaskBase.class,
                    ConstructorStrategy.Default.IMITATE_SUPER_CLASS_OPENING)
                .name(name)
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        TraceContext ctx = begin("GET /api/async/excluded");
        TraceContext seen =
            seenThrough(
                task -> {
                  pool.execute(type.getConstructor(Runnable.class).newInstance(task));
                  return null;
                });
        end(ctx);
        assertThat(seen).as(name).isNull();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-AG-052d 10,000 tasks: no context entry left in the task store after they ran (weak map"
          + " fallback for JDK task objects)")
  void noEntriesLeft() throws Exception {
    // The store is JVM-wide. Earlier tests leave entries by design (a queued task removed before
    // it ran, a cancelled scheduled task: the weak map keeps them until the key is collected), and
    // the puts of this test expunge those once the GC has cleared their keys, at a time no test
    // controls. So the entries are counted by key: the task objects of this test, which the advice
    // attaches to (ThreadPoolExecutor.execute receives the FutureTask of submit) and which the
    // futures list keeps reachable, so no GC can remove an entry of theirs.
    ContextStore<Runnable, TraceContext> store =
        ContextStore.of(Runnable.class, TraceContext.class);
    final int tasks = 10_000;
    final int workers = 4;
    final ExecutorService pool = Executors.newFixedThreadPool(workers);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      int before = store.size();
      TraceContext ctx = begin("GET /api/async/many");
      List<Future<?>> futures = new ArrayList<Future<?>>(tasks);
      for (int i = 0; i < tasks; i++) {
        futures.add(pool.submit(AppTasks.runnable(() -> ExecutorAdviceTest.awaitQuietly(release))));
      }
      assertThat(futures.get(0))
          .as("JDK task object: the weak map fallback, not the injected field")
          .isInstanceOf(Runnable.class)
          .isNotInstanceOf(ContextCarrier.class);
      // Held by the gate, at most one task per worker has started (and taken its context).
      assertThat(attachedTo(store, futures, ctx))
          .as("queued tasks carrying the transaction while the workers are held")
          .isBetween(tasks - workers, tasks);
      release.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      end(ctx);
      assertThat(attachedTo(store, futures, null))
          .as("entries of this test's tasks after every task ran")
          .isZero();
      assertThat(store.size())
          .as("entries after every task ran (earlier tests' stale entries can only leave)")
          .isLessThanOrEqualTo(before);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /** Tasks whose store entry is {@code ctx}, or any entry when {@code ctx} is null. */
  private static int attachedTo(
      ContextStore<Runnable, TraceContext> store, List<Future<?>> tasks, TraceContext ctx) {
    int count = 0;
    for (Future<?> task : tasks) {
      TraceContext attached = store.get((Runnable) task);
      if (attached != null && (ctx == null || attached == ctx)) {
        count++;
      }
    }
    return count;
  }

  private static void runOnNewThread(Runnable task) throws InterruptedException {
    Thread thread = new Thread(task, "app-direct");
    thread.start();
    thread.join(10_000);
  }

  private static void callOnNewThread(final Callable<?> task) throws InterruptedException {
    Thread thread =
        new Thread(
            () -> {
              try {
                task.call();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            "app-direct");
    thread.start();
    thread.join(10_000);
  }

  static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
