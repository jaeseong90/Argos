/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static io.argos.agent.instrumentation.api.SafeMatchers.safeImplements;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The type instrumentations of {@link ExecutorModule} (10 §6.6). Every JDK type named here is on
 * the core's JDK allow-list ({@code IgnoreMatchers}); the version witness of each internal class
 * and member is {@code JdkInternalsWitnessTest} (U-12), run on JDK 8, 11, 17, 21 and 25.
 */
final class ExecutorInstrumentations {
  static final String THREAD_POOL_EXECUTOR = "java.util.concurrent.ThreadPoolExecutor";
  static final String TOMCAT_THREAD_POOL_EXECUTOR =
      "org.apache.tomcat.util.threads.ThreadPoolExecutor";
  static final String SCHEDULED_FUTURE_TASK =
      "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask";
  static final String FORK_JOIN_POOL = "java.util.concurrent.ForkJoinPool";
  static final String FORK_JOIN_TASK = "java.util.concurrent.ForkJoinTask";
  static final String FORK_JOIN_ADAPTER_PREFIX = "java.util.concurrent.ForkJoinTask$Adapted";
  static final String FORK_JOIN_EXECUTE_ACTION =
      "java.util.concurrent.ForkJoinTask$RunnableExecuteAction";
  static final String FUTURE_TASK = "java.util.concurrent.FutureTask";
  static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
  static final String ASYNC_SUPPLY = "java.util.concurrent.CompletableFuture$AsyncSupply";
  static final String ASYNC_RUN = "java.util.concurrent.CompletableFuture$AsyncRun";
  static final String UNI_PREFIX = "java.util.concurrent.CompletableFuture$Uni";
  static final String TASK_RUNNER = "java.util.concurrent.ThreadPerTaskExecutor$TaskRunner";
  static final String THREAD_BOUND_FUTURE =
      "java.util.concurrent.ThreadPerTaskExecutor$ThreadBoundFuture";
  static final String SPRING_SIMPLE_ASYNC = "org.springframework.core.task.SimpleAsyncTaskExecutor";
  static final String SPRING_POOL_EXECUTOR_PREFIX =
      "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$";
  static final String SPRING_POOL_SCHEDULER_PREFIX =
      "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler$";

  /** The asynchronous dependent-stage methods of 10 §6.6 (besides supplyAsync/runAsync). */
  static final String[] ASYNC_STAGE_METHODS = {
    "thenApplyAsync",
    "thenAcceptAsync",
    "thenRunAsync",
    "thenComposeAsync",
    "whenCompleteAsync",
    "handleAsync"
  };

  /**
   * Packages whose task classes never carry a context (10 §6.6: Netty, Reactor, agent; container
   * internals; JDK classes have their own rules). The Netty package is built at run time: Shadow
   * relocates string constants that start with a relocated package ({@code io.netty} is shaded in
   * the agent jar), which would turn the literal into the agent's own namespace (C-23).
   */
  static final String[] EXCLUDED_TASK_PACKAGES = {
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "jakarta.",
    String.join(".", "io", "netty", ""),
    "reactor.",
    "io.argos.agent.",
    "org.apache.tomcat.",
    "org.apache.coyote.",
    "org.apache.catalina."
  };

  private ExecutorInstrumentations() {}

  static ElementMatcher.Junction<MethodDescription> execute() {
    return named("execute").and(takesArgument(0, Runnable.class));
  }

  static ElementMatcher.Junction<MethodDescription> beforeExecute() {
    return named("beforeExecute")
        .and(takesArguments(2))
        .and(takesArgument(0, Thread.class))
        .and(takesArgument(1, Runnable.class));
  }

  static ElementMatcher.Junction<MethodDescription> afterExecute() {
    return named("afterExecute")
        .and(takesArguments(2))
        .and(takesArgument(0, Runnable.class))
        .and(takesArgument(1, Throwable.class));
  }

