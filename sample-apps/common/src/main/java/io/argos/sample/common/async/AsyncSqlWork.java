/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.async;

import io.argos.sample.common.db.JdbcAccess;
import io.argos.sample.common.db.ShopDataException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SQL work of GET /api/async/executor (WO-15, F-A07, 10 §6.6 and §8): every asynchronous branch
 * runs exactly one statement of its own on its own thread, so an agent test can count the async SQL
 * steps of the request's transaction and tell the branches apart.
 *
 * <ul>
 *   <li>{@code work=sql} ({@link #run}): the web layer's {@code @Async} method ({@link
 *       #ASYNC_METHOD_SQL}, run by the caller and handed in as a future), {@code
 *       CompletableFuture.supplyAsync} ({@link #SUPPLY_ASYNC_SQL}), a {@link
 *       ScheduledThreadPoolExecutor} task ({@link #SCHEDULED_SQL}), the app's own one-thread {@link
 *       ThreadPoolExecutor} ({@link #POOL_SQL}, thread {@value #POOL_THREAD}) and, on JDK 21+,
 *       {@code Executors.newVirtualThreadPerTaskExecutor()} ({@link #VIRTUAL_SQL}; looked up with a
 *       {@link MethodHandle}, the module is compiled for Java 8). The answer has each branch's
 *       thread and {@code asyncSql}, the statements that ran off the request thread.
 *   <li>{@code work=detached&count=N} ({@link #detached}): {@code N} statements ({@link
 *       #DETACHED_SQL}) on the same pool thread, submitted by a dispatcher thread that this object
 *       started when it was created (outside any request): work that belongs to no transaction, for
 *       the context-leak check of T-AG-052 (a pool thread that ran a request's task must not carry
 *       that request into later tasks).
 * </ul>
 *
 * Plain {@link Statement}s without literals that vary; connections from {@link JdbcAccess}. The
 * pools and the dispatcher are daemon threads with bounded queues; {@link #close()} stops them.
 */
public final class AsyncSqlWork implements AutoCloseable {

  /** The {@code @Async} method's statement (run by the web layer, see {@link #query}). */
  public static final String ASYNC_METHOD_SQL = "SELECT COUNT(*) FROM products";

  /** The {@code CompletableFuture.supplyAsync} branch. */
  public static final String SUPPLY_ASYNC_SQL = "SELECT COUNT(*) FROM orders";

  /** The {@link ScheduledThreadPoolExecutor} branch. */
  public static final String SCHEDULED_SQL = "SELECT COUNT(*) FROM customers";

  /** The app pool branch ({@value #POOL_THREAD}). */
  public static final String POOL_SQL = "SELECT COUNT(*) FROM order_items";

  /** The virtual-thread-per-task branch (JDK 21+). */
  public static final String VIRTUAL_SQL = "SELECT COUNT(*) FROM audit_log";

  /** The statement of {@code work=detached} (no transaction). */
  public static final String DETACHED_SQL = "SELECT COUNT(*) FROM customers WHERE id < 0";

  /** The one thread of the app pool. */
  public static final String POOL_THREAD = "async-sql-pool-1";

  /** Thread name prefix of the scheduler branch. */
  public static final String SCHEDULER_THREAD_PREFIX = "async-sql-scheduler-";

  /** Upper bound of {@code count} in {@code work=detached}. */
  public static final int MAX_DETACHED = 100;

  private static final long WAIT_SECONDS = 10;

  /** {@code Executors.newVirtualThreadPerTaskExecutor()}, null before JDK 21. */
  private static final MethodHandle VIRTUAL_PER_TASK = virtualPerTaskHandle();

  private final JdbcAccess access;
  private final ThreadPoolExecutor pool;
  private final ScheduledThreadPoolExecutor scheduler;
  private final BlockingQueue<DetachedJob> detachedJobs = new ArrayBlockingQueue<>(16);
  private final Thread dispatcher;
  private volatile boolean closed;

  /**
   * Starts the pools and the dispatcher thread. Create it outside any request (a singleton bean),
   * so the dispatcher thread never ran inside a transaction.
   */
  public AsyncSqlWork(JdbcAccess access) {
    this.access = access;
    this.pool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(MAX_DETACHED * 2),
            new NamedThreadFactory("async-sql-pool-", true));
    this.scheduler =
        new ScheduledThreadPoolExecutor(1, new NamedThreadFactory(SCHEDULER_THREAD_PREFIX, true));
    this.scheduler.setRemoveOnCancelPolicy(true);
    this.dispatcher = new Thread(this::dispatch, "async-sql-dispatcher");
    this.dispatcher.setDaemon(true);
    this.dispatcher.start();
  }

  /** True when this JVM has {@code Executors.newVirtualThreadPerTaskExecutor()} (JDK 21+). */
  public static boolean virtualPerTaskSupported() {
    return VIRTUAL_PER_TASK != null;
  }

  /**
   * {@code work=sql}: runs the branches at the same time and waits for all of them (10 s each).
   *
   * @param supplyExecutor executor of {@code supplyAsync} (the common pool when null)
   * @param asyncMethod the future of the web layer's {@code @Async} method that ran {@link
   *     #ASYNC_METHOD_SQL} through {@link #query} and answers its thread name
   * @return {@code requestThread}, {@code asyncMethodThread}, {@code supplyAsyncSqlThread}, {@code
   *     scheduledSqlThread}, {@code poolSqlThread}, {@code virtualPerTaskThread} (JDK 21+), {@code
   *     asyncSql} and {@code sqlBranches}
   */
  public Map<String, Object> run(Executor supplyExecutor, Future<String> asyncMethod)
      throws InterruptedException, ExecutionException, TimeoutException {
    Thread request = Thread.currentThread();
    String requestThread = request.getName();
    // Branches compare thread identity (virtual threads of a per-task executor have no name).
    AtomicInteger offRequestThread = new AtomicInteger();
    CompletableFuture<String> supplied =
        supplyExecutor == null
            ? CompletableFuture.supplyAsync(
                () -> branch(SUPPLY_ASYNC_SQL, request, offRequestThread))
            : CompletableFuture.supplyAsync(
                () -> branch(SUPPLY_ASYNC_SQL, request, offRequestThread), supplyExecutor);
    Future<String> scheduled =
        scheduler.schedule(
            () -> branch(SCHEDULED_SQL, request, offRequestThread), 5, TimeUnit.MILLISECONDS);
    Future<String> pooled = pool.submit(() -> branch(POOL_SQL, request, offRequestThread));
    ExecutorService perTask = newVirtualPerTaskExecutor();
    Future<String> virtual =
        perTask == null
            ? null
            : perTask.submit(() -> branch(VIRTUAL_SQL, request, offRequestThread));
    try {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("work", "sql");
      result.put("requestThread", requestThread);
      int branches = 1;
      String asyncMethodThread = put(result, "asyncMethodThread", asyncMethod);
      put(result, "supplyAsyncSqlThread", supplied);
      put(result, "scheduledSqlThread", scheduled);
      put(result, "poolSqlThread", pooled);
      branches += 3;
      if (virtual != null) {
        put(result, "virtualPerTaskThread", virtual);
        branches++;
      }
      // The @Async method runs on the web layer's executor: only its thread name is known here.
      int off = offRequestThread.get() + (requestThread.equals(asyncMethodThread) ? 0 : 1);
      result.put("asyncSql", off);
      result.put("sqlBranches", branches);
      return result;
    } finally {
      if (perTask != null) {
        perTask.shutdown();
      }
    }
  }

  /**
   * {@code work=detached&count=N}: hands {@code count} statements to the dispatcher thread, which
   * submits them to the app pool; waits until all ran (10 s).
   *
   * @return {@code work}, {@code detachedSql} (statements that ran) and {@code detachedThreads}
   *     (their distinct thread names)
   * @throws IllegalArgumentException when {@code count} is not 1..{@value #MAX_DETACHED}
   */
  public Map<String, Object> detached(int count) throws InterruptedException {
    if (count < 1 || count > MAX_DETACHED) {
      throw new IllegalArgumentException("count must be between 1 and " + MAX_DETACHED);
    }
    DetachedJob job = new DetachedJob(count);
    if (!detachedJobs.offer(job, WAIT_SECONDS, TimeUnit.SECONDS)) {
      throw new IllegalStateException("the detached work queue is full");
    }
    if (!job.done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
      throw new IllegalStateException("detached work did not finish within 10 s");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("work", "detached");
    result.put("detachedSql", job.ran());
    result.put("detachedThreads", job.threads());
    return result;
  }

  /** {@link #query} for a branch of {@link #run}: counts it when it ran off the request thread. */
  private String branch(String sql, Thread request, AtomicInteger offRequestThread) {
    String name = query(sql);
    if (Thread.currentThread() != request) {
      offRequestThread.incrementAndGet();
    }
    return name;
  }

  /** Runs {@code sql} (a count) once on this thread and answers the thread's name. */
  public String query(String sql) {
    Connection connection;
    try {
      connection = access.acquire();
    } catch (SQLException e) {
      throw new ShopDataException("Cannot get a connection: " + e.getMessage(), e);
    }
    try {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(sql)) {
        rows.next();
      }
      return Thread.currentThread().getName();
    } catch (SQLException e) {
      throw new ShopDataException(e.getMessage(), e);
    } finally {
      try {
        access.release(connection);
      } catch (SQLException e) {
        // the statement ran; a failed release does not change the answer
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    dispatcher.interrupt();
    pool.shutdownNow();
    scheduler.shutdownNow();
  }

  private void dispatch() {
    while (!closed) {
      DetachedJob job;
      try {
        job = detachedJobs.take();
      } catch (InterruptedException e) {
        return;
      }
      for (int i = 0; i < job.count; i++) {
        try {
          pool.execute(job::runOne);
        } catch (RuntimeException e) {
          job.failed();
        }
      }
    }
  }

  private static String put(Map<String, Object> result, String key, Future<String> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    String thread = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    result.put(key, thread);
    return thread;
  }

  private static ExecutorService newVirtualPerTaskExecutor() {
    if (VIRTUAL_PER_TASK == null) {
      return null;
    }
    try {
      return (ExecutorService) VIRTUAL_PER_TASK.invoke();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("newVirtualThreadPerTaskExecutor failed", e);
    }
  }

  private static MethodHandle virtualPerTaskHandle() {
    try {
      MethodHandle handle =
          MethodHandles.publicLookup()
              .findStatic(
                  java.util.concurrent.Executors.class,
                  "newVirtualThreadPerTaskExecutor",
                  MethodType.methodType(ExecutorService.class));
      // JDK 19/20 have the method as a preview API that throws without --enable-preview.
      ExecutorService probe = (ExecutorService) handle.invoke();
      probe.shutdown();
      return handle;
    } catch (Throwable e) {
      return null;
    }
  }

  /** {@code count} detached statements and their outcome. */
  private final class DetachedJob {
    final int count;
    final CountDownLatch done;
    private final Set<String> threads = new LinkedHashSet<>();
    private int ran;

    DetachedJob(int count) {
      this.count = count;
      this.done = new CountDownLatch(count);
    }

    void runOne() {
      try {
        String thread = query(DETACHED_SQL);
        synchronized (this) {
          ran++;
          threads.add(thread);
        }
      } finally {
        done.countDown();
      }
    }

    void failed() {
      done.countDown();
    }

    synchronized int ran() {
      return ran;
    }

    synchronized List<String> threads() {
      return new ArrayList<>(threads);
    }
  }
}
