/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * The step of a method a plain {@code instrumentation.custom-methods} item names (10 §6.10 "동작"):
 * inside a transaction a {@code METHOD} step named {@code Class#method} (the {@code Advice.Origin}
 * constant of the instrumented method), closed on exit with the error flag when the method threw
 * (the exception itself is untouched); without a transaction only {@code countOrphan(METHOD)}. A
 * class marked by {@link CustomMatchers.DisabledClasses} (its item passed the class limit after it
 * was transformed) records nothing.
 */
public final class CustomMethodAdvice {
  private CustomMethodAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressWarnings({"unchecked", "rawtypes", "UnusedVariable"})
  public static TraceContext enter(
      @Advice.Origin("#t") String type,
      @Advice.Origin("#t\\##m") String name,
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
    TraceContext context = tracer.current();
    if (context == null) {
      tracer.countOrphan(StepType.METHOD);
      return null;
    }
    seq =
        tracer.stepStart(
            context, StepType.METHOD, tracer.dict(DictKind.METHOD, name), 0L, System.nanoTime());
    return seq < 0 ? null : context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Local("argosSeq") int seq,
      @Advice.Thrown Throwable error) {
    if (context == null) {
      return;
    }
    Argos.tracer().stepEnd(context, seq, 0, error != null, 0L, System.nanoTime());
  }
}
