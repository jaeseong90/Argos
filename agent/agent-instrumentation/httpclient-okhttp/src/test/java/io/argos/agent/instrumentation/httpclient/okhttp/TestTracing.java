/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.context.PropagationSettings;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TraceIds;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The real tracer of a module test, installed through the bootstrap {@code Argos} facade (so the
 * advised library classes call it), with the ended transactions kept. Also restores the module
 * state and checks that no advice failed ({@code ModuleHealth.errors}).
 */
final class TestTracing {
  /** The outcome of one call made inside a transaction. */
  static final class Outcome {
    final Transaction tx;
    final Object value;
    final Throwable error;

    Outcome(Transaction tx, Object value, Throwable error) {
      this.tx = tx;
      this.value = value;
      this.error = error;
    }

    StepBuffer steps() {
      return tx.steps();
    }

    String traceId() {
      TraceContext ctx = tx.context();
      return TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo);
    }
  }

  final AgentControl control = TestControls.newControl();
  final Dictionary dictionary = new Dictionary();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  private final int moduleId;
  TracerImpl tracer;
  private int errorsBefore;

  TestTracing(int moduleId) {
    this.moduleId = moduleId;
  }

  void install() {
    tracer = TracerImpl.builder().control(control).dictionary(dictionary).sink(ended::add).build();
    control.installTracer(tracer);
    control.setModuleState(moduleId, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(moduleId);
  }

  void uninstall() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(moduleId, ModuleHealth.ENABLED);
    control.installTracer(null);
    assertThat(ModuleHealth.errors(moduleId))
        .as("advice and helper errors of module %s (suppressed exceptions)", moduleId)
        .isEqualTo(errorsBefore);
  }

  /** {@code propagation.exclude-hosts}, {@code argos-state.hosts}, {@code preserve-incoming}. */
  void propagation(List<String> excludeHosts, List<String> argosStateHosts) {
    tracer.setPropagation(PropagationSettings.of(excludeHosts, argosStateHosts, true));
  }

  /** Runs {@code body} inside a new HTTP transaction and ends it. */
  Outcome inTransaction(Callable<?> body) {
    ended.clear();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/pay/{id}", System.nanoTime());
    assertThat(ctx).isNotNull();
    Object value = null;
    Throwable error = null;
    try {
      value = body.call();
    } catch (Throwable t) {
      error = t;
    }
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
    return new Outcome(ended.get(0), value, error);
  }

  /** The dictionary text of a step's name id (the HOST text of ADR-0010 C-142). */
  String text(long id) {
    return dictionary.text(id);
  }

  /** Calls the public static method {@code name} of {@code type}, unwrapping its exception. */
  static Object call(Class<?> type, String name, Object... args) throws Exception {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
        try {
          return method.invoke(null, args);
        } catch (InvocationTargetException e) {
          Throwable cause = e.getCause();
          if (cause instanceof Exception) {
            throw (Exception) cause;
          }
          if (cause instanceof Error) {
            throw (Error) cause;
          }
          throw e;
        }
      }
    }
    throw new NoSuchMethodException(type.getName() + "." + name);
  }
}
