/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.boot.SchedulerTestInstaller;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.instrumentation.scheduler.testagent.InstrumentationHolderAgent;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The scheduler module installed, as the agent does, into the test JVM with the core's installer
 * (once per JVM, before any library class is loaded), and a real tracer per test that keeps the
 * ended transactions.
 *
 * <p>The test classes never name a library type (not even in a lambda): JUnit reads their methods
 * before the first test runs, which would load Spring, Quartz or Spring Batch classes before the
 * module is installed. Every library call goes through the fixtures of {@code com.example.jobs} (an
 * application package, not ignored by the agent), whose signatures use JDK types only.
 */
abstract class SchedulerTestSupport {
  private static SchedulerTestInstaller.Result installed;
  private static AgentControl control;

  final Dictionary dictionary = new Dictionary();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  TracerImpl tracer;
  private int errorsBefore;

  /** Installs the module once per JVM; returns what the installer recorded. */
  static synchronized SchedulerTestInstaller.Result installed() {
    if (installed == null) {
      Instrumentation inst = InstrumentationHolderAgent.instrumentation();
      assertThat(inst).as("-javaagent holder of build.gradle.kts").isNotNull();
      assertThat(Argos.class.getClassLoader())
          .as("bootstrap API on the boot class path, as with the agent jar")
          .isNull();
      for (String library :
          new String[] {
            SchedulerModule.SCHEDULED_METHOD_RUNNABLE,
            SchedulerModule.QUARTZ_JOB,
            SchedulerModule.BATCH_ABSTRACT_STEP
          }) {
        assertThat(loaded(inst, library)).as("%s loaded before the install", library).isFalse();
      }
      installed = SchedulerTestInstaller.install(inst, new SchedulerModule());
      control = TestControls.newControl();
    }
    return installed;
  }

  private static boolean loaded(Instrumentation inst, String className) {
    for (Class<?> type : inst.getAllLoadedClasses()) {
      if (type.getName().equals(className)) {
        return true;
      }
    }
    return false;
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
    control.setModuleState(SchedulerModule.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(SchedulerModule.MODULE_ID);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(SchedulerModule.MODULE_ID, ModuleHealth.ENABLED);
    control.installTracer(null);
    assertThat(leftover).as("no transaction left bound to the test thread").isNull();
    assertThat(ModuleHealth.errors(SchedulerModule.MODULE_ID))
        .as("advice and helper errors (suppressed exceptions)")
        .isEqualTo(errorsBefore);
    assertThat(installed.errors).as("transformation errors").isEmpty();
  }

  /** Begins an HTTP transaction on the test thread (a job started by a request). */
  TraceContext beginHttp(String name) {
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

  /** The ended transactions of {@code kind}, in end order. */
  List<Transaction> endedOfKind(int kind) {
    List<Transaction> result = new ArrayList<Transaction>();
    synchronized (ended) {
      for (Transaction tx : ended) {
        if (tx.context().kind == kind) {
          result.add(tx);
        }
      }
    }
    return result;
  }

  /** Waits until {@code count} BATCH transactions ended (jobs on other threads). */
  List<Transaction> awaitBatch(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    List<Transaction> batch = endedOfKind(Kind.BATCH);
    while (batch.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(10);
      batch = endedOfKind(Kind.BATCH);
    }
    assertThat(batch).as("ended BATCH transactions").hasSizeGreaterThanOrEqualTo(count);
    return batch;
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

  /** Indexes of the recorded steps of {@code type} in {@code tx}. */
  List<Integer> stepsOf(Transaction tx, int type) {
    StepBuffer steps = tx.steps();
    List<Integer> result = new ArrayList<Integer>();
    for (int seq = 0; seq < steps.size(); seq++) {
      if (steps.type(seq) == type) {
        result.add(seq);
      }
    }
    return result;
  }
}
