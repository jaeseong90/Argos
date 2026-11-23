/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Context propagation through executors, {@code CompletableFuture} and thread-per-task executors
 * (10 §6.6, §8, F-A07, F-A30, DSN-02-05). No task is ever wrapped or replaced (absolute rule): the
 * submitting thread's transaction is attached to the task object in one task store ({@code
 * ContextStore.of(Runnable.class, TraceContext.class)}, used for every task key: {@code Runnable},
 * {@code Callable} and {@code ForkJoinTask}; an injected {@code argos$ctx} field or the weak map,
 * DSN-02-05), and the method that runs the task on the worker removes it again and activates it.
 *
 * <p>Submit side, allow-listed executors only ({@link ExecutorInstrumentations}):
 *
 * <ul>
 *   <li>{@code ThreadPoolExecutor.execute} (JDK; {@code submit}, {@code invokeAll} and {@code
 *       CompletableFuture.*Async(.., pool)} pass through it), Tomcat's {@code
 *       org.apache.tomcat.util.threads.ThreadPoolExecutor.execute}, Spring's {@code
 *       SimpleAsyncTaskExecutor.execute} ({@code submit*} pass through it): {@link ExecuteAdvice};
 *   <li>{@code ScheduledThreadPoolExecutor} ({@code schedule*}, {@code execute}, {@code submit}):
 *       the {@code ScheduledFutureTask} it creates ({@link CreatedTaskArg1Advice});
 *   <li>{@code ForkJoinPool.execute/submit/invoke} ({@link ForkJoinSubmitAdvice}) and the {@code
 *       ForkJoinTask} adapters it creates ({@link CreatedTaskAdvice}), {@code ForkJoinTask.fork()}
 *       ({@link ForkAdvice}: parallel streams on the common pool);
 *   <li>{@code ThreadPerTaskExecutor.execute/submit} (JDK 21+, {@code
 *       Executors.newVirtualThreadPerTaskExecutor()}): the {@code TaskRunner}/{@code
 *       ThreadBoundFuture} it creates ({@link CreatedTaskArg1Advice});
 *   <li>{@code CompletableFuture}: {@code supplyAsync}/{@code runAsync} through the {@code
 *       AsyncSupply}/{@code AsyncRun} they create ({@link CreatedTaskArg1Advice}), the six {@code
 *       then*Async}/{@code whenCompleteAsync}/{@code handleAsync} methods through the {@code Uni*}
 *       stage they create ({@link AsyncStageAdvice}, {@link UniCreatedAdvice}). These work on any
 *       executor, allow-listed or not.
 * </ul>
 *
 * <p>Spring's {@code ThreadPoolTaskExecutor} delegates every {@code execute}/{@code submit*} to the
 * JDK {@code ThreadPoolExecutor} it creates, so it needs no submit advice of its own (one at the
 * Spring level would attach the context to the undecorated task when a {@code TaskDecorator} is
 * set).
 *
 * <p>Execution side: {@code FutureTask.run}, {@code ForkJoinTask.doExec} (every fork/join task, the
 * {@code exec()} of 10 §6.6), {@code AsyncSupply/AsyncRun.run}, {@code Uni*.tryFire(ASYNC)}, {@code
 * ThreadPerTaskExecutor$TaskRunner.run}, {@code run()}/{@code call()} of application {@code
 * Runnable}/{@code Callable} classes (with the context field of DSN-02-05) — {@link RunAdvice},
 * {@link TryFireAdvice} — and, for lambda tasks whose hidden classes cannot be instrumented, the
 * {@code beforeExecute}/{@code afterExecute} hooks of the pool ({@link BeforeExecuteAdvice}, {@link
 * AfterExecuteAdvice}) on the JDK and Tomcat {@code ThreadPoolExecutor} and on the pool subclasses
 * of Spring's {@code ThreadPoolTaskExecutor}/{@code ThreadPoolTaskScheduler}, which override the
 * hooks without {@code super} (Spring 6.1+).
 *
 * <p>Exclusions: agent threads ({@code Argos.skip}); task classes of {@code java.lang.} (virtual
 * thread continuations), {@code jdk.}, {@code sun.}, {@code io.netty.}, {@code reactor.}, {@code
 * io.argos.agent.} (the agent itself; sample apps under {@code io.argos.sample.} are applications)
 * and the container ({@code org.apache.tomcat.}, {@code org.apache.coyote.}, {@code
 * org.apache.catalina.}) on the submit side and in the application task matcher (module-local; the
 * agent's global ignore list is unchanged); ended transactions are never activated. Netty event
 * executors and Reactor schedulers are not allow-listed executors.
 *
 * <p>All advice runs in the class loader of the instrumented class (the bootstrap loader for the
 * JDK classes): bootstrap API only, no helper (10 §5.4).
 */
public final class ExecutorModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.EXECUTOR;

  /** {@code CompletableFuture.ASYNC}: the {@code tryFire} mode of a stage run by its executor. */
  static final int CF_ASYNC_MODE = 1;

  /** Value of the {@code CallDepth.EXECUTOR} slot while an asynchronous stage method runs. */
  static final long CF_STAGE_ARMED = 1L;

  @Override
  public String id() {
    return "executor";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.unmodifiableList(
        Arrays.<TypeInstrumentation>asList(
            new ExecutorInstrumentations.ThreadPools(),
            new ExecutorInstrumentations.HookOverrides(),
            new ExecutorInstrumentations.ScheduledTasks(),
            new ExecutorInstrumentations.ForkJoinPools(),
            new ExecutorInstrumentations.ForkJoinTasks(),
            new ExecutorInstrumentations.ForkJoinAdapters(),
            new ExecutorInstrumentations.FutureTasks(),
            new ExecutorInstrumentations.CompletableFutures(),
            new ExecutorInstrumentations.AsyncTasks(),
            new ExecutorInstrumentations.UniStages(),
            new ExecutorInstrumentations.ThreadPerTask(),
            new ExecutorInstrumentations.SpringAsyncExecutors(),
            new ExecutorInstrumentations.ApplicationTasks()));
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "java.util.concurrent (JDK 8-25): ThreadPoolExecutor, ScheduledThreadPoolExecutor,"
          + " ForkJoinPool/ForkJoinTask, FutureTask, CompletableFuture,"
          + " ThreadPerTaskExecutor (JDK 21+)",
      "org.springframework.core.task.SimpleAsyncTaskExecutor and the pools of"
          + " ThreadPoolTaskExecutor/ThreadPoolTaskScheduler (Spring Framework 5.3, 6.x, 7.x)",
      "org.apache.tomcat.util.threads.ThreadPoolExecutor (Tomcat 9, 10.1, 11)"
    };
  }
}
