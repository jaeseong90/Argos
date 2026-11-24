/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into Spring's {@code ScheduledMethodRunnable.run()}: one BATCH transaction {@code BATCH
 * <user class>#<method>} per run of a {@code @Scheduled} method, or a METHOD step inside an
 * existing transaction ({@link SchedulerHelper}). The exception of the method passes unchanged.
 */
public final class ScheduledRunAdvice {
  private ScheduledRunAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.This Object runnable) {
    if (Argos.skip(SchedulerModule.MODULE_ID)) {
      return null;
    }
    return SchedulerHelper.enter(ScheduledHelper.serviceName(runnable));
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object opened, @Advice.Thrown Throwable error) {
    if (opened == null) {
      return;
    }
    SchedulerHelper.exit(opened, error, null, null);
  }
}