  static ElementMatcher.Junction<MethodDescription> noArgMethod(String name) {
    return named(name).and(takesArguments(0)).and(not(isAbstract()));
  }

  static ElementMatcher.Junction<TypeDescription> notExcludedPackage() {
    ElementMatcher.Junction<TypeDescription> excluded = nameStartsWith(EXCLUDED_TASK_PACKAGES[0]);
    for (int i = 1; i < EXCLUDED_TASK_PACKAGES.length; i++) {
      excluded = excluded.or(nameStartsWith(EXCLUDED_TASK_PACKAGES[i]));
    }
    return not(excluded);
  }

  /** Base with a readable name for the agent's debug output. */
  abstract static class Named implements TypeInstrumentation {
    private final String name;

    Named(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "executor(" + name + ")";
    }
  }

  /**
   * JDK and Tomcat {@code ThreadPoolExecutor}: submit ({@code execute}) and the worker hooks
   * (lambda tasks).
   */
  static final class ThreadPools extends Named {
    ThreadPools() {
      super("ThreadPoolExecutor");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(THREAD_POOL_EXECUTOR).or(named(TOMCAT_THREAD_POOL_EXECUTOR));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(execute(), ExecuteAdvice.class.getName());
      transformer.applyAdvice(beforeExecute(), BeforeExecuteAdvice.class.getName());
      transformer.applyAdvice(afterExecute(), AfterExecuteAdvice.class.getName());
    }
  }

  /**
   * The {@code ThreadPoolExecutor}/{@code ScheduledThreadPoolExecutor} subclasses that Spring's
   * {@code ThreadPoolTaskExecutor} and {@code ThreadPoolTaskScheduler} create: since Spring 6.1
   * they override {@code beforeExecute}/{@code afterExecute} without calling {@code super}, so the
   * JDK hooks never run for their workers.
   */
  static final class HookOverrides extends Named {
    HookOverrides() {
      super("Spring pool hooks");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return nameStartsWith(SPRING_POOL_EXECUTOR_PREFIX)
          .or(nameStartsWith(SPRING_POOL_SCHEDULER_PREFIX))
          .and(declaresMethod(beforeExecute().or(afterExecute())));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(beforeExecute(), BeforeExecuteAdvice.class.getName());
      transformer.applyAdvice(afterExecute(), AfterExecuteAdvice.class.getName());
    }
  }

  /**
   * {@code ScheduledThreadPoolExecutor}: the task object created by every submission. An inner
   * (non-static) class: constructor argument 0 is the pool, the user task is argument 1.
   */
  static final class ScheduledTasks extends Named {
    ScheduledTasks() {
      super("ScheduledFutureTask");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(SCHEDULED_FUTURE_TASK);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(isConstructor(), CreatedTaskArg1Advice.class.getName());
    }
  }

  /** {@code ForkJoinPool.execute/submit/invoke} of a {@code ForkJoinTask}. */
  static final class ForkJoinPools extends Named {
    ForkJoinPools() {
      super("ForkJoinPool");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(FORK_JOIN_POOL);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(
          namedOneOf("execute", "submit", "invoke")
              .and(isPublic())
              .and(not(isStatic()))
              .and(takesArguments(1).or(takesArguments(2))),
          ForkJoinSubmitAdvice.class.getName());
    }
  }

  /** {@code ForkJoinTask.fork()} (submit) and {@code doExec()} (execution, every JDK 8-25). */
  static final class ForkJoinTasks extends Named {
    ForkJoinTasks() {
      super("ForkJoinTask");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(FORK_JOIN_TASK);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(noArgMethod("fork"), ForkAdvice.class.getName());
      transformer.applyAdvice(noArgMethod("doExec"), RunAdvice.class.getName());
    }
  }

  /** The adapters that {@code ForkJoinPool} creates for a {@code Runnable} or {@code Callable}. */
  static final class ForkJoinAdapters extends Named {
    ForkJoinAdapters() {
      super("ForkJoinTask adapters");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return nameStartsWith(FORK_JOIN_ADAPTER_PREFIX)
          .or(named(FORK_JOIN_EXECUTE_ACTION))
          .and(not(isAbstract()));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(isConstructor(), CreatedTaskAdvice.class.getName());
    }
  }

