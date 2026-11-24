/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into Spring Batch's {@code AbstractStep.execute(StepExecution)}: one BATCH transaction
 * {@code BATCH <job>.<step>} per step execution, or a METHOD step inside an existing transaction
 * ({@link SchedulerHelper}). {@code AbstractStep} records a failure on the step execution instead
 * of throwing it, so the exit marks the error from a thrown exception, the first failure exception
 * or a FAILED status.
 */
public final class BatchStepExecuteAdvice {
  private BatchStepExecuteAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.Argument(0) Object stepExecution) {
    if (Argos.skip(SchedulerModule.MODULE_ID)) {
      return null;
    }
    return SchedulerHelper.enter(BatchStepHelper.serviceName(stepExecution));
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object opened,
      @Advice.Argument(0) Object stepExecution,
      @Advice.Thrown Throwable thrown) {
    if (opened == null) {
      return;
    }
    Throwable error = thrown != null ? thrown : BatchStepHelper.failure(stepExecution);
    boolean failedWithoutException = error == null && BatchStepHelper.failed(stepExecution);
    SchedulerHelper.exit(
        opened,
        error,
        failedWithoutException ? BatchStepHelper.FAILED_STATUS_CLASS : null,
        failedWithoutException ? "FAILED" : null);
  }
}
