/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.boot.ExecutorTestInstaller;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U-12 (10 §6.6, 18 확인 작업): the JDK internals that the executor advice relies on exist with the
 * expected shape on the JDK this task runs on ({@code test} = 8, {@code testJdk11/17/21/25}), and
 * the core's installer transformed every one of them without an error.
 */
@Tag("req:F-A07")
class JdkInternalsWitnessTest extends ExecutorTestSupport {
  private static final String CF = ExecutorInstrumentations.COMPLETABLE_FUTURE;

  /** The concrete stages of the six asynchronous dependent-stage methods. */
  private static final List<String> STAGES =
      Arrays.asList(
          "UniApply", "UniAccept", "UniRun", "UniCompose", "UniWhenComplete", "UniHandle");

  private static int javaVersion() {
    String spec = System.getProperty("java.specification.version");
    return spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec);
  }

  private static Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName(name, false, null);
  }

  @Test
  @DisplayName(
      "T-AG-052f (U-12) CompletableFuture internals: AsyncSupply/AsyncRun(dep, fn), Uni* stages with"
          + " tryFire(int), UniCompletion.executor, fn fields, ASYNC = 1")
  void completableFutureInternals() throws Exception {
    Field async = type(CF).getDeclaredField("ASYNC");
    assertThat(Modifier.isStatic(async.getModifiers())).isTrue();
    assertThat(async.getType()).isEqualTo(int.class);
    if (javaVersion() < 9) {
      // JDK 9+ does not open java.util.concurrent; there the propagation tests through an executor
      // outside the allow-list (TryFireAdvice acts in mode ASYNC only) witness the value.
      async.setAccessible(true);
      assertThat(async.getInt(null)).isEqualTo(ExecutorModule.CF_ASYNC_MODE);
    }
    for (String task : Arrays.asList("AsyncSupply", "AsyncRun")) {
      Class<?> type = type(CF + "$" + task);
      assertThat(Runnable.class.isAssignableFrom(type)).as(task).isTrue();
      assertThat(type.getDeclaredMethod("run").getReturnType()).isEqualTo(void.class);
      assertThat(type.getDeclaredField("fn")).as(task + ".fn").isNotNull();
      Constructor<?>[] constructors = type.getDeclaredConstructors();
      assertThat(constructors).hasSize(1);
      assertThat(constructors[0].getParameterTypes()).hasSize(2);
      assertThat(constructors[0].getParameterTypes()[0]).isEqualTo(type(CF));
    }
    Class<?> completion = type(CF + "$UniCompletion");
    assertThat(completion.getDeclaredField("executor").getType()).isEqualTo(Executor.class);
    assertThat(Modifier.isAbstract(completion.getModifiers())).isTrue();
    for (String stage : STAGES) {
      Class<?> type = type(CF + "$" + stage);
      assertThat(completion.isAssignableFrom(type)).as(stage).isTrue();
      assertThat(Modifier.isAbstract(type.getModifiers())).as(stage).isFalse();
      assertThat(type.getDeclaredMethod("tryFire", int.class)).as(stage).isNotNull();
      assertThat(type.getDeclaredField("fn")).as(stage + ".fn").isNotNull();
    }
    for (String method : ExecutorInstrumentations.ASYNC_STAGE_METHODS) {
      List<Class<?>> firstParameters = new ArrayList<Class<?>>();
      for (Method candidate : type(CF).getDeclaredMethods()) {
        if (candidate.getName().equals(method) && !candidate.isBridge()) {
          firstParameters.add(candidate.getParameterTypes()[0]);
        }
      }
      assertThat(firstParameters).as(method + "(fn[, executor])").hasSize(2);
    }
  }

  @Test
  @DisplayName(
      "T-AG-051l (U-12) fork/join and scheduled internals: doExec(), fork(), adapters take the"
          + " user task first, ScheduledFutureTask second")
  void forkJoinAndScheduledInternals() throws Exception {
    Class<?> task = type(ExecutorInstrumentations.FORK_JOIN_TASK);
    assertThat(task.getDeclaredMethod("doExec")).isNotNull();
    assertThat(task.getDeclaredMethod("fork")).isNotNull();
    List<String> adapters = new ArrayList<String>();
    for (Class<?> nested : task.getDeclaredClasses()) {
      String name = nested.getName();
      boolean adapter =
          name.startsWith(ExecutorInstrumentations.FORK_JOIN_ADAPTER_PREFIX)
              || name.equals(ExecutorInstrumentations.FORK_JOIN_EXECUTE_ACTION);
      if (!adapter || Modifier.isAbstract(nested.getModifiers())) {
        continue;
      }
      adapters.add(name.substring(name.indexOf('$') + 1));
      for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
        Class<?> first = constructor.getParameterTypes()[0];
        assertThat(first == Runnable.class || first == Callable.class)
            .as(name + " constructor takes the user task first")
            .isTrue();
      }
    }
    assertThat(adapters)
        .contains(
            "RunnableExecuteAction", "AdaptedRunnable", "AdaptedRunnableAction", "AdaptedCallable");
    Class<?> scheduled = type(ExecutorInstrumentations.SCHEDULED_FUTURE_TASK);
    assertThat(Modifier.isStatic(scheduled.getModifiers()))
        .as("inner class: the enclosing pool is constructor argument 0")
        .isFalse();
    for (Constructor<?> constructor : scheduled.getDeclaredConstructors()) {
      Class<?>[] parameters = constructor.getParameterTypes();
      assertThat(parameters[0]).isEqualTo(type("java.util.concurrent.ScheduledThreadPoolExecutor"));
      assertThat(parameters[1] == Runnable.class || parameters[1] == Callable.class)
          .as("user task is constructor argument 1")
          .isTrue();
    }
    assertThat(type(ExecutorInstrumentations.FUTURE_TASK).getDeclaredMethod("run")).isNotNull();
  }

  @Test
  @DisplayName(
      "T-AG-051l (U-12) ThreadPerTaskExecutor internals (JDK 21+): TaskRunner(executor, Runnable)"
          + " with run(), ThreadBoundFuture(executor, Callable)")
  void threadPerTaskInternals() throws Exception {
    if (javaVersion() < 21) {
      assertThat(classExists("java.util.concurrent.ThreadPerTaskExecutor")).isFalse();
      return;
    }
    Class<?> runner = type(ExecutorInstrumentations.TASK_RUNNER);
    assertThat(runner.getDeclaredConstructors()[0].getParameterTypes()[1])
        .isEqualTo(Runnable.class);
    assertThat(runner.getDeclaredMethod("run")).isNotNull();
    Class<?> future = type(ExecutorInstrumentations.THREAD_BOUND_FUTURE);
    assertThat(future.getDeclaredConstructors()[0].getParameterTypes()[1])
        .isEqualTo(Callable.class);
    assertThat(type(ExecutorInstrumentations.FUTURE_TASK).isAssignableFrom(future)).isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-005a the installer transformed the module's JDK classes (and only allow-listed ones),"
          + " no transformation error")
  void installerTransformedTheJdkClasses() throws Exception {
    List<String> expected =
        new ArrayList<String>(
            Arrays.asList(
                ExecutorInstrumentations.THREAD_POOL_EXECUTOR,
                ExecutorInstrumentations.SCHEDULED_FUTURE_TASK,
                ExecutorInstrumentations.FORK_JOIN_POOL,
                ExecutorInstrumentations.FORK_JOIN_TASK,
                ExecutorInstrumentations.FORK_JOIN_EXECUTE_ACTION,
                ExecutorInstrumentations.FUTURE_TASK,
                ExecutorInstrumentations.COMPLETABLE_FUTURE,
                ExecutorInstrumentations.ASYNC_SUPPLY,
                ExecutorInstrumentations.ASYNC_RUN));
    for (String stage : STAGES) {
      expected.add(CF + "$" + stage);
    }
    if (javaVersion() >= 21) {
      expected.add(ExecutorInstrumentations.TASK_RUNNER);
      expected.add(ExecutorInstrumentations.THREAD_BOUND_FUTURE);
    }
    for (String name : expected) {
      Class.forName(name, true, null); // loading transforms a class the installer has not seen
    }
    ExecutorTestInstaller.Result result = installed();
    assertThat(result.errors).isEmpty();
    assertThat(result.transformed).containsAll(expected);
    for (String name : new ArrayList<String>(result.transformed)) {
      if (name.startsWith("java.")) {
        assertThat(io.argos.agent.core.boot.IgnoreMatchers.isJdkAllowlisted(name))
            .as(name + " is on the JDK allow-list")
            .isTrue();
      }
    }
  }

  private static boolean classExists(String name) {
    try {
      type(name);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
