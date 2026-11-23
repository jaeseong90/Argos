/* SPDX-License-Identifier: Apache-2.0 */
package com.example.app;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RecursiveTask;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Application-side task objects for the executor module tests. The module never attaches a context
 * to tasks of Argos' own packages ({@code io.argos.}), so every task, lambda and function the tests
 * submit is created here, in an application package: a lambda's class is named after the class that
 * defines it ({@code com.example.app.AppTasks$$Lambda...}).
 */
public final class AppTasks {
  private AppTasks() {}

  public static Runnable runnable(Runnable body) {
    return () -> body.run();
  }

  public static <T> Callable<T> callable(Callable<T> body) {
    return () -> body.call();
  }

  public static <T> Supplier<T> supplier(Supplier<T> body) {
    return () -> body.get();
  }

  public static <T, R> Function<T, R> function(Function<T, R> body) {
    return value -> body.apply(value);
  }

  public static <T> Consumer<T> consumer(Consumer<T> body) {
    return value -> body.accept(value);
  }

  public static <T, U> BiConsumer<T, U> biConsumer(BiConsumer<T, U> body) {
    return (value, error) -> body.accept(value, error);
  }

  public static <T, U, R> BiFunction<T, U, R> biFunction(BiFunction<T, U, R> body) {
    return (value, error) -> body.apply(value, error);
  }

  /** An application {@code Runnable} class (execution side: its own {@code run()}). */
  public static final class AppRunnable implements Runnable {
    private final Runnable body;

    public AppRunnable(Runnable body) {
      this.body = body;
    }

    @Override
    public void run() {
      body.run();
    }
  }

  /** An application {@code Callable} class (execution side: its own {@code call()}). */
  public static final class AppCallable<T> implements Callable<T> {
    private final Callable<T> body;

    public AppCallable(Callable<T> body) {
      this.body = body;
    }

    @Override
    public T call() throws Exception {
      return body.call();
    }
  }

  /**
   * Base of generated task classes with foreign names ({@code io.netty...}, {@code reactor...}):
   * the generated subclass only inherits {@link #run()}.
   */
  public abstract static class NamedTaskBase implements Runnable {
    private final Runnable body;

    protected NamedTaskBase(Runnable body) {
      this.body = body;
    }

    @Override
    public void run() {
      body.run();
    }
  }

  /**
   * Base of the task classes that the tests generate after the module is installed (so that the
   * agent transforms them while they are first defined): the generated class implements {@code
   * Runnable}/{@code Callable} and declares {@code run()}/{@code call()} calling these.
   */
  public abstract static class BodyHolder {
    private final Runnable body;
    private final Callable<?> callBody;

    protected BodyHolder(Runnable body, Callable<?> callBody) {
      this.body = body;
      this.callBody = callBody;
    }

    public void invokeBody() {
      body.run();
    }

    public Object invokeCallBody() throws Exception {
      return callBody.call();
    }
  }

  /** A fork/join task that splits a range and forks the halves. */
  public static final class SplitTask extends RecursiveTask<Integer> {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final transient Runnable leaf;

    public SplitTask(int from, int to, Runnable leaf) {
      this.from = from;
      this.to = to;
      this.leaf = leaf;
    }

    @Override
    protected Integer compute() {
      if (to - from <= 1) {
        leaf.run();
        return 1;
      }
      int middle = (from + to) >>> 1;
      SplitTask left = new SplitTask(from, middle, leaf);
      left.fork();
      int right = new SplitTask(middle, to, leaf).compute();
      return right + left.join();
    }
  }

  /** An executor outside the allow-list (10 §6.6): one new platform thread per task. */
  public static final class NewThreadExecutor implements Executor {
    @Override
    public void execute(Runnable command) {
      new Thread(command, "app-new-thread").start();
    }
  }
}
