/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobs.BatchFixtures;
import com.example.jobs.QuartzFixtures;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.Transaction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-AG-058 (unit part, 10 §6.8, §9.1): Spring Batch steps (4.3.10 in {@code test}, 5.2.6 in {@code
 * testModern}) executed through {@code AbstractStep.execute}: a real chunk-oriented {@code
 * TaskletStep} with a transaction per chunk, real tracer.
 */
@Tag("req:F-A24")
@Tag("req:D-20")
@Timeout(120)
class SpringBatchAdviceTest extends SchedulerTestSupport {

  @Test
  @DisplayName(
      "T-AG-058: a step executed outside any transaction = one BATCH transaction"
          + " 'BATCH <job>.<step>', fixed, status 0, no error")
  void stepIsBatchTransaction() throws Exception {
    String status = BatchFixtures.runChunkStep("settlementJob", "prepareStep", 20, 5, false, -1);

    assertThat(status).isEqualTo("COMPLETED");
    List<Transaction> batch = endedOfKind(Kind.BATCH);
    assertThat(batch).hasSize(1);
    TraceContext ctx = batch.get(0).context();
    assertThat(ctx.serviceName).isEqualTo("BATCH settlementJob.prepareStep");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ctx.status).isZero();
    assertThat(ctx.error).isFalse();
    assertThat(tracer.current()).isNull();
  }

  @Test
  @DisplayName(
      "T-AG-058: chunk step of 5,000 items with one SQL per item: recorded steps stay within"
          + " sampling.profile.max-steps and the rest is folded into a SUMMARY entry (summary mode),"
          + " recorded + summarized = 5,000")
  void chunkStepUsesSummaryMode() throws Exception {
    String status =
        BatchFixtures.runChunkStep("settlementJob", "settleStep", 5_000, 1_000, true, -1);

    assertThat(status).isEqualTo("COMPLETED");
    List<Transaction> batch = endedOfKind(Kind.BATCH);
    assertThat(batch).hasSize(1);
    Transaction tx = batch.get(0);
    assertThat(tx.context().serviceName).isEqualTo("BATCH settlementJob.settleStep");
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isLessThanOrEqualTo(steps.maxSteps());
    assertThat(steps.summarized()).isTrue();
    assertThat(tx.context().hasFlags(TraceContext.FLAG_SUMMARIZED)).isTrue();
    long recordedSql = stepsOf(tx, StepType.SQL).size();
    long summarizedSql = 0;
    int sqlKeys = 0;
    for (int key = 0; key < steps.summaryKeys(); key++) {
      if (steps.summaryType(key) == StepType.SQL) {
        summarizedSql += steps.summaryCount(key);
        sqlKeys++;
        assertThat(dictionary.text(steps.summaryNameId(key)))
            .isEqualTo(dictionary.text(steps.nameId(stepsOf(tx, StepType.SQL).get(0))));
      }
    }
    assertThat(sqlKeys).as("one summary key per (type, name)").isEqualTo(1);
    assertThat(recordedSql + summarizedSql).isEqualTo(5_000L);
    assertThat(summarizedSql).isGreaterThanOrEqualTo(4_000L);
  }

  @Test
  @DisplayName(
      "T-AG-058: a step that fails inside (AbstractStep records the failure, nothing is thrown)"
          + " ends its transaction as an error with the failure's class")
  void failedStepIsError() throws Exception {
    String status = BatchFixtures.runChunkStep("settlementJob", "settleStep", 20, 5, false, 7);

    assertThat(status).isEqualTo("FAILED");
    List<Transaction> batch = endedOfKind(Kind.BATCH);
    assertThat(batch).hasSize(1);
    assertThat(batch.get(0).context().error).isTrue();
    assertThat(batch.get(0).errorClass()).isEqualTo(IllegalArgumentException.class.getName());
    assertThat(batch.get(0).errorMessage()).isEqualTo("item 7 has no price");
  }

  @Test
  @DisplayName(
      "T-AG-058: a step executed inside an HTTP transaction (job run by a request) is a METHOD"
          + " step '<job>.<step>' with the item SQL in the same HTTP transaction")
  void insideHttpTransactionIsAStep() throws Exception {
    TraceContext http = beginHttp("POST /batch/run/{job}");
    BatchFixtures.runChunkStep("settlementJob", "settleStep", 30, 10, true, -1);
    Transaction tx = end(http);

    assertThat(endedOfKind(Kind.BATCH)).isEmpty();
    assertThat(stepNames(tx, StepType.METHOD)).containsExactly("settlementJob.settleStep");
    assertThat(stepsOf(tx, StepType.SQL)).hasSize(30);
    int method = stepsOf(tx, StepType.METHOD).get(0);
    assertThat(tx.steps().isClosed(method)).isTrue();
    assertThat(tx.steps().error(method)).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-058: a failing step inside an HTTP transaction marks its METHOD step as an error")
  void failingInsideHttpTransaction() throws Exception {
    TraceContext http = beginHttp("POST /batch/run/{job}");
    String status = BatchFixtures.runChunkStep("settlementJob", "settleStep", 20, 5, false, 3);
    Transaction tx = end(http);

    assertThat(status).isEqualTo("FAILED");
    int method = stepsOf(tx, StepType.METHOD).get(0);
    assertThat(tx.steps().error(method)).isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-058: a Spring Batch step run by a Quartz job is a METHOD step of the Quartz"
          + " transaction (one BATCH transaction in total)")
  void stepInsideQuartzJob() throws Exception {
    QuartzFixtures.fireOnce("batch-launching", "reports", "batchLauncher", false);

    List<Transaction> batch = awaitBatch(1);
    assertThat(batch).hasSize(1);
    assertThat(batch.get(0).context().serviceName).isEqualTo("BATCH reports.batchLauncher");
    assertThat(stepNames(batch.get(0), StepType.METHOD))
        .containsExactly("settlementJob.prepareStep");
  }
}
