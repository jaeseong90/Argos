/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.shaded.net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code java.util.concurrent.ThreadPoolExecutor.execute} (java.base): records an
 * ASYNC step when a transaction is current, else counts an orphan. Bootstrap API only (no helper
 * can be injected into the bootstrap class loader).
 */
public final class ExecuteAdvice {
  private ExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter() {
    if (Argos.skip(ProbeJdkModule.MODULE_ID)) {
      return;
    }
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.current();
    if (context == null) {
      tracer.countOrphan(StepType.ASYNC);
      return;
    }
    long now = System.nanoTime();
    int seq =
        tracer.stepStart(
            context,
            StepType.ASYNC,
            tracer.dict(DictKind.METHOD, "java.util.concurrent.ThreadPoolExecutor#execute"),
            0L,
            now);
    tracer.stepEnd(context, seq, 0, false, 0L, now);
  }
}
