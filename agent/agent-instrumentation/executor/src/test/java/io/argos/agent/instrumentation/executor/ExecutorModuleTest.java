/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.app.AppTasks;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ExecutorModule matchers (T-AG-051 matcher part, 10 §6.6): only the allow-listed executors, the
 * JDK task classes of the execution side and application task classes are matched; executors
 * outside the allow-list, Netty, Reactor and container classes are not. No helper (the advice runs
 * in the bootstrap loader for the JDK classes). Matching only, no class is loaded or transformed.
 */
@Tag("req:F-A07")
class ExecutorModuleTest {
  private final ExecutorModule module = new ExecutorModule();

  @Test
  @DisplayName("executor module identity: id, module id, no helper, version range")
  void identity() {
    assertThat(module.id()).isEqualTo("executor");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.EXECUTOR);
    assertThat(module.helperClassNames()).as("JDK classes: no helper").isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(3);
  }

  /** The rules of the module whose type matcher matches {@code type}. */
  private List<String> rulesMatching(TypeDescription type) {
    List<String> rules = new ArrayList<String>();
    for (TypeInstrumentation rule : module.types()) {
      if (rule.typeMatcher().matches(type)) {
        rules.add(rule.getClass().getSimpleName());
      }
    }
    return rules;
  }

  private List<String> rulesMatching(Class<?> type) {
    return rulesMatching(TypeDescription.ForLoadedType.of(type));
  }

  /**
   * An application test class described from its class file, without loading it (not even a class
   * literal): the propagation tests need these classes loaded after the module is installed.
   */
  private static TypeDescription app(String simpleName) {
    return TypePool.Default.of(ExecutorModuleTest.class.getClassLoader())
        .describe("com.example.app.AppTasks$" + simpleName)
        .resolve();
  }

  private static TypeDescription named(String name) throws ClassNotFoundException {
    return TypeDescription.ForLoadedType.of(Class.forName(name, false, null));
  }

  /** An unloaded class named {@code name} with the given super type, interfaces and run/call. */
  private static TypeDescription generated(
      String name, Class<?> superType, List<Class<?>> interfaces, String... noArgMethods) {
    net.bytebuddy.dynamic.DynamicType.Builder<?> builder =
        new ByteBuddy().subclass(superType).name(name);
    for (Class<?> type : interfaces) {
      builder = builder.implement(type);
    }
    for (String method : noArgMethods) {
      Class<?> returns = method.equals("call") ? Object.class : void.class;
      builder =
          builder.defineMethod(method, returns, Visibility.PUBLIC).intercept(StubMethod.INSTANCE);
    }
    return builder.make().getTypeDescription();
  }

  @Test
  @DisplayName(
      "T-AG-051a the allow-listed executors and JDK task classes are matched, each by its rule")
  void allowListedTypesMatched() throws Exception {
    assertThat(rulesMatching(ThreadPoolExecutor.class)).containsExactly("ThreadPools");
    assertThat(rulesMatching(named(ExecutorInstrumentations.SCHEDULED_FUTURE_TASK)))
        .containsExactly("ScheduledTasks");
    assertThat(rulesMatching(ForkJoinPool.class)).containsExactly("ForkJoinPools");
    assertThat(rulesMatching(ForkJoinTask.class)).containsExactly("ForkJoinTasks");
    assertThat(rulesMatching(named(ExecutorInstrumentations.FORK_JOIN_EXECUTE_ACTION)))
        .containsExactly("ForkJoinAdapters");
    assertThat(rulesMatching(named("java.util.concurrent.ForkJoinTask$AdaptedCallable")))
        .containsExactly("ForkJoinAdapters");
    assertThat(rulesMatching(FutureTask.class)).containsExactly("FutureTasks");
    assertThat(rulesMatching(CompletableFuture.class)).containsExactly("CompletableFutures");
    assertThat(rulesMatching(named(ExecutorInstrumentations.ASYNC_SUPPLY)))
        .containsExactly("AsyncTasks");
    assertThat(rulesMatching(named("java.util.concurrent.CompletableFuture$UniApply")))
        .containsExactly("UniStages");
    assertThat(rulesMatching(named("java.util.concurrent.CompletableFuture$UniCompletion")))
        .as("abstract stage base")
        .isEmpty();
    assertThat(
            rulesMatching(
                generated(
                    ExecutorInstrumentations.TOMCAT_THREAD_POOL_EXECUTOR,
                    AbstractExecutorService.class,
                    new ArrayList<Class<?>>(),
                    "shutdown")))
        .containsExactly("ThreadPools");
    assertThat(
            rulesMatching(
                generated(
                    ExecutorInstrumentations.SPRING_SIMPLE_ASYNC,
                    Object.class,
                    new ArrayList<Class<?>>())))
        .containsExactly("SpringAsyncExecutors");
  }

  @Test
  @DisplayName(
      "T-AG-051a Spring pool subclasses: only those that override beforeExecute/afterExecute")
  void springPoolHooks() {
    TypeDescription overriding =
        new ByteBuddy()
            .subclass(ThreadPoolExecutor.class)
            .name("org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1")
            .defineMethod("beforeExecute", void.class, Visibility.PROTECTED)
            .withParameters(Thread.class, Runnable.class)
            .intercept(StubMethod.INSTANCE)
            .defineMethod("afterExecute", void.class, Visibility.PROTECTED)
            .withParameters(Runnable.class, Throwable.class)
            .intercept(StubMethod.INSTANCE)
            .make()
            .getTypeDescription();
    assertThat(rulesMatching(overriding)).containsExactly("HookOverrides");
    TypeDescription plain =
        new ByteBuddy()
            .subclass(ThreadPoolExecutor.class)
            .name("org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1")
            .make()
            .getTypeDescription();
    assertThat(rulesMatching(plain)).as("Spring 5.3 shape: no override").isEmpty();
    TypeDescription executor =
        generated(
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor",
            Object.class,
            new ArrayList<Class<?>>());
    assertThat(rulesMatching(executor))
        .as("ThreadPoolTaskExecutor delegates to the JDK pool")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-051a executors outside the allow-list (application, Netty, Reactor, JDK wrappers) are"
          + " not matched")
  void foreignExecutorsNotMatched() throws Exception {
    assertThat(rulesMatching(app("NewThreadExecutor"))).isEmpty();
    assertThat(rulesMatching(named("java.util.concurrent.Executors$DelegatedExecutorService")))
        .isEmpty();
    assertThat(rulesMatching(ScheduledThreadPoolExecutor.class))
        .as("STPE: its task object is instrumented, not the pool")
        .isEmpty();
    for (String name :
        Arrays.asList(
            "com.example.app.CustomExecutorService",
            "io.netty.util.concurrent.DefaultEventExecutor",
            "io.netty.channel.nio.NioEventLoop",
            "reactor.core.scheduler.ParallelScheduler",
            "org.apache.tomcat.util.threads.VirtualThreadExecutor")) {
      TypeDescription type =
          generated(name, AbstractExecutorService.class, new ArrayList<Class<?>>(), "shutdown");
      assertThat(rulesMatching(type)).as(name).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051a application Runnable/Callable classes are matched; Netty, Reactor, container,"
          + " Argos and JDK task classes, interfaces and classes without run()/call() are not")
  void applicationTasks() throws Exception {
    assertThat(rulesMatching(app("AppRunnable"))).containsExactly("ApplicationTasks");
    assertThat(rulesMatching(app("AppCallable"))).containsExactly("ApplicationTasks");
    List<Class<?>> runnable = Arrays.<Class<?>>asList(Runnable.class);
    List<Class<?>> callable = Arrays.<Class<?>>asList(Callable.class);
    assertThat(
            rulesMatching(
                generated(
                    "com.example.app.Both",
                    Object.class,
                    Arrays.<Class<?>>asList(Runnable.class, Callable.class),
                    "run",
                    "call")))
        .containsExactly("ApplicationTasks");
    for (String name :
        Arrays.asList(
            "io.netty.util.concurrent.PromiseTask",
            "reactor.core.scheduler.WorkerTask",
            "org.apache.tomcat.util.net.SocketProcessorBase",
            "org.apache.coyote.AbstractProtocol$Task",
            "org.apache.catalina.core.AsyncContextImpl$RunnableWrapper",
            "io.argos.agent.core.Task",
            "jakarta.enterprise.Task",
            "javax.management.Task")) {
      assertThat(rulesMatching(generated(name, Object.class, runnable, "run"))).as(name).isEmpty();
      assertThat(rulesMatching(generated(name + "C", Object.class, callable, "call")))
          .as(name)
          .isEmpty();
    }
    assertThat(rulesMatching(named("java.util.concurrent.Executors$RunnableAdapter"))).isEmpty();
    assertThat(rulesMatching(app("NamedTaskBase")))
        .as("abstract class with run()")
        .containsExactly("ApplicationTasks");
    assertThat(
            rulesMatching(
                generated(
                    "com.example.app.NoRun",
                    AppTasks.NamedTaskBase.class,
                    new ArrayList<Class<?>>())))
        .as("inherits run(), declares none")
        .isEmpty();
    assertThat(
            rulesMatching(
                new ByteBuddy()
                    .makeInterface(Runnable.class)
                    .name("com.example.app.TaskInterface")
                    .make()
                    .getTypeDescription()))
        .as("interface")
        .isEmpty();
  }

  /** The advice and the context-field request of each rule. */
  @Test
  @DisplayName("T-AG-051a advice per rule; the context field only for application task classes")
  void advicePerRule() throws Exception {
    List<String> advice = new ArrayList<String>();
    for (TypeInstrumentation rule : module.types()) {
      final List<String> applied = new ArrayList<String>();
      final boolean[] field = new boolean[1];
      rule.transform(
          new TypeTransformer() {
            @Override
            public void applyAdvice(
                ElementMatcher<? super MethodDescription> methods, String adviceClassName) {
              applied.add(adviceClassName.substring(adviceClassName.lastIndexOf('.') + 1));
            }

            @Override
            public void injectContextField() {
              field[0] = true;
            }
          });
      advice.add(rule.getClass().getSimpleName() + applied + (field[0] ? "+field" : ""));
    }
    assertThat(advice)
        .containsExactly(
            "ThreadPools[ExecuteAdvice, BeforeExecuteAdvice, AfterExecuteAdvice]",
            "HookOverrides[BeforeExecuteAdvice, AfterExecuteAdvice]",
            "ScheduledTasks[CreatedTaskArg1Advice]",
            "ForkJoinPools[ForkJoinSubmitAdvice]",
            "ForkJoinTasks[ForkAdvice, RunAdvice]",
            "ForkJoinAdapters[CreatedTaskAdvice]",
            "FutureTasks[RunAdvice]",
            "CompletableFutures[AsyncStageAdvice]",
            "AsyncTasks[CreatedTaskArg1Advice, RunAdvice]",
            "UniStages[UniCreatedAdvice, TryFireAdvice]",
            "ThreadPerTask[CreatedTaskArg1Advice, RunAdvice]",
            "SpringAsyncExecutors[ExecuteAdvice]",
            "ApplicationTasks[RunAdvice, RunAdvice]+field");
  }

  @Test
  @DisplayName("T-AG-051a method matchers: execute and the two pool hooks, not remove")
  void methodMatchers() throws Exception {
    assertThat(
            ExecutorInstrumentations.execute()
                .matches(method(ThreadPoolExecutor.class, "execute", Runnable.class)))
        .isTrue();
    assertThat(
            ExecutorInstrumentations.execute()
                .matches(method(ThreadPoolExecutor.class, "remove", Runnable.class)))
        .isFalse();
    assertThat(
            ExecutorInstrumentations.beforeExecute()
                .matches(
                    method(
                        ThreadPoolExecutor.class, "beforeExecute", Thread.class, Runnable.class)))
        .isTrue();
    assertThat(
            ExecutorInstrumentations.afterExecute()
                .matches(
                    method(
                        ThreadPoolExecutor.class, "afterExecute", Runnable.class, Throwable.class)))
        .isTrue();
  }

  private static MethodDescription method(Class<?> owner, String name, Class<?>... parameters)
      throws NoSuchMethodException {
    return new MethodDescription.ForLoadedMethod(owner.getDeclaredMethod(name, parameters));
  }
}
