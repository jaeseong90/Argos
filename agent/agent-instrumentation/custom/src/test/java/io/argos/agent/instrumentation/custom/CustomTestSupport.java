/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.boot.CustomTestInstaller;
import io.argos.agent.core.command.CustomMethodPolicy;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.instrumentation.custom.testagent.InstrumentationHolderAgent;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The custom module installed, as the agent does, into the test JVM with the core's installer (once
 * per JVM), a real tracer per test and the policy each test installs before it defines its fixture
 * classes in a fresh {@link FixtureLoader}.
 */
abstract class CustomTestSupport {
  private static CustomTestInstaller.Result installed;
  private static AgentControl control;

  final Dictionary dictionary = new Dictionary();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  TracerImpl tracer;
  private int errorsBefore;

  /** Installs the module once per JVM; returns what the installer recorded. */
  static synchronized CustomTestInstaller.Result installed() {
    if (installed == null) {
      Instrumentation inst = InstrumentationHolderAgent.instrumentation();
      assertThat(inst).as("-javaagent holder of build.gradle.kts").isNotNull();
      assertThat(Argos.class.getClassLoader())
          .as("bootstrap API on the boot class path, as with the agent jar")
          .isNull();
      installed = CustomTestInstaller.install(inst, new CustomModule());
      control = TestControls.newControl();
    }
    return installed;
  }

  static Instrumentation instrumentation() {
    return InstrumentationHolderAgent.instrumentation();
  }

  @BeforeEach
  void installTracer() {
    installed();
    CustomMethodPolicy.install(null);
    tracer = TracerImpl.builder().control(control).dictionary(dictionary).sink(ended::add).build();
    control.installTracer(tracer);
    control.setModuleState(CustomModule.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(CustomModule.MODULE_ID);
  }

  @AfterEach
  void uninstallTracer() {
    CustomMethodPolicy.install(null);
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(CustomModule.MODULE_ID, ModuleHealth.ENABLED);
    control.installTracer(null);
    assertThat(leftover).as("no transaction left bound to the test thread").isNull();
    assertThat(ModuleHealth.errors(CustomModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(errorsBefore);
    assertThat(installed.errors).as("transformation errors").isEmpty();
  }

  /** Installs the policy of {@code items} (at most {@code max} classes per item). */
  static CustomMethodPolicy policy(int max, String... items) {
    CustomMethodPolicy policy = CustomMethodPolicy.of(Arrays.asList(items), max);
    CustomMethodPolicy.install(policy);
    return policy;
  }

  /** Installs the policy of {@code items} with the default limit (500). */
  static CustomMethodPolicy policy(String... items) {
    return policy(CustomMethodPolicy.DEFAULT_MAX_CLASSES_PER_PATTERN, items);
  }

  TraceContext begin() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /custom", System.nanoTime());
    assertThat(ctx).isNotNull();
    return ctx;
  }

  /** Ends {@code ctx} and returns its finished transaction. */
  Transaction end(TraceContext ctx) {
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    return endedOf(ctx);
  }

  /** The finished transaction of {@code ctx}. */
  Transaction endedOf(TraceContext ctx) {
    synchronized (ended) {
      for (Transaction tx : ended) {
        if (tx.context() == ctx) {
          return tx;
        }
      }
    }
    throw new AssertionError("transaction not ended: " + ctx.txId);
  }

  /** Names of the recorded steps of {@code type} in {@code tx}, in step order. */
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

  /** True when the installer transformed a class named {@code name}. */
  static boolean transformed(String name) {
    return installed().transformed.contains(name);
  }
}
