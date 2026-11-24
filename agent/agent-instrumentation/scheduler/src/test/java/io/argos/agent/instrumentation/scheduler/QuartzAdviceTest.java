/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.jobs.QuartzFixtures;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Transaction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-AG-058 (unit part, 10 §6.8): Quartz jobs fired by a real scheduler (Quartz 2.3.2 in {@code
 * test}, 2.5.2 in {@code testModern} and {@code testSpring7}) on its worker thread, real tracer.
 */
@Tag("req:F-A24")
@Tag("req:D-20")
@Timeout(120)
class QuartzAdviceTest extends SchedulerTestSupport {

  @Test
  @DisplayName(
      "T-AG-058: one Quartz job run = one BATCH transaction 'BATCH <group>.<name>' on the worker"
          + " thread, fixed, status 0, no error")
  void quartzJobRun() throws Exception {
    QuartzFixtures.fireOnce("report", "reports", "quartzReportJob", false);

    List<Transaction> batch = awaitBatch(1);
    assertThat(batch).hasSize(1);
    TraceContext ctx = batch.get(0).context();
    assertThat(ctx.kind).isEqualTo(Kind.BATCH);
    assertThat(ctx.serviceName).isEqualTo("BATCH reports.quartzReportJob");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ctx.status).isZero();
    assertThat(ctx.error).isFalse();
    assertThat(batch.get(0).threadName()).isNotEqualTo(Thread.currentThread().getName());
    assertThat(stepNames(batch.get(0), StepType.METHOD)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-058: a job delegating to another Job.execute with the same context is one"
          + " transaction without an extra step")
  void delegatingJobIsOneTransaction() throws Exception {
    QuartzFixtures.fireOnce("delegating", "reports", "wrappedJob", false);

    List<Transaction> batch = awaitBatch(1);
    assertThat(batch).hasSize(1);
    assertThat(batch.get(0).context().serviceName).isEqualTo("BATCH reports.wrappedJob");
    assertThat(batch.get(0).steps().size()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-058: a failing job: the transaction is an error with the JobExecutionException class"
          + " and message")
  void failingJob() throws Exception {
    QuartzFixtures.fireOnce("report", "reports", "failingJob", true);

    List<Transaction> batch = awaitBatch(1);
    assertThat(batch).hasSize(1);
    assertThat(batch.get(0).context().error).isTrue();
    assertThat(batch.get(0).errorClass()).isEqualTo("org.quartz.JobExecutionException");
    assertThat(batch.get(0).errorMessage()).isEqualTo("report job failed");
  }

  @Test
  @DisplayName(
      "T-AG-058: Job.execute inside an HTTP transaction (manual run) is a METHOD step"
          + " '<group>.<name>', no new transaction; its exception passes unchanged")
  void insideHttpTransactionIsAStep() {
    TraceContext http = beginHttp("POST /batch/quartz/{job}");
    Throwable none = catchThrowable(() -> QuartzFixtures.executeOnThisThread("reports", "manual"));
    Transaction tx = end(http);

    assertThat(none).isNull();
    assertThat(endedOfKind(Kind.BATCH)).isEmpty();
    assertThat(stepNames(tx, StepType.METHOD)).containsExactly("reports.manual");
  }
}
