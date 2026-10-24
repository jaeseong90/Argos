/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.async;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

/**
 * The framework-neutral part of GET /api/async/executor (18 §4.2, F-A07):
 * CompletableFuture.supplyAsync, a parallel stream, a ScheduledThreadPoolExecutor task, and the
 * {@code cancel()} / {@code remove()} results that context propagation must not break (an agent
 * that wraps a Runnable makes {@code ThreadPoolExecutor.remove(runnable)} return false).
 */
public final class ExecutorProbe {

  private ExecutorProbe() {}

  /**
   * Runs the probes. {@code asyncExecutor} runs supplyAsync (the common pool when null). The web
   * layer adds the result of its {@code @Async} method.
   */
  public static Map<String, Object> run(Executor asyncExecutor)
      throws InterruptedException, ExecutionException, TimeoutException {
    Map<String, Object> result = new LinkedHashMap<>();
    CompletableFuture<String> supplied =
        asyncExecutor == null
            ? CompletableFuture.supplyAsync(ExecutorProbe::threadName)
            : CompletableFuture.supplyAsync(ExecutorProbe::threadName, asyncExecutor);
    result.put("supplyAsyncThread", supplied.get(10, TimeUnit.SECONDS));

    Set<String> streamThreads = ConcurrentHashMap.newKeySet();
    long sum =
        IntStream.range(0, 10_000)
            .parallel()
            .mapToLong(
                i -> {
                  streamThreads.add(Thread.currentThread().getName());
                  return i;
                })
            .sum();
    result.put("parallelStreamSum", sum);
    result.put("parallelStreamThreads", streamThreads.size());

    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("probe-scheduler-", true));
    scheduler.setRemoveOnCancelPolicy(true);
    try {
      ScheduledFuture<String> scheduled =
          scheduler.schedule(ExecutorProbe::threadName, 10, TimeUnit.MILLISECONDS);
      result.put("scheduledThread", scheduled.get(10, TimeUnit.SECONDS));
      ScheduledFuture<?> later = scheduler.schedule(() -> {}, 60, TimeUnit.SECONDS);
      result.put("cancelled", later.cancel(false));
      result.put("queueAfterCancel", scheduler.getQueue().size());
    } finally {
      scheduler.shutdownNow();
    }

    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(10),
            new NamedThreadFactory("probe-pool-", true));
    CountDownLatch busy = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      pool.execute(
          () -> {
            busy.countDown();
            awaitQuietly(release);
          });
      busy.await(10, TimeUnit.SECONDS);
      Runnable queued = () -> {};
      pool.execute(queued);
      result.put("removed", pool.remove(queued));
    } finally {
      release.countDown();
      pool.shutdown();
    }
    return result;
  }

  private static String threadName() {
    return Thread.currentThread().getName();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