  /**
   * {@code FutureTask.run()}: {@code submit} on any allow-listed executor, Spring {@code @Async}.
   */
  static final class FutureTasks extends Named {
    FutureTasks() {
      super("FutureTask");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(FUTURE_TASK);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(noArgMethod("run"), RunAdvice.class.getName());
    }
  }

  /** The asynchronous dependent-stage methods of {@code CompletableFuture}. */
  static final class CompletableFutures extends Named {
    CompletableFutures() {
      super("CompletableFuture");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(COMPLETABLE_FUTURE);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(
          namedOneOf(ASYNC_STAGE_METHODS)
              .and(not(isStatic()))
              .and(not(isAbstract()))
              .and(not(isBridge())),
          AsyncStageAdvice.class.getName());
    }
  }

  /** {@code CompletableFuture$AsyncSupply}/{@code $AsyncRun}: creation and {@code run()}. */
  static final class AsyncTasks extends Named {
    AsyncTasks() {
      super("CompletableFuture async tasks");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(ASYNC_SUPPLY).or(named(ASYNC_RUN));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(isConstructor(), CreatedTaskArg1Advice.class.getName());
      transformer.applyAdvice(noArgMethod("run"), RunAdvice.class.getName());
    }
  }

  /** The concrete {@code CompletableFuture$Uni*} stages: creation and {@code tryFire(int)}. */
  static final class UniStages extends Named {
    UniStages() {
      super("CompletableFuture stages");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return nameStartsWith(UNI_PREFIX).and(not(isAbstract()));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(isConstructor(), UniCreatedAdvice.class.getName());
      transformer.applyAdvice(
          named("tryFire").and(takesArguments(1)).and(takesArgument(0, int.class)),
          TryFireAdvice.class.getName());
    }
  }

  /**
   * {@code ThreadPerTaskExecutor} (JDK 21+): the task objects of {@code execute}/{@code submit}.
   */
  static final class ThreadPerTask extends Named {
    ThreadPerTask() {
      super("ThreadPerTaskExecutor");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(TASK_RUNNER).or(named(THREAD_BOUND_FUTURE));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(isConstructor(), CreatedTaskArg1Advice.class.getName());
      transformer.applyAdvice(noArgMethod("run"), RunAdvice.class.getName());
    }
  }

  /** Spring's {@code SimpleAsyncTaskExecutor} (a thread per task, platform or virtual). */
  static final class SpringAsyncExecutors extends Named {
    SpringAsyncExecutors() {
      super("SimpleAsyncTaskExecutor");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(SPRING_SIMPLE_ASYNC);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(execute(), ExecuteAdvice.class.getName());
    }
  }

  /**
   * Application {@code Runnable} and {@code Callable} classes (10 §6.6 execution side, {@code
   * not(isInterface())}): {@code run()}/{@code call()} take the context attached to the task, and
   * the class gets the context field of DSN-02-05 when it is being defined for the first time. One
   * rule for both interfaces, so a class that implements both is transformed (and given the field)
   * once. Cheapest tests first: package exclusions, then a declared {@code run()}/{@code call()},
   * then the interface walk.
   */
  static final class ApplicationTasks extends Named {
    ApplicationTasks() {
      super("application Runnable/Callable");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return notExcludedPackage()
          .and(not(isInterface()))
          .and(declaresMethod(noArgMethod("run").or(noArgMethod("call"))))
          .and(
              safeImplements(named("java.lang.Runnable"))
                  .or(safeImplements(named("java.util.concurrent.Callable"))));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(noArgMethod("run"), RunAdvice.class.getName());
      transformer.applyAdvice(noArgMethod("call"), RunAdvice.class.getName());
      transformer.injectContextField();
    }
  }
}
