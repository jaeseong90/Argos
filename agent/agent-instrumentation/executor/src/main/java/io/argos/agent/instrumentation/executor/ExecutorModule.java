/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Context propagation through {@code java.util.concurrent.ThreadPoolExecutor} (10 §6.6, F-A07,
 * DSN-02-05), WO-02 P-6 scope: one JDK class of {@code java.base}, three methods, no wrapping.
 *
 * <ul>
 *   <li>{@code execute(Runnable)} enter (submitting thread): the current transaction is attached to
 *       the task object ({@link io.argos.agent.bootstrap.ContextStore}, weak identity keys). {@code
 *       submit()} and {@code CompletableFuture.*Async(.., executor)} go through {@code execute}
 *       with a {@code FutureTask}/{@code AsyncSupply} task, lambdas are tasks like any other.
 *   <li>{@code beforeExecute(Thread, Runnable)} enter (worker thread, right before {@code
 *       task.run()}, also for a worker's first task): the attached transaction is removed from the
 *       store and activated on the worker; with {@code async.step=true} an ASYNC step named after
 *       the task class opens.
 *   <li>{@code afterExecute(Runnable, Throwable)} enter (worker thread, right after the task): the
 *       step closes and the previous binding of the worker is restored.
 * </ul>
 *
 * <p>The task object is never replaced, so {@code ThreadPoolExecutor.remove(task)} and the queue
 * keep working (no {@code Runnable} wrapping, absolute rule). The advice runs in the bootstrap
 * class loader: bootstrap API only, no helper (10 §5.4).
 *
 * <p>Limits of the PoC point (WO-15 adds the execution-side points of 10 §6.6, {@code
 * FutureTask.run} and {@code Runnable.run}): a subclass that overrides {@code beforeExecute} or
 * {@code afterExecute} without calling {@code super} (Spring 6.1+ {@code ThreadPoolTaskExecutor})
 * gets no propagation; {@code ScheduledThreadPoolExecutor} schedules without {@code execute}; tasks
 * run by the caller ({@code CallerRunsPolicy}) keep their thread's context anyway. If the module is
 * disabled between the two hooks of one task, the worker keeps the binding until its next task.
 */
public final class ExecutorModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.EXECUTOR;

  static final String EXECUTOR_CLASS = "java.util.concurrent.ThreadPoolExecutor";

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
    return Collections.<TypeInstrumentation>singletonList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(EXECUTOR_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(execute(), ExecuteAdvice.class.getName());
            transformer.applyAdvice(beforeExecute(), BeforeExecuteAdvice.class.getName());
            transformer.applyAdvice(afterExecute(), AfterExecuteAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "executor(" + EXECUTOR_CLASS + ")";
          }
        });
  }

  static ElementMatcher.Junction<MethodDescription> execute() {
    return named("execute").and(takesArguments(1)).and(takesArgument(0, Runnable.class));
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

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"java.util.concurrent.ThreadPoolExecutor (JDK 8-25)"};
  }
}
