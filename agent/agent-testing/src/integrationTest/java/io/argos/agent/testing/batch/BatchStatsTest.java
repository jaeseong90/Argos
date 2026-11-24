/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.batch.BatchRuns.Combo;
import io.argos.agent.testing.batch.BatchRuns.Profile;
import io.argos.agent.testing.batch.BatchRuns.Run;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-ACC-20, the agent side, and T-AG-036, the batch part (D-20, F-A24, F-A22; WO-18), on
 * shop-batch:
 *
 * <ul>
 *   <li>Kind separation of the full SQL statistics (10 §9.3 SqlStat by kind): the item query of
 *       settlementJob counts under {@code kind=BATCH} exactly as many executions as the BATCH
 *       transactions ran (detached runs) and under {@code kind=HTTP} as many as the HTTP
 *       transactions ran (a run inside a request); the order query of {@value #REQUESTS} HTTP
 *       requests counts under {@code kind=HTTP} only, exactly the executions of those requests.
 *       BATCH transactions carry no URL, method or status (they never enter the HTTP statistics,
 *       Apdex or TPS of the server, which group by kind).
 *   <li>SQL outside any transaction: Quartz's scheduler thread polls its JDBC job store (acquire
 *       next triggers, fire, complete) outside {@code Job.execute}; that SQL creates no
 *       transaction, no step and no statistics row, and raises {@code
 *       argos_agent_sql_outside_tx_total}.
 * </ul>
 *
 * The server half of T-ACC-20 (HTTP statistics count = HTTP requests, BATCH listed apart, on the
 * real server's API and screens) is {@code ui/e2e/specs/batch.spec.ts} 'T-ACC-20 (stack)'.
 */
@Tag("req:D-20")
@Tag("req:F-A24")
@ExtendWith(BatchRuns.class)
class BatchStatsTest {
  /** HTTP requests of the kind separation check. */
  static final int REQUESTS = 1_000;

  @TestFactory
  @DisplayName(
      "T-ACC-20 batch SQL in kind=BATCH statistics, HTTP SQL in kind=HTTP; T-AG-036 Quartz job"
          + " store polling outside a transaction is counted, never recorded")
  Stream<DynamicTest> batchStatistics() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : BatchRuns.combos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-ACC-20 "
                  + combo
                  + ": settlementJob SQL counts under kind=BATCH, 1,000 requests' SQL under"
                  + " kind=HTTP only",
              () -> kindSeparation(BatchRuns.run(combo, Profile.MAIN))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-036 "
                  + combo
                  + ": Quartz job store polling SQL raises sql_outside_tx_total, no transaction,"
                  + " step or statistics row",
              () -> quartzPolling(BatchRuns.run(combo, Profile.MAIN))));
    }
    return tests.stream();
  }

  /** True for the order-by-id query of {@code GET /api/orders/{id}} (spaces ignored). */
  static boolean isOrderQuery(String sql) {
    return sql != null
        && sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", "").contains("FROMORDERSWHEREID=?");
  }

  static void kindSeparation(Run run) throws Exception {
    // At least one detached run of settlementJob (BatchTransactionsTest may have made one).
    if (run.transactions(tx -> BatchRuns.SETTLE_STEP.equals(tx.service())).isEmpty()) {
      run.runDetached("settlementJob");
      run.await(tx -> BatchRuns.SETTLE_STEP.equals(tx.service()), 1, BatchRuns.WAIT);
    }
    Predicate<TransactionRecord> orders =
        tx -> tx.path() != null && tx.path().startsWith("/api/orders/");
    int before = run.transactions(orders).size();
    long begin = System.nanoTime();
    for (int i = 0; i < REQUESTS; i++) {
      HttpResult result = run.app.call("GET", "/api/orders/" + (1 + i % 10), java.util.Map.of());
      assertThat(result.status())
          .as("GET /api/orders/%d on %s", 1 + i % 10, run.name)
          .isEqualTo(200);
    }
    long loadMs = (System.nanoTime() - begin) / 1_000_000L;
    List<TransactionRecord> requests = run.await(orders, before + REQUESTS, Duration.ofMinutes(2));
    assertThat(requests)
        .as("HTTP transactions of the %d requests (D-04: one per request)", REQUESTS)
        .hasSize(before + REQUESTS);
    for (TransactionRecord tx : requests) {
      assertThat(tx.kind()).as("kind of %s", tx).isEqualTo("HTTP");
    }

    // The statistics are 10-second windows: wait until they caught up with the transactions.
    long[] expected = new long[4];
    long[] counted = new long[4];
    boolean equal =
        BatchRuns.await(
            () -> {
              List<TransactionRecord> all = run.collected().transactions();
              expected[0] = sum(all, "BATCH", BatchRuns::priceQueryExecutions);
              expected[1] = sum(all, "HTTP", BatchRuns::priceQueryExecutions);
              expected[2] = sum(all, "HTTP", BatchStatsTest::orderQueryExecutions);
              expected[3] = 0;
              List<Collected.StatWindow> windows = run.statWindows();
              counted[0] = statCount(windows, "BATCH", BatchRuns::isPriceQuery);
              counted[1] = statCount(windows, "HTTP", BatchRuns::isPriceQuery);
              counted[2] = statCount(windows, "HTTP", BatchStatsTest::isOrderQuery);
              counted[3] = statCount(windows, "BATCH", BatchStatsTest::isOrderQuery);
              return java.util.Arrays.equals(expected, counted);
            },
            Duration.ofSeconds(45),
            1_000);
    assertThat(counted)
        .as(
            "SqlStat counts [BATCH item query, HTTP item query, HTTP order query, BATCH order"
                + " query] = the executions of the transactions of that kind\n%s",
            run.describe())
        .containsExactly(expected);
    assertThat(equal).isTrue();
    assertThat(expected[0])
        .as("item queries of the detached runs (20,000 each)")
        .isPositive()
        .matches(n -> n % BatchRuns.CHUNK_ITEMS == 0, "a multiple of 20,000");
    assertThat(expected[2])
        .as("order queries of the HTTP transactions (at least one per request)")
        .isGreaterThanOrEqualTo(REQUESTS);

    Set<String> kinds = new TreeSet<>();
    for (Collected.StatWindow window : run.statWindows()) {
      window.sql().forEach(row -> kinds.add(row.kind()));
    }
    assertThat(kinds).as("kinds of the SqlStat rows").isSubsetOf("HTTP", "BATCH");
    for (TransactionRecord tx : run.transactions(tx -> "BATCH".equals(tx.kind()))) {
      assertThat(tx.path()).as("no URL on BATCH %s", tx.service()).isNull();
      assertThat(tx.httpMethod()).as("no HTTP method on BATCH %s", tx.service()).isNull();
      assertThat(tx.status()).as("status of BATCH %s", tx.service()).isZero();
      assertThat(tx.service()).as("service of a BATCH transaction").startsWith("BATCH ");
    }
    System.out.println(
        "[wo18-batch] T-ACC-20 "
            + run.name
            + ": "
            + REQUESTS
            + " requests in "
            + loadMs
            + " ms; SqlStat item query BATCH "
            + counted[0]
            + " / HTTP "
            + counted[1]
            + ", order query HTTP "
            + counted[2]
            + " / BATCH "
            + counted[3]
            + "; transactions HTTP "
            + run.transactions(tx -> "HTTP".equals(tx.kind())).size()
            + ", BATCH "
            + run.transactions(tx -> "BATCH".equals(tx.kind())).size());
  }

  static void quartzPolling(Run run) throws Exception {
    long outsideBefore = run.freshSelfDiagnostics().value("sql_outside_tx_total");
    // Firing a job wakes the scheduler thread: it acquires the trigger, fires it and, after the
    // job, completes it, each through the job store outside Job.execute.
    run.call("POST", "/batch/quartz/quartzCleanupJob", 200);
    long outsideAfter;
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      outsideAfter = run.freshSelfDiagnostics().value("sql_outside_tx_total");
    } while (outsideAfter <= outsideBefore && System.nanoTime() < deadline);
    assertThat(outsideAfter)
        .as("argos_agent_sql_outside_tx_total of %s across a Quartz firing", run.name)
        .isGreaterThan(outsideBefore);

    for (TransactionRecord tx : run.collected().transactions()) {
      assertThat(String.valueOf(tx.thread()))
          .as("a transaction of Quartz's scheduler thread: %s", tx)
          .doesNotContain("QuartzSchedulerThread");
      assertThat(tx.kind()).as("kind of %s", tx).isIn("HTTP", "BATCH");
      for (TransactionRecord.StepRecord step : tx.steps("SQL")) {
        assertThat(isAcquireQuery(step.name()))
            .as("the job store's acquire query recorded as a step of %s", tx.service())
            .isFalse();
      }
      for (TransactionRecord.SummaryRecord entry : tx.summary()) {
        assertThat(isAcquireQuery(entry.name()))
            .as("the job store's acquire query summarized in %s", tx.service())
            .isFalse();
      }
    }
    for (Collected.StatWindow window : run.statWindows()) {
      for (Collected.SqlRow row : window.sql()) {
        assertThat(isAcquireQuery(row.sql()))
            .as("the job store's acquire query in the SQL statistics (%s)", row.kind())
            .isFalse();
      }
    }
    System.out.println(
        "[wo18-batch] T-AG-036 "
            + run.name
            + ": sql_outside_tx_total "
            + outsideBefore
            + " -> "
            + outsideAfter
            + " across a Quartz firing; no transaction, step or statistics row of the job store"
            + " polling");
  }

  /** Quartz StdJDBCConstants SELECT_NEXT_TRIGGER_TO_ACQUIRE (scheduler thread only). */
  static boolean isAcquireQuery(String sql) {
    if (sql == null) {
      return false;
    }
    String text = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    return text.contains("QRTZ_TRIGGERS") && text.contains("NEXT_FIRE_TIME <=");
  }

  static long orderQueryExecutions(TransactionRecord tx) {
    return tx.steps("SQL").stream()
            .filter(s -> isOrderQuery(s.name()))
            .mapToLong(BatchRuns::executions)
            .sum()
        + tx.summary().stream()
            .filter(e -> "SQL".equals(e.type()) && isOrderQuery(e.name()))
            .mapToLong(e -> e.count() == null ? 0 : e.count())
            .sum();
  }

  private static long sum(
      List<TransactionRecord> all,
      String kind,
      java.util.function.ToLongFunction<TransactionRecord> executions) {
    return all.stream().filter(tx -> kind.equals(tx.kind())).mapToLong(executions).sum();
  }

  private static long statCount(
      List<Collected.StatWindow> windows, String kind, Predicate<String> statement) {
    return windows.stream()
        .flatMap(w -> w.sql().stream())
        .filter(row -> kind.equals(row.kind()) && statement.test(row.sql()))
        .mapToLong(Collected.SqlRow::count)
        .sum();
  }
}
