/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.app.AppTasks;
import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.boot.ExecutorTestInstaller;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.instrumentation.executor.testagent.InstrumentationHolderAgent;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.MethodCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The executor module installed, as the agent does, into the test JVM with the core's installer
 * (once per JVM: the JDK classes stay transformed), and a real tracer per test. Steps recorded by a
 * task ({@link #record}) show where its work landed.
 */
abstract class ExecutorTestSupport {
  private static ExecutorTestInstaller.Result installed;
  private static AgentControl control;

  final Dictionary dictionary = new Dictionary();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  TracerImpl tracer;
  private int errorsBefore;

  /** Installs the module once per JVM; returns what the installer recorded. */
  static synchronized ExecutorTestInstaller.Result installed() {
    if (installed == null) {
      Instrumentation inst = InstrumentationHolderAgent.instrumentation();
      assertThat(inst).as("-javaagent holder of build.gradle.kts").isNotNull();
      assertThat(Argos.class.getClassLoader())
          .as("bootstrap API on the boot class path, as with the agent jar")
          .isNull();
      preloadNestedJdkClasses();
      installed = ExecutorTestInstaller.install(inst, new ExecutorModule());
      control = TestControls.newControl();
    }
    return installed;
  }

  /**
   * Loads the nested classes of the allow-listed JDK classes before the install, so that the
   * install retransforms them together with their outer classes. Without this, retransforming an
   * outer class that is already loaded (CompletableFuture: Gradle's test worker loads it) loads its
   * nested classes from inside the transformer, where ByteBuddy's circularity lock skips them: the
   * stages would stay untransformed (reported to the core as an open issue: the premain discovery
   * is single pass).
   */
  private static void preloadNestedJdkClasses() {
    List<String> outers =
        new ArrayList<String>(
            java.util.Arrays.asList(
                ExecutorInstrumentations.COMPLETABLE_FUTURE,
                ExecutorInstrumentations.FORK_JOIN_TASK,
                "java.util.concurrent.ScheduledThreadPoolExecutor",
                "java.util.concurrent.ThreadPerTaskExecutor"));
    for (String outer : outers) {
      try {
        Class<?>[] nested = Class.forName(outer, false, null).getDeclaredClasses();
        assertThat(nested).isNotEmpty();
      } catch (ClassNotFoundException e) {
        // ThreadPerTaskExecutor exists from JDK 21.
      }
    }
  }

  static AgentControl control() {
    installed();
    return control;
  }

  @BeforeEach
  void installTracer() {
    installed();
    tracer = TracerImpl.builder().control(control).dictionary(dictionary).sink(ended::add).build();
    control.installTracer(tracer);
    control.setModuleState(ExecutorModule.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(ExecutorModule.MODULE_ID);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(ExecutorModule.MODULE_ID, ModuleHealth.ENABLED);
    control.installTracer(null);
    assertThat(leftover).as("no transaction left bound to the test thread").isNull();
    assertThat(ModuleHealth.errors(ExecutorModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(errorsBefore);
    assertThat(installed.errors).as("transformation errors").isEmpty();
  }

  void asyncSteps(boolean on) {
    tracer.setCapture(CaptureConfig.DEFAULT.withAsyncStep(on));
  }

  TraceContext begin(String name) {
    TraceContext ctx = tracer.begin(Kind.HTTP, name, System.nanoTime());
    assertThat(ctx).isNotNull();
    return ctx;
  }

  /** Ends {@code ctx} and returns its finished transaction. */
  Transaction end(TraceContext ctx) {
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    synchronized (ended) {
      for (Transaction tx : ended) {
        if (tx.context() == ctx) {
          return tx;
        }
      }
    }
    throw new AssertionError("transaction not ended: " + ctx.txId);
  }

  /** Records a closed METHOD step named {@code name} on the transaction current on this thread. */
  void record(String name) {
    TraceContext ctx = tracer.current();
    if (ctx == null) {
      return;
    }
    int seq =
        tracer.stepStart(
            ctx, StepType.METHOD, tracer.dict(DictKind.METHOD, name), 0L, System.nanoTime());
    tracer.stepEnd(ctx, seq, 0, false, 0L, System.nanoTime());
  }

  private static Class<?> freshRunnableType;
  private static Class<?> freshCallableType;

  /**
   * An application {@code Runnable} whose class the test JVM defines after the module was
   * installed, so the agent transformed it on definition (execution-side advice on {@code run()},
   * the context field of DSN-02-05); the classes of the test sources may be loaded earlier by the
   * test framework.
   */
  static synchronized Runnable freshRunnable(Runnable body) throws Exception {
    installed();
    if (freshRunnableType == null) {
      freshRunnableType =
          new ByteBuddy()
              .subclass(
                  AppTasks.BodyHolder.class,
                  ConstructorStrategy.Default.IMITATE_SUPER_CLASS_OPENING)
              .implement(Runnable.class)
              .name("com.example.gen.FreshRunnable")
              .defineMethod("run", void.class, Visibility.PUBLIC)
              .intercept(MethodCall.invoke(AppTasks.BodyHolder.class.getMethod("invokeBody")))
              .make()
              .load(
                  ExecutorTestSupport.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
              .getLoaded();
    }
    return (Runnable)
        freshRunnableType.getConstructor(Runnable.class, Callable.class).newInstance(body, null);
  }

  /** The {@code Callable} counterpart of {@link #freshRunnable}. */
  static synchronized Callable<?> freshCallable(Callable<?> body) throws Exception {
    installed();
    if (freshCallableType == null) {
      freshCallableType =
          new ByteBuddy()
              .subclass(
                  AppTasks.BodyHolder.class,
                  ConstructorStrategy.Default.IMITATE_SUPER_CLASS_OPENING)
              .implement(Callable.class)
              .name("com.example.gen.FreshCallable")
              .defineMethod("call", Object.class, Visibility.PUBLIC)
              .throwing(Exception.class)
              .intercept(MethodCall.invoke(AppTasks.BodyHolder.class.getMethod("invokeCallBody")))
              .make()
              .load(
                  ExecutorTestSupport.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
              .getLoaded();
    }
    return (Callable<?>)
        freshCallableType.getConstructor(Runnable.class, Callable.class).newInstance(null, body);
  }

  /** Names of the steps of {@code type} in {@code tx}, in step order. */
  List<String> stepNames(Transaction tx, int type) {
    StepBuffer steps = tx.steps();
    List<String> names = new ArrayList<String>();
    for (int seq = 0; seq < steps.size(); seq++) {
      if (steps.type(seq) == type) {
        names.add(dictionary.text(steps.nameId(seq)));
      }
    }
    return names;
  }
}
