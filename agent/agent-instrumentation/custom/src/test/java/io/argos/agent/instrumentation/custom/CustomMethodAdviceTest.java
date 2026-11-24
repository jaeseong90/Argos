/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The custom advice inlined by the installed module into fixture classes defined after the policy
 * (10 §6.10 "동작", F-A14): METHOD steps named {@code Class#method}, errors, orphans, globs,
 * generated classes, the as-transaction BATCH transaction and summary mode (10 §9.1).
 */
@Tag("req:F-A14")
@Tag("req:SEC-25")
class CustomMethodAdviceTest extends CustomTestSupport {
  private static final String SERVICE = "example.custom.Service";

  @Test
  @DisplayName(
      "F-A14 a matched method records one METHOD step named Class#method inside the transaction;"
          + " private methods match too, constructors never")
  void stepsInsideTransaction() throws Exception {
    policy(SERVICE + "#*");
    Callable<?> service = (Callable<?>) new FixtureLoader().instance(SERVICE);
    TraceContext ctx = begin();
    assertThat(service.call()).isEqualTo(3);
    Transaction tx = end(ctx);
    assertThat(stepNames(tx, StepType.METHOD))
        .containsExactly(SERVICE + "#call", SERVICE + "#work", SERVICE + "#helper");
    StepBuffer steps = tx.steps();
    assertThat(steps.parent(1)).as("work is a child of call").isEqualTo(0);
    assertThat(steps.isClosed(0)).isTrue();
    assertThat(steps.error(0)).isFalse();
  }

  @Test
  @DisplayName(
      "F-A14 without a transaction nothing is recorded, the step is counted as an orphan"
          + " (orphan_steps_total{type=METHOD})")
  void orphanWithoutTransaction() throws Exception {
    policy(SERVICE + "#work");
    Callable<?> service = (Callable<?>) new FixtureLoader().instance(SERVICE);
    long before = tracer.orphanSteps(StepType.METHOD);
    assertThat(service.call()).isEqualTo(3);
    assertThat(tracer.orphanSteps(StepType.METHOD) - before).isEqualTo(1);
    assertThat(ended).isEmpty();
  }

  @Test
  @DisplayName(
      "F-A14 a method that throws closes its step with the error flag and the application gets"
          + " the same exception instance")
  void errorFlagExceptionUnchanged() throws Exception {
    policy("example.custom.Failing#call");
    FixtureLoader loader = new FixtureLoader();
    Callable<?> failing = (Callable<?>) loader.instance("example.custom.Failing");
    Object failure = loader.loadClass("example.custom.Failing").getField("FAILURE").get(null);
    TraceContext ctx = begin();
    assertThatThrownBy(failing::call).isSameAs(failure);
    Transaction tx = end(ctx);
    assertThat(stepNames(tx, StepType.METHOD)).containsExactly("example.custom.Failing#call");
    assertThat(tx.steps().error(0)).isTrue();
  }

  @Test
  @DisplayName(
      "10 §6.10 globs: * stays within a package segment (and any characters of a method name),"
          + " ** crosses segments, ? is one character")
  void globs() throws Exception {
    List<String> classes =
        Arrays.asList(
            "example.custom.globs.Alpha",
            "example.custom.globs.Gamma1",
            "example.custom.globs.Gamma22",
            "example.custom.globs.deep.Beta");
    assertThat(stepsOfCalls("example.custom.globs.*#c*", classes))
        .containsExactly(
            "example.custom.globs.Alpha#call",
            "example.custom.globs.Gamma1#call",
            "example.custom.globs.Gamma22#call");
    assertThat(stepsOfCalls("example.custom.globs.**#call", classes))
        .containsExactly(
            "example.custom.globs.Alpha#call",
            "example.custom.globs.Gamma1#call",
            "example.custom.globs.Gamma22#call",
            "example.custom.globs.deep.Beta#call");
    assertThat(stepsOfCalls("example.custom.globs.Gamma?#cal?", classes))
        .containsExactly("example.custom.globs.Gamma1#call");
  }

  private List<String> stepsOfCalls(String item, List<String> classes) throws Exception {
    policy(item);
    FixtureLoader loader = new FixtureLoader();
    List<Callable<?>> instances = new ArrayList<Callable<?>>();
    for (String name : classes) {
      instances.add((Callable<?>) loader.instance(name));
    }
    TraceContext ctx = begin();
    for (Callable<?> instance : instances) {
      instance.call();
    }
    return stepNames(end(ctx), StepType.METHOD);
  }

  @Test
  @DisplayName(
      "T-SEC-25 an item with a forbidden prefix matches nothing, and ** never reaches a forbidden"
          + " class: java.lang.String#* and **#length give no step")
  void forbiddenNeverMatches() throws Exception {
    policy("java.lang.String#*", "**#length", SERVICE + "#work");
    Callable<?> service = (Callable<?>) new FixtureLoader().instance(SERVICE);
    TraceContext ctx = begin();
    assertThat("abc".length()).isEqualTo(3);
    service.call();
    assertThat(stepNames(end(ctx), StepType.METHOD)).containsExactly(SERVICE + "#work");
  }

  @Test
  @DisplayName(
      "10 §6.10 no double step through generated classes: a CGLIB-style subclass"
          + " (Service$$SpringCGLIB$$0) calling super, a JDK proxy defined in the fixture package"
          + " and a lambda give only the steps of the real methods; bridge methods are no steps")
  void generatedClassesNoDoubleStep() throws Exception {
    policy("example.custom.**#*");
    FixtureLoader loader = new FixtureLoader();
    Class<?> serviceType = loader.loadClass(SERVICE);
    Class<?> cglib =
        new ByteBuddy()
            .subclass(serviceType)
            .name(SERVICE + "$$SpringCGLIB$$0")
            .method(ElementMatchers.named("work"))
            .intercept(SuperMethodCall.INSTANCE)
            .make()
            .load(loader, ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    Object proxied = cglib.getConstructor().newInstance();
    Callable<?> proxyCaller = (Callable<?>) loader.instance("example.custom.ProxyCaller");
    Callable<?> generic = (Callable<?>) loader.instance("example.custom.Generic");
    Object service = serviceType.getConstructor().newInstance();
    @SuppressWarnings("unchecked")
    Callable<Object> lambda = (Callable<Object>) serviceType.getMethod("viaLambda").invoke(service);

    TraceContext ctx = begin();
    assertThat(cglib.getMethod("work").invoke(proxied)).isEqualTo(1);
    assertThat(proxyCaller.call()).isEqualTo(7);
    assertThat(lambda.call()).isEqualTo(1);
    assertThat(generic.call()).isEqualTo("generic");
    Transaction tx = end(ctx);

    String proxyClass =
        (String)
            loader.loadClass("example.custom.ProxyCaller").getField("proxyClassName").get(null);
    assertThat(proxyClass).as("the JDK proxy class is in the fixture package").contains("$Proxy");
    assertThat(stepNames(tx, StepType.METHOD))
        .containsExactly(
            SERVICE + "#work",
            "example.custom.ProxyCaller#call",
            "example.custom.HiddenImpl#value",
            SERVICE + "#work",
            "example.custom.Generic#call");
    assertThat(transformed(SERVICE + "$$SpringCGLIB$$0")).isFalse();
    assertThat(transformed(proxyClass)).isFalse();
    assertThat(lambda.getClass().getName()).contains("$$Lambda");
    assertThat(transformed(lambda.getClass().getName())).isFalse();
  }

  @Test
  @DisplayName(
      "10 §6.8/§6.10 an as-transaction item begins a BATCH transaction named BATCH <Class#method>"
          + " (fixed) when the thread has none, its callees are steps; inside a transaction it is a"
          + " METHOD step")
  void asTransaction() throws Exception {
    policy("example.custom.Job#run|true", "example.custom.Job#step");
    Runnable job = (Runnable) new FixtureLoader().instance("example.custom.Job");
    job.run();
    assertThat(ended).hasSize(1);
    Transaction batch = ended.get(0);
    TraceContext ctx = batch.context();
    assertThat(ctx.kind).isEqualTo(Kind.BATCH);
    assertThat(ctx.serviceName).isEqualTo("BATCH example.custom.Job#run");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ctx.error).isFalse();
    assertThat(stepNames(batch, StepType.METHOD)).containsExactly("example.custom.Job#step");
    assertThat(tracer.current()).as("the BATCH transaction is unbound at exit").isNull();

    TraceContext http = begin();
    job.run();
    Transaction tx = end(http);
    assertThat(ended).hasSize(2);
    assertThat(stepNames(tx, StepType.METHOD))
        .containsExactly("example.custom.Job#run", "example.custom.Job#step");
  }

  @Test
  @DisplayName(
      "10 §9.1 5,000 recursive calls of a custom method switch the transaction to summary mode:"
          + " at most max-steps recorded steps, the rest counted per name")
  void recursionSummaryMode() throws Exception {
    policy("example.custom.Recursive#depth");
    @SuppressWarnings("unchecked")
    final Function<Integer, Integer> recursive =
        (Function<Integer, Integer>) new FixtureLoader().instance("example.custom.Recursive");
    // 5,000 nested frames with the inlined advice: on a thread with a stack large enough for any
    // test JVM (the test worker's default stack is not), the transaction begun and ended there.
    final AtomicReference<Object> outcome = new AtomicReference<Object>();
    Thread deep =
        new Thread(
            null,
            () -> {
              try {
                TraceContext ctx = begin();
                Integer depth = recursive.apply(5_000);
                outcome.set(new Object[] {depth, end(ctx)});
              } catch (Throwable t) {
                outcome.set(t);
              }
            },
            "custom-recursion",
            256L * 1024 * 1024);
    deep.start();
    deep.join(TimeUnit.MINUTES.toMillis(1));
    assertThat(outcome.get()).isInstanceOf(Object[].class);
    Object[] result = (Object[]) outcome.get();
    assertThat(result[0]).isEqualTo(5_000);
    Transaction tx = (Transaction) result[1];
    StepBuffer steps = tx.steps();
    assertThat(steps.summarized()).isTrue();
    assertThat(steps.size()).isLessThanOrEqualTo(tracer.maxSteps());
    long summarized = 0;
    for (int key = 0; key < steps.summaryKeys(); key++) {
      assertThat(dictionary.text(steps.summaryNameId(key)))
          .isEqualTo("example.custom.Recursive#depth");
      summarized += steps.summaryCount(key);
    }
    assertThat(stepNames(tx, StepType.METHOD).size() + summarized).isEqualTo(5_001);
    assertThat(steps.totalSteps()).isEqualTo(5_001);
  }
}
