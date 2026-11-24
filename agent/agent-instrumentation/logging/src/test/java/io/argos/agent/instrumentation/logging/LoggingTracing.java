/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TraceIds;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The real tracer of the logging module tests, installed through the bootstrap {@code Argos} facade
 * (so that the advised logging classes call it), with the ended transactions kept. Restores the
 * module state and checks that no advice failed ({@code ModuleHealth.errors}).
 */
final class LoggingTracing {
  static final byte[] SALT = "wo18-logging-module-test-salt-32".getBytes(StandardCharsets.UTF_8);

  final AgentControl control = TestControls.newControl();
  final List<Transaction> ended = Collections.synchronizedList(new ArrayList<Transaction>());
  final AgentCounters counters = new AgentCounters();
  TracerImpl tracer;
  private int errorsBefore;

  void install() {
    tracer =
        TracerImpl.builder()
            .control(control)
            .counters(counters)
            .sink(ended::add)
            .userIdSalts(() -> SALT)
            .build();
    control.installTracer(tracer);
    control.setCapture(CaptureConfig.DEFAULT);
    control.setModuleState(LoggingModule.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(LoggingModule.MODULE_ID);
  }

  void uninstall() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setCapture(CaptureConfig.DEFAULT);
    control.setModuleState(LoggingModule.MODULE_ID, ModuleHealth.ENABLED);
    control.installTracer(null);
    assertThat(ModuleHealth.errors(LoggingModule.MODULE_ID))
        .as("advice and helper errors of the logging module (suppressed exceptions)")
        .isEqualTo(errorsBefore);
  }

  /** {@code capture.user-id.mode} and {@code capture.user-id.source}. */
  void userId(int mode, String source) {
    control.setCapture(CaptureConfig.builder().userIdMode(mode).userIdSource(source).build());
  }

  /** Runs {@code body} inside a new HTTP transaction, ends it and returns its record. */
  Transaction inTransaction(Callable<?> body) throws Exception {
    ended.clear();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/mdc-log", System.nanoTime());
    assertThat(ctx).isNotNull();
    try {
      body.call();
    } finally {
      tracer.end(ctx, 200, null, null, null, System.nanoTime());
    }
    assertThat(ended).hasSize(1);
    return ended.get(0);
  }

  /** The trace id of {@code tx} as the core formats it (32 lower-case hex digits). */
  static String traceId(Transaction tx) {
    TraceContext ctx = tx.context();
    return TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo);
  }

  /** The transaction id of {@code tx} as the core formats it (16 lower-case hex digits). */
  static String txId(Transaction tx) {
    return TraceIds.hex16(tx.context().txId);
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
