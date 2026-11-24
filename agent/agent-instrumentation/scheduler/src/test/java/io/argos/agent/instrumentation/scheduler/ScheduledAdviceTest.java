/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.jobs.ScheduledFixtures;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Transaction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-AG-058 (unit part, 10 §6.8): Spring {@code @Scheduled} methods through the real {@code
 * ScheduledMethodRunnable} of Spring 5.3 ({@code test}), 6.2 ({@code testModern}) and 7.0 ({@code
 * testSpring7}), with the module installed by the core's installer and the real tracer.
 */
@Tag("req:F-A24")
@Tag("req:D-20")
@Timeout(120)
class ScheduledAdviceTest extends SchedulerTestSupport {

  @Test
  @DisplayName(
      "T-AG-058: one @Scheduled run in a Spring context = one BATCH transaction named"
          + " 'BATCH <user class>#<method>' (not the CGLIB subclass), fixed, status 0, no error")
  void scheduledRunInSpringContext() throws Exception {
    String[] run = ScheduledFixtures.runOnceInSpringContext();
    assertThat(run[0]).as("Spring ran the method on a CGLIB subclass").contains("$$");

    List<Transaction> batch = awaitBatch(1);
    assertThat(batch).hasSize(1);
    TraceContext ctx = batch.get(0).context();
    assertThat(ctx.kind).isEqualTo(Kind.BATCH);
    assertThat(ctx.serviceName)
        .isEqualTo("BATCH com.example.jobs.ScheduledJobsConfig#refreshStats");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ctx.status).isZero();
    assertThat(ctx.error).isFalse();
    assertThat(batch.get(0).threadName()).isEqualTo(run[1]);
    assertThat(endedOfKind(Kind.HTTP)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-058: a @Scheduled method that throws: the same exception reaches the caller, the"
          + " transaction is an error with that exception's class")
  void failingScheduledMethod() throws Exception {
    Throwable thrown = catchThrowable(() -> ScheduledFixtures.runOnThisThread("fail"));

    assertThat(thrown).isSameAs(ScheduledFixtures.lastFailure()).isNotNull();
    List<Transaction> batch = endedOfKind(Kind.BATCH);
    assertThat(batch).hasSize(1);
    assertThat(batch.get(0).context().serviceName)
        .isEqualTo("BATCH com.example.jobs.PlainJobs#fail");
    assertThat(batch.get(0).context().error).isTrue();
    assertThat(batch.get(0).errorClass()).isEqualTo(IllegalStateException.class.getName());
    assertThat(batch.get(0).errorMessage()).isEqualTo("scheduled job failed");
    assertThat(tracer.current()).isNull();
  }

  @Test
  @DisplayName(
      "T-AG-058: a scheduled method run inside an HTTP transaction (manual run) creates no"
          + " transaction, only a METHOD step '<class>#<method>' of the HTTP transaction")
  void insideHttpTransactionIsAStep() throws Exception {
    TraceContext http = beginHttp("POST /batch/run/{job}");
    ScheduledFixtures.runOnThisThread("work");
    assertThat(tracer.current()).isSameAs(http);
    Transaction tx = end(http);

    assertThat(endedOfKind(Kind.BATCH)).isEmpty();
    assertThat(stepNames(tx, StepType.METHOD)).containsExactly("com.example.jobs.PlainJobs#work");
    int seq = stepsOf(tx, StepType.METHOD).get(0);
    assertThat(tx.steps().isClosed(seq)).isTrue();
    assertThat(tx.steps().error(seq)).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-058: a failing scheduled method inside an HTTP transaction marks its METHOD step as"
          + " an error and still throws the application's exception")
  void failingInsideHttpTransaction() {
    TraceContext http = beginHttp("POST /batch/run/{job}");
    Throwable thrown = catchThrowable(() -> ScheduledFixtures.runOnThisThread("fail"));
    Transaction tx = end(http);

    assertThat(thrown).isSameAs(ScheduledFixtures.lastFailure());
    int seq = stepsOf(tx, StepType.METHOD).get(0);
    assertThat(tx.steps().error(seq)).isTrue();
    assertThat(endedOfKind(Kind.BATCH)).isEmpty();
  }

  @Test
  @DisplayName("instrumentation.disabled=scheduler: a scheduled run records nothing")
  void disabledModule() throws Exception {
    control().setModuleState(SchedulerModule.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    ScheduledFixtures.runOnThisThread("work");
    assertThat(ended).isEmpty();
    assertThat(tracer.current()).isNull();
  }
}
