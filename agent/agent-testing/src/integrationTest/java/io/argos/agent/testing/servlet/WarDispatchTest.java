/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.inContext;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.servlet.RequestBoundaryTest.Dispatch;
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
 * T-AG-011 in a standalone Tomcat (D-04, D-21, F-A28, C-48): the five dispatch kinds of the 18 §4.2
 * catalog in the first web application of each WAR app, {@value #REPEAT} requests each, are exactly
 * that many transactions with the final status and the context prefix; Spring Boot's {@code
 * ErrorPageFilter} (WAR deployments) forwards errors to {@code /error}, which never becomes a
 * transaction or a service name, and the self-diagnostics count no unanchored dispatch ({@link
 * #UNANCHORED}). A forward and an include whose servlet hands the request on to a page servlet (the
 * JSP shape, {@link #handedOn}) count one dispatch each. Then the run stops and Tomcat's log is
 * judged ({@link WarRuns#judgeTomcatLog}).
 */
@Tag("req:F-A28")
@Tag("req:D-04")
@Tag("req:D-21")
@ExtendWith(ServletRuns.class)
class WarDispatchTest {
  static final int REPEAT = 10;

  /**
   * {@code argos_agent_transactions_unanchored_total} added by the {@value #REPEAT} requests of
   * each kind, per WAR app (measured on Tomcat 9 with shop-war-javax and Tomcat 10.1 with
   * shop-war-jakarta): Spring Boot's {@code ErrorPageFilter} handles the exception, the 404 and
   * {@code sendError} inside the REQUEST chain with a forward to {@code /error}, which joins the
   * running transaction, so Tomcat starts no ERROR dispatch and nothing is unanchored (unlike
   * embedded Tomcat, {@link RequestBoundaryTest#UNANCHORED}).
   */
  static final Map<String, Map<String, Integer>> UNANCHORED =
      Map.of(
          "shop-war-javax",
          Map.of(
              "/api/error-500", 0,
              "/api/not-found-x", 0,
              "/api/send-error", 0,
              "/api/forward", 0,
              "/api/async/deferred", 0),
          "shop-war-jakarta",
          Map.of(
              "/api/error-500", 0,
              "/api/not-found-x", 0,
              "/api/send-error", 0,
              "/api/forward", 0,
              "/api/async/deferred", 0));

  @TestFactory
  @DisplayName("T-AG-011 dispatch kinds in a standalone Tomcat (WAR apps)")
  Stream<DynamicTest> warDispatches() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.warCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-011 "
                  + combo
                  + ": exception, 404, sendError, forward and async dispatch in a WAR are one"
                  + " transaction each with the final status; no /error transaction; nothing"
                  + " unanchored (ErrorPageFilter)",
              () -> dispatches(ServletRuns.run(combo, Profile.WAR))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-011 "
                  + combo
                  + ": a forward and an include whose servlet hands the request on to a page"
                  + " servlet (the shape of Jasper's JspServlet; fixture /forward-page,"
                  + " /page/including) count one dispatch each: dispatch_count 2",
              () -> handedOn(ServletRuns.run(combo, Profile.WAR))));
      tests.add(WarRuns.tomcatLog("T-AG-011", combo));
    }
    return tests.stream();
  }

  static void dispatches(Run run) throws Exception {
    String context = run.app.app().contextPaths().get(0);
    Map<String, Integer> unanchored = UNANCHORED.get(run.combo.app());
    assertThat(unanchored).as("measured unanchored counts of %s", run.combo.app()).isNotNull();
    List<String> table = new ArrayList<>();
    run.settle();
    long unanchoredBefore = run.counterNow(ServletRuns.UNANCHORED_COUNTER);
    for (Dispatch dispatch : RequestBoundaryTest.DISPATCHES) {
      int totalBefore = run.settle();
      int before = run.transactions(inContext(context, dispatch.path())).size();
      run.repeat("GET", context + dispatch.path(), Map.of(), REPEAT, dispatch.status());
      List<TransactionRecord> added =
          run.newTransactions(inContext(context, dispatch.path()), before, REPEAT);
      long unanchoredAfter = run.counterNow(ServletRuns.UNANCHORED_COUNTER);
      assertThat(added).as("%s%s%n%s", context, dispatch.path(), run.describe()).hasSize(REPEAT);
      assertThat(run.transactions().size() - totalBefore)
          .as("no other transaction for %s%s", context, dispatch.path())
          .isEqualTo(REPEAT);
      assertThat(unanchoredAfter - unanchoredBefore)
          .as(
              "%s added by %d x GET %s%s in %s",
              ServletRuns.UNANCHORED_COUNTER, REPEAT, context, dispatch.path(), run.combo)
          .isEqualTo(unanchored.get(dispatch.path()).longValue());
      unanchoredBefore = unanchoredAfter;
      for (TransactionRecord tx : added) {
        assertThat(tx.status()).as("D-21: %s", tx).isEqualTo(dispatch.status());
        assertThat(tx.service()).as(tx.toString()).isEqualTo(context + " " + dispatch.service());
        assertThat(tx.error()).as(tx.toString()).isEqualTo(dispatch.error());
      }
      table.add(dispatch.path() + " " + added.size() + "x" + dispatch.status());
    }
    assertThat(run.transactions()).noneMatch(ServletRuns::isErrorTransaction);
    System.out.println("[wo12] T-AG-011 WAR " + run.combo + " " + context + ": " + table);
  }

  /**
   * F-A28 {@code dispatch_count} when the servlet of a dispatch hands the same request on to a page
   * servlet through {@code Servlet.service(ServletRequest, ServletResponse)}, as Jasper's {@code
   * JspServlet} runs a JSP page (the fixture's {@code HandOffServlet}: the staged Tomcat has no JSP
   * compiler): the REQUEST plus one FORWARD ({@code /forward-page}), and the REQUEST plus one
   * INCLUDE ({@code /page/including}, the {@code <jsp:include>} shape), are 2 each, not 3.
   */
  static void handedOn(Run run) throws Exception {
    List<String> table = new ArrayList<>();
    for (String[] expected :
        List.of(
            new String[] {"/forward-page", "GET /forward-page"},
            new String[] {"/page/including", "GET /page/*"})) {
      TransactionRecord tx =
          WarRuns.single(run, "GET", ServletRuns.FIXTURE_CONTEXT, expected[0], Map.of(), 200);
      assertThat(tx.dispatchCount())
          .as("F-A28 dispatch_count of %s: %s", expected[0], tx)
          .isEqualTo(2);
      assertThat(tx.service())
          .as(tx.toString())
          .isEqualTo(ServletRuns.FIXTURE_CONTEXT + " " + expected[1]);
      assertThat(tx.error()).as(tx.toString()).isFalse();
      table.add(expected[0] + " dispatch_count=" + tx.dispatchCount());
    }
    assertThat(run.transactions()).noneMatch(ServletRuns::isErrorTransaction);
    System.out.println("[wo12] T-AG-011 WAR " + run.combo + " hand-off: " + table);
  }
}
