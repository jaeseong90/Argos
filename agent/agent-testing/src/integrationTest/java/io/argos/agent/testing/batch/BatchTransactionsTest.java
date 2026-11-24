/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.batch.BatchRuns.Combo;
import io.argos.agent.testing.batch.BatchRuns.Profile;
import io.argos.agent.testing.batch.BatchRuns.Run;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-058 (F-A24, D-20, 10 §6.8; WO-18): the non-HTTP entry points of shop-batch with the release
 * agent jar are BATCH transactions.
 *
 * <ul>
 *   <li>Each run of the three {@code @Scheduled} kinds (fixed rate, cron, long running) is exactly
 *       one BATCH transaction named {@code BATCH
 *       io.argos.sample.batch.scheduled.ScheduledJobs#<method>} (the user class, no CGLIB suffix):
 *       between the app's execution counters read before and after.
 *   <li>Both Quartz jobs, fired now, are BATCH transactions {@code BATCH shop-batch.<job>}, one per
 *       execution (QuartzJobBean delegating to the job records once), with the report job's
 *       outgoing call as an HTTP step.
 *   <li>A Spring Batch job run on a plain app thread ({@code ?detached=true}, not propagated, 10
 *       §8) gives one BATCH transaction per step, {@code BATCH settlementJob.prepareStep} and
 *       {@code BATCH settlementJob.settleStep}. With {@code SAMPLE_BATCH_PER_ITEM_SQL=true} the
 *       chunk step's 20,000 item queries are summarized: at most {@code max-steps} recorded steps,
 *       and the SUMMARY entry of the item query counts exactly the executions not recorded (10 U-19
 *       result line {@code [wo18-u19]}).
 *   <li>The same job run inside an HTTP request ({@code POST /batch/run/settlementJob}) adds no
 *       BATCH transaction: its steps are METHOD steps of the request's transaction (10 §6.8).
 *   <li>On virtual threads ({@code SAMPLE_VT=true}, JDK 21+) the {@code @Scheduled} transactions
 *       carry the virtual-thread flag; the detached job's plain platform thread does not.
 * </ul>
 */
@Tag("req:F-A24")
@Tag("req:D-20")
@ExtendWith(BatchRuns.class)
class BatchTransactionsTest {
  private static final Pattern EXECUTIONS = Pattern.compile("\"executions\"\\s*:\\s*(\\d+)");

  @TestFactory
  @DisplayName("T-AG-058 @Scheduled, Quartz and Spring Batch steps are BATCH transactions")
  Stream<DynamicTest> batchTransactions() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : BatchRuns.combos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-058 "
                  + combo
                  + ": each run of the three @Scheduled kinds is one BATCH transaction"
                  + " 'BATCH ScheduledJobs#<method>'",
              () -> scheduled(BatchRuns.run(combo, Profile.MAIN))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-058 " + combo + ": both Quartz jobs are BATCH 'BATCH shop-batch.<job>'",
              () -> quartz(BatchRuns.run(combo, Profile.MAIN))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-058 "
                  + combo
                  + ": a detached Spring Batch job gives BATCH settlementJob.prepareStep and"
                  + " .settleStep; 20,000 item queries summarized within max-steps (U-19)",
              () -> detached(BatchRuns.run(combo, Profile.MAIN))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-058 "
                  + combo
                  + ": the job run inside an HTTP request adds no BATCH transaction, only METHOD"
                  + " steps",
              () -> insideHttp(BatchRuns.run(combo, Profile.MAIN))));
    }
    for (Combo combo : BatchRuns.virtualThreadCombo()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-058 "
                  + combo
                  + " SAMPLE_VT=true: @Scheduled BATCH transactions on virtual threads, the"
                  + " detached job's platform thread not propagated",
              () -> virtualThreads(BatchRuns.run(combo, Profile.VT))));
    }
    return tests.stream();
  }

  static void scheduled(Run run) throws Exception {
    // Wait until the long-running method has completed once (3 s, first after 1 s).
    String counters = "";
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < deadline) {
      counters = run.call("GET", "/batch/scheduled?awaitMs=20000", 200).body();
      if (counter(counters, "longRunningCompleted") >= 1
          && counter(counters, "fixedRate") >= 1
          && counter(counters, "cron") >= 1) {
        break;
      }
      Thread.sleep(500);
    }
    long[] before = {
      counter(counters, "fixedRate"),
      counter(counters, "cron"),
      counter(counters, "longRunningCompleted")
    };
    for (long count : before) {
      assertThat(count).as("@Scheduled counters of %s: %s", run.name, counters).isPositive();
    }
    for (int i = 0; i < 3; i++) {
      String service = BatchRuns.SCHEDULED_SERVICES.get(i);
      List<TransactionRecord> txs =
          run.await(tx -> service.equals(tx.service()), (int) before[i], BatchRuns.WAIT);
      String after = run.call("GET", "/batch/scheduled", 200).body();
      long upper =
          i == 0
              ? counter(after, "fixedRate")
              : i == 1 ? counter(after, "cron") : counter(after, "longRunningStarted");
      assertThat((long) txs.size())
          .as(
              "BATCH transactions '%s' of %s: one per execution (counters %s then %s)\n%s",
              service, run.name, counters, after, run.describe())
          .isBetween(before[i], upper);
      for (TransactionRecord tx : txs) {
        expectBatch(tx, service);
        assertThat(BatchRuns.number(tx, "sql_count")).as("SQL of %s", tx).isGreaterThanOrEqualTo(1);
        if (run.profile == Profile.MAIN) {
          assertThat(tx.thread()).as("thread of %s", tx).isEqualTo("batch-scheduling-");
        }
      }
      if (i == 2) {
        TransactionRecord longRun = txs.get(0);
        assertThat(longRun.elapsedMs())
            .as("the long-running method's transaction covers its 3 s: %s", longRun)
            .isGreaterThanOrEqualTo(2_500.0);
      }
    }
    System.out.println(
        "[wo18-batch] T-AG-058 "
            + run.name
            + ": @Scheduled counters "
            + counters.replaceAll("\\s+", " ")
            + " match their BATCH transactions");
  }

  static void quartz(Run run) throws Exception {
    for (String job : BatchRuns.QUARTZ_JOBS) {
      String service = "BATCH " + BatchRuns.QUARTZ_GROUP + "." + job;
      HttpResult fired = run.call("POST", "/batch/quartz/" + job, 200);
      assertThat(fired.body()).as("Quartz %s on %s", job, run.name).contains("\"completed\":true");
      Matcher m = EXECUTIONS.matcher(fired.body());
      assertThat(m.find()).as("executions in %s", fired.body()).isTrue();
      long executions = Long.parseLong(m.group(1));
      List<TransactionRecord> txs =
          run.await(tx -> service.equals(tx.service()), (int) executions, BatchRuns.WAIT);
      // A cron firing between the answer and now adds at most one execution (and transaction).
      assertThat((long) txs.size())
          .as(
              "BATCH transactions '%s' of %s: one per execution (%d executed)\n%s",
              service, run.name, executions, run.describe())
          .isBetween(executions, executions + 1);
      for (TransactionRecord tx : txs) {
        expectBatch(tx, service);
      }
      TransactionRecord last = txs.get(txs.size() - 1);
      assertThat(BatchRuns.number(last, "sql_count"))
          .as("SQL of %s", last)
          .isGreaterThanOrEqualTo(1);
      if (job.equals("quartzReportJob")) {
        assertThat(last.steps("HTTP"))
            .as("the report job's call to ext-stub inside its BATCH transaction: %s", last)
            .isNotEmpty();
      }
    }
    assertThat(
            run.transactions(tx -> String.valueOf(tx.thread()).contains("QuartzSchedulerThread")))
        .as("transactions of Quartz's scheduler thread (job store polling is not a job)")
        .isEmpty();
  }

  static void detached(Run run) throws Exception {
    int prepareBefore = run.transactions(tx -> BatchRuns.PREPARE_STEP.equals(tx.service())).size();
    int settleBefore = run.transactions(tx -> BatchRuns.SETTLE_STEP.equals(tx.service())).size();
    long begin = System.nanoTime();
    long runId = run.runDetached("settlementJob");
    long runMs = (System.nanoTime() - begin) / 1_000_000L;
    List<TransactionRecord> prepare =
        run.await(
            tx -> BatchRuns.PREPARE_STEP.equals(tx.service()), prepareBefore + 1, BatchRuns.WAIT);
    List<TransactionRecord> settle =
        run.await(
            tx -> BatchRuns.SETTLE_STEP.equals(tx.service()), settleBefore + 1, BatchRuns.WAIT);
    assertThat(prepare)
        .as("BATCH settlementJob.prepareStep of run %d\n%s", runId, run.describe())
        .hasSize(prepareBefore + 1);
    assertThat(settle)
        .as("BATCH settlementJob.settleStep of run %d\n%s", runId, run.describe())
        .hasSize(settleBefore + 1);
    TransactionRecord prepareTx = prepare.get(prepare.size() - 1);
    TransactionRecord settleTx = settle.get(settle.size() - 1);
    for (TransactionRecord tx : List.of(prepareTx, settleTx)) {
      expectBatch(tx, tx.service());
      assertThat(tx.thread())
          .as("the app's detached thread of %s", tx)
          .isEqualTo(BatchRuns.DETACHED_THREAD);
    }
    U19 u19 = expectSummarized(run, settleTx);
    System.out.println(
        "[wo18-u19] "
            + run.name
            + ": settleStep of 20,000 items with per-item SQL in "
            + runMs
            + " ms (detached run), tx "
            + Math.round(settleTx.elapsedMs())
            + " ms, total_steps "
            + settleTx.totalSteps()
            + ", recorded steps "
            + settleTx.steps().size()
            + " (slots "
            + u19.slots
            + ", max-steps "
            + BatchRuns.MAX_STEPS
            + "), summary keys "
            + settleTx.summary().size()
            + ", item queries recorded "
            + u19.recorded
            + " + summarized "
            + u19.summarized
            + ", sql_count "
            + BatchRuns.number(settleTx, "sql_count")
            + ", profile bytes "
            + run.profileBytes(settleTx));
  }

  /** Recorded and summarized item queries and step slots of a summarized settleStep. */
  record U19(long recorded, long summarized, long slots) {}

  static U19 expectSummarized(Run run, TransactionRecord tx) {
    assertThat(tx.profileCaptured()).as("profile of %s (normal-rate 1)", tx).isTrue();
    long slots = tx.steps().stream().mapToLong(BatchRuns::executions).sum();
    assertThat(tx.steps().size())
        .as("recorded steps of %s", tx.service())
        .isLessThanOrEqualTo(BatchRuns.MAX_STEPS);
    assertThat(slots)
        .as("recorded step slots (repeat counts included) of %s", tx.service())
        .isLessThanOrEqualTo(BatchRuns.MAX_STEPS);
    assertThat(tx.truncated()).as("summary mode of %s", tx.service()).isTrue();
    assertThat(tx.flags()).as("C-69 summarized flag of %s", tx.service()).contains("summarized");
    assertThat(tx.totalSteps())
        .as("every started step counted (total_steps) of %s", tx.service())
        .isGreaterThanOrEqualTo((long) BatchRuns.CHUNK_ITEMS);
    assertThat(BatchRuns.number(tx, "sql_count"))
        .as("sql_count of %s", tx.service())
        .isGreaterThanOrEqualTo(BatchRuns.CHUNK_ITEMS);
    long recorded = BatchRuns.recordedPriceQueries(tx);
    long summarized = BatchRuns.summarizedPriceQueries(tx);
    assertThat(tx.summary())
        .as("SUMMARY entries of %s\n%s", tx.service(), run.describe())
        .anySatisfy(e -> assertThat(BatchRuns.isPriceQuery(e.name())).isTrue());
    assertThat(summarized)
        .as(
            "SUMMARY count of the item query = executed (%d) - recorded (%d) in %s",
            BatchRuns.CHUNK_ITEMS, recorded, tx.service())
        .isEqualTo(BatchRuns.CHUNK_ITEMS - recorded);
    assertThat(tx.summary().size()).as("summary keys (10 §9.1 cap)").isLessThanOrEqualTo(256);
    return new U19(recorded, summarized, slots);
  }

  static void insideHttp(Run run) throws Exception {
    int batchBefore =
        run.transactions(tx -> String.valueOf(tx.service()).startsWith("BATCH settlementJob."))
            .size();
    String path = "/batch/run/settlementJob";
    int httpBefore = run.transactions(tx -> path.equals(tx.path())).size();
    run.call("POST", path, 200);
    List<TransactionRecord> http =
        run.await(tx -> path.equals(tx.path()), httpBefore + 1, BatchRuns.WAIT);
    assertThat(http)
        .as("HTTP transactions of POST %s\n%s", path, run.describe())
        .hasSize(httpBefore + 1);
    TransactionRecord tx = http.get(http.size() - 1);
    assertThat(tx.kind()).as("kind of %s", tx).isEqualTo("HTTP");
    List<String> methods =
        tx.steps("METHOD").stream().map(TransactionRecord.StepRecord::name).toList();
    assertThat(methods)
        .as("METHOD steps of the request (10 §6.8: a job inside a transaction is a step)")
        .contains("settlementJob.prepareStep", "settlementJob.settleStep");
    assertThat(BatchRuns.priceQueryExecutions(tx))
        .as("item queries of the job inside the request transaction (recorded + summarized)")
        .isEqualTo(BatchRuns.CHUNK_ITEMS);
    Thread.sleep(3_000);
    assertThat(
            run.transactions(t -> String.valueOf(t.service()).startsWith("BATCH settlementJob."))
                .size())
        .as("BATCH transactions of settlementJob after the run inside a request")
        .isEqualTo(batchBefore);
    System.out.println(
        "[wo18-batch] T-AG-058 "
            + run.name
            + ": POST "
            + path
            + " = HTTP transaction with METHOD steps "
            + methods.stream().filter(n -> n.startsWith("settlementJob.")).toList()
            + ", no new BATCH transaction");
  }

  static void virtualThreads(Run run) throws Exception {
    String counters = run.call("GET", "/batch/scheduled?awaitMs=20000", 200).body();
    assertThat(counters).as("@Scheduled on virtual threads").contains("\"lastRunVirtual\":true");
    for (String service : BatchRuns.SCHEDULED_SERVICES) {
      List<TransactionRecord> txs =
          run.await(tx -> service.equals(tx.service()), 1, BatchRuns.WAIT);
      assertThat(txs).as("BATCH '%s' on %s\n%s", service, run.name, run.describe()).isNotEmpty();
      for (TransactionRecord tx : txs) {
        expectBatch(tx, service);
        assertThat(tx.virtualThread()).as("virtual thread of %s", tx).isTrue();
        assertThat(tx.flags()).as("flags of %s", tx).contains("virtual_thread");
      }
    }
    int settleBefore = run.transactions(tx -> BatchRuns.SETTLE_STEP.equals(tx.service())).size();
    run.runDetached("settlementJob");
    List<TransactionRecord> settle =
        run.await(
            tx -> BatchRuns.SETTLE_STEP.equals(tx.service()), settleBefore + 1, BatchRuns.WAIT);
    assertThat(settle).hasSize(settleBefore + 1);
    TransactionRecord tx = settle.get(settle.size() - 1);
    expectBatch(tx, BatchRuns.SETTLE_STEP);
    assertThat(tx.virtualThread()).as("the app's plain platform thread: %s", tx).isFalse();
    assertThat(tx.thread()).isEqualTo(BatchRuns.DETACHED_THREAD);
    expectSummarized(run, tx);
    System.out.println(
        "[wo18-batch] T-AG-058 "
            + run.name
            + ": @Scheduled BATCH on virtual threads, detached job on its platform thread");
  }

  /** The common fields of a BATCH transaction (10 §6.8, 11 http_status 0 for BATCH). */
  static void expectBatch(TransactionRecord tx, String service) {
    assertThat(tx.kind()).as("kind of %s", tx).isEqualTo("BATCH");
    assertThat(tx.service()).as("service of %s", tx).isEqualTo(service);
    assertThat(tx.status()).as("status of %s", tx).isZero();
    assertThat(tx.path()).as("no URL path on %s", tx).isNull();
    assertThat(tx.httpMethod()).as("no HTTP method on %s", tx).isNull();
    assertThat(tx.error()).as("error of %s", tx).isFalse();
  }

  static long counter(String json, String name) {
    Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }
}
