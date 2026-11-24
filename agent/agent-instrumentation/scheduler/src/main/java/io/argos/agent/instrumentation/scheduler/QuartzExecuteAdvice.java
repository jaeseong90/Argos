/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code execute(JobExecutionContext)} of every Quartz {@code Job} implementation: one
 * BATCH transaction {@code BATCH <group>.<name>} per job run; a delegating job or an override
 * calling {@code super.execute} adds nothing inside its own transaction, any other transaction gets
 * a METHOD step ({@link SchedulerHelper}). The job's exception passes unchanged.
 */
public final class QuartzExecuteAdvice {
  private QuartzExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.Argument(0) Object jobExecutionContext) {
    if (Argos.skip(SchedulerModule.MODULE_ID)) {
      return null;
    }
    return SchedulerHelper.enter(QuartzHelper.serviceName(jobExecutionContext));
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object opened, @Advice.Thrown Throwable error) {
    if (opened == null) {
      return;
    }
    SchedulerHelper.exit(opened, error, null, null);
  }
}
