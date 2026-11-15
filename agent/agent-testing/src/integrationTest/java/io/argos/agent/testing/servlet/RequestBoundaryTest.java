/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * One request, one transaction (D-04, F-A28, 10 §7.1) on every shop jar app of {@code -Pagent.e2e}
 * (Spring Boot 2.7, 3.5, 4.1 on embedded Tomcat 9, 10.1, 11):
 *
 * <ul>
 *   <li>T-AG-010: {@code GET /api/orders/1} is exactly one transaction named {@code GET
 *       /api/orders/{id}} with status 200.
 *   <li>T-AG-011 / T-ACC-04 / T-ACC-21: the five dispatch kinds of the 18 §4.2 catalog (exception,
 *       404 without handler, {@code sendError(503)}, forward, async dispatch of a {@code
 *       DeferredResult}), 100 requests each, are exactly 100 transactions each and nothing else (no
 *       {@code /error} transaction or service, C-48), with the final status: an exception that
 *       escapes with a status below 400 is 500, the ERROR dispatch after {@code sendError(503)}
 *       keeps 503 (D-21). The self-diagnostics count the ERROR dispatches that Tomcat starts after
 *       the transaction ended in {@code argos_agent_transactions_unanchored_total}, exactly once
 *       per request ({@link #UNANCHORED}).
 *   <li>T-AG-011 over gRPC (the {@code CAPTURE} run, FakeCollector): {@code dispatch_count} is 1
 *       for one dispatch and 2 for a forward and for an async dispatch.
 * </ul>
 */
@Tag("req:F-A01")
@Tag("req:F-A02")
@Tag("req:F-A28")
@Tag("req:D-04")
@Tag("req:D-21")
@ExtendWith(ServletRuns.class)
class RequestBoundaryTest {
  /** One of the five dispatch kinds of T-AG-011. */
  record Dispatch(String path, int status, String service, boolean error, String errorClass) {}

  static final List<Dispatch> DISPATCHES =
      List.of(
          new Dispatch(
              "/api/error-500", 500, "GET /api/error-500", true, "java.lang.IllegalStateException"),
          new Dispatch("/api/not-found-x", 404, "GET /api/not-found-x", false, null),
          new Dispatch("/api/send-error", 503, "GET /api/send-error", true, null),
          new Dispatch("/api/forward", 200, "GET /api/forward", false, null),
          new Dispatch("/api/async/deferred", 200, "GET /api/async/deferred", false, null));

  /**
   * {@code dispatch_count} of each kind on embedded Tomcat: the ERROR dispatch of an exception or
   * {@code sendError} runs after the REQUEST chain ended (WO-02 D-8) and is not counted; a forward
   * and the async dispatch of a {@code DeferredResult} are.
   */
  static final Map<String, Integer> DISPATCH_COUNTS =
      Map.of(
          "/api/error-500", 1,
          "/api/not-found-x", 1,
          "/api/send-error", 1,
          "/api/forward", 2,
          "/api/async/deferred", 2);

  /** Requests of each dispatch kind (T-ACC-04: 100). */
  static final int REPEAT = 100;

  /**
   * {@code argos_agent_transactions_unanchored_total} added by the {@value #REPEAT} requests of
   * each kind on embedded Tomcat (10 §7.1): the ERROR dispatch that Tomcat starts for an exception,
   * a 404 without handler or {@code sendError} runs after the transaction ended, outside the
   * REQUEST chain (WO-02 D-8): once per request; a forward and the async dispatch of a {@code
   * DeferredResult} join the running transaction: none.
   */
  static final Map<String, Integer> UNANCHORED =
      Map.of(
          "/api/error-500", REPEAT,
          "/api/not-found-x", REPEAT,
          "/api/send-error", REPEAT,
          "/api/forward", 0,
          "/api/async/deferred", 0);

  @TestFactory
  @DisplayName(
      "T-AG-010/T-AG-011/T-ACC-04/T-ACC-21 request boundary and dispatch kinds (shop jar apps)")
  Stream<DynamicTest> requestBoundary() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-010 "
                  + combo
                  + ": GET /api/orders/1 is one transaction named GET /api/orders/{id}, status 200",
              () -> oneRequestOneTransaction(ServletRuns.run(combo, Profile.DEFAULTS))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-011/T-ACC-04/T-ACC-21 "
                  + combo
                  + ": exception, 404, sendError, forward and async dispatch, "
                  + REPEAT
                  + " requests each, are exactly "
                  + REPEAT
                  + " transactions each with the final status; no /error transaction; the ERROR"
                  + " dispatches outside the chain counted as unanchored",
              () -> dispatchKinds(ServletRuns.run(combo, Profile.DEFAULTS), UNANCHORED)));
    }
    // Profile-major order: the DEFAULTS runs of every combination first, then CAPTURE (fewer
    // restarts of the shared runs, ServletRuns.MAX_ALIVE).
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-011 "
                  + combo
                  + " over gRPC: dispatch_count 1 for one dispatch, 2 for a forward and an async"
                  + " dispatch",
              () -> dispatchCount(ServletRuns.run(combo, Profile.CAPTURE))));
    }
    return tests.stream();
  }

  static void oneRequestOneTransaction(Run run) throws Exception {
    int before = run.settle();
    assertThat(run.get("/api/orders/1").status()).isEqualTo(200);
    List<TransactionRecord> added = run.newTransactions(tx -> true, before, 1);
    assertThat(added).as("transactions of GET /api/orders/1%n%s", run.describe()).hasSize(1);
    TransactionRecord order = added.get(0);
    assertThat(order.path()).isEqualTo("/api/orders/1");
    assertThat(order.service()).isEqualTo("GET /api/orders/{id}");
    assertThat(order.status()).isEqualTo(200);
    assertThat(order.error()).isFalse();
    assertThat(order.kind()).isEqualTo("HTTP");
    assertThat(order.httpMethod()).isEqualTo("GET");
    System.out.println("[wo12] T-AG-010 " + run.combo + ": " + order.service());
  }

  /**
   * @param unanchored {@code argos_agent_transactions_unanchored_total} each kind adds (by path)
   */
  static void dispatchKinds(Run run, Map<String, Integer> unanchored) throws Exception {
    List<String> table = new ArrayList<>();
    run.settle();
    long unanchoredBefore = run.counterNow(ServletRuns.UNANCHORED_COUNTER);
    for (Dispatch dispatch : DISPATCHES) {
      int totalBefore = run.settle();
      int ordersBefore = run.transactions(path("/api/orders/1")).size();
      int before = run.transactions(path(dispatch.path())).size();
      run.repeat("GET", dispatch.path(), Map.of(), REPEAT, dispatch.status());
      List<TransactionRecord> added = run.newTransactions(path(dispatch.path()), before, REPEAT);
      long unanchoredAfter = run.counterNow(ServletRuns.UNANCHORED_COUNTER);
      assertThat(added)
          .as("D-04: transactions of %d x GET %s", REPEAT, dispatch.path())
          .hasSize(REPEAT);
      assertThat(run.transactions().size() - totalBefore)
          .as("no other transaction (no /error, no forward target) for GET %s", dispatch.path())
          .isEqualTo(REPEAT);
      assertThat(run.transactions(path("/api/orders/1")))
          .as("the forward target is part of the forwarding transaction")
          .hasSize(ordersBefore);
      assertThat(unanchoredAfter - unanchoredBefore)
          .as(
              "%s added by %d x GET %s (10 §7.1)",
              ServletRuns.UNANCHORED_COUNTER, REPEAT, dispatch.path())
          .isEqualTo(unanchored.get(dispatch.path()).longValue());
      unanchoredBefore = unanchoredAfter;
      for (TransactionRecord tx : added) {
        assertThat(tx.status())
            .as("D-21 final status of %s: %s", dispatch.path(), tx)
            .isEqualTo(dispatch.status());
        assertThat(tx.service()).as(tx.toString()).isEqualTo(dispatch.service());
        assertThat(tx.error()).as(tx.toString()).isEqualTo(dispatch.error());
        if (dispatch.errorClass() != null) {
          assertThat(tx.errorClass()).as(tx.toString()).isEqualTo(dispatch.errorClass());
        }
        assertThat(tx.dispatchCount())
            .as("dispatch_count (F-A28): %s", tx)
            .isEqualTo(DISPATCH_COUNTS.get(dispatch.path()));
      }
      table.add(
          dispatch.path()
              + " "
              + added.size()
              + "x"
              + dispatch.status()
              + " unanchored+"
              + unanchored.get(dispatch.path()));
    }
    assertThat(run.transactions())
        .as("C-48: no transaction is named after or recorded for /error")
        .noneMatch(ServletRuns::isErrorTransaction);
    System.out.println("[wo12] T-ACC-04 " + run.combo + ": " + table);
  }

  static void dispatchCount(Run run) throws Exception {
    assertThat(run.collector).isNotNull();
    for (String[] expected :
        new String[][] {
          {"/api/orders/1", "1"}, {"/api/forward", "2"}, {"/api/async/deferred", "2"}
        }) {
      int before = run.settle();
      int pathBefore = run.transactions(path(expected[0])).size();
      assertThat(run.get(expected[0]).status()).isEqualTo(200);
      List<TransactionRecord> added = run.newTransactions(path(expected[0]), pathBefore, 1);
      assertThat(added).as("transactions of GET %s%n%s", expected[0], run.describe()).hasSize(1);
      assertThat(run.transactions().size() - before)
          .as("one transaction for %s", expected[0])
          .isEqualTo(1);
      TransactionRecord tx = added.get(0);
      assertThat(tx.field("dispatch_count"))
          .as("dispatch_count of GET %s on the wire: %s", expected[0], tx)
          .isEqualTo(Long.parseLong(expected[1]));
      assertThat(tx.dispatchCount()).isEqualTo(Integer.parseInt(expected[1]));
    }
    assertThat(run.transactions()).noneMatch(ServletRuns::isErrorTransaction);
  }
}
