/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * A method an {@code as-transaction} item names (10 §6.8 "설정 루트", §6.10): without a transaction on
 * the thread it begins a {@code BATCH} transaction with the fixed service name {@code BATCH
 * <Class#method>} and ends it on exit (status 0 as every BATCH transaction, 11 {@code http_status};
 * error when the method threw, the exception itself untouched); inside a transaction it records the
 * {@code METHOD} step of {@link CustomMethodAdvice}. A class marked by {@link
 * CustomMatchers.DisabledClasses} records nothing.
 */
public final class CustomTransactionAdvice {
  private CustomTransactionAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes", "UnusedVariable"})
  public static TraceContext enter(
      @Advice.Origin("#t") String type,
      @Advice.Origin("#t\\##m") String name,
      @Advice.Origin("BATCH #t\\##m") String serviceName,
      @Advice.Local("argosSeq") int seq) {
    if (Argos.skip(CustomModule.MODULE_ID)) {
      return null;
    }
    ContextStore<Object, Boolean> disabled =
        (ContextStore) ContextStore.of(CharSequence.class, Boolean.class);
    if (disabled.size() != 0 && disabled.get(type) != null) {
      return null;
    }
    Tracer tracer = Argos.tracer();
    TraceContext current = tracer.current();
    long now = System.nanoTime();
    if (current == null) {
      TraceContext began = tracer.begin(Kind.BATCH, serviceName, now);
      if (began == null) {
        return null;
      }
      tracer.setServiceName(began, serviceName, true);
      seq = CustomModule.BEGAN;
      return began;
    }
    seq = tracer.stepStart(current, StepType.METHOD, tracer.dict(DictKind.METHOD, name), 0L, now);
    return seq < 0 ? null : current;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Local("argosSeq") int seq,
      @Advice.Thrown Throwable error) {
    if (context == null) {
      return;
    }
    Tracer tracer = Argos.tracer();
    if (seq == CustomModule.BEGAN) {
      tracer.end(context, 0, null, null, error, System.nanoTime());
      return;
    }
    tracer.stepEnd(context, seq, 0, error != null, 0L, System.nanoTime());
  }
}
