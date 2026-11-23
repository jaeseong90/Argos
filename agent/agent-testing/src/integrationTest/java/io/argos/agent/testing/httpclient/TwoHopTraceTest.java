/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.httpclient.HttpClientRuns.Combo;
import io.argos.agent.testing.httpclient.HttpClientRuns.Run;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-ACC-05, agent side (F-A06, 10 §8): two shop-boot3 processes, A ({@code app.name}
 * shop-boot3-hop-a) calling B (shop-boot3-hop-b) through SAMPLE_HOP_URL with {@code
 * propagation.argos-state.hosts=127.0.0.1} and {@code sampling.profile.normal-rate=1.0}, each with
 * its own FakeCollector (which answers a different {@code app_id} per app name,
 * V1Protocol#appIdOf). 100 calls of GET /api/hop/{id}?depth=1 on A give, for every call, B's
 * transaction in A's trace: same trace id, B {@code incoming_trace} with a parent span, B {@code
 * caller_app_id} = A's app id, and A's HTTP step lasting at least as long as B's transaction
 * (caller ≥ callee). Ten calls with depth 2 (A → B → B itself) give three transactions per trace
 * whose callers are A and B. The server side of T-ACC-05 (GET /traces/{id} returns both) is the UI
 * E2E stack (T-E2E-trace-12).
 */
@Tag("req:F-A06")
@Tag("req:F-A32")
@ExtendWith(HttpClientRuns.class)
class TwoHopTraceTest {

  static final int CALLS = 100;

  static final int DEEP_CALLS = 10;

  @TestFactory
  @DisplayName("T-ACC-05 2-hop shop-boot3 -> shop-boot3, caller HTTP step >= callee transaction")
  Stream<DynamicTest> twoHop() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : HttpClientRuns.combosOf("shop-boot3")) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-ACC-05 "
                  + combo
                  + ": "
                  + CALLS
                  + " calls A -> B, same trace, B incoming_trace and caller_app_id = A, A's HTTP"
                  + " step >= B's transaction; "
                  + DEEP_CALLS
                  + " 3-hop calls",
              () -> run(combo)));
    }
    return tests.stream();
  }

  static void run(Combo combo) throws Exception {
    Path root = HttpClientRuns.newWorkRoot("wo14-hop-");
    String nameA = "shop-boot3-hop-a";
    String nameB = "shop-boot3-hop-b";
    int appIdA = V1Protocol.appIdOf(nameA);
    int appIdB = V1Protocol.appIdOf(nameB);
    assertThat(appIdA).as("distinct app ids of the two apps").isNotEqualTo(appIdB);
    try (Run b = start(nameB, combo, Map.of(), root);
        Run a = start(nameA, combo, Map.of("SAMPLE_HOP_URL", b.app.baseUrl()), root)) {
      for (int i = 0; i < CALLS; i++) {
        long id = HttpClientRuns.nextId();
        HttpResult result = a.app.get("/api/hop/" + id + "?depth=1&client=hurl");
        assertThat(result.status()).as("call %s: %s", i, result.body()).isEqualTo(200);
      }
      Predicate<TransactionRecord> hop =
          tx -> tx.path() != null && tx.path().startsWith("/api/hop/");
      List<TransactionRecord> callers =
          a.collected().awaitTransactions(hop, CALLS, HttpClientRuns.WAIT);
      List<TransactionRecord> callees =
          b.collected().awaitTransactions(hop, CALLS, HttpClientRuns.WAIT);
      assertThat(callers).as("A transactions").hasSize(CALLS);
      assertThat(callees).as("B transactions").hasSize(CALLS);
      Map<String, TransactionRecord> calleeByTrace = new HashMap<>();
      for (TransactionRecord callee : callees) {
        assertThat(calleeByTrace.put(callee.traceId(), callee))
            .as("one B transaction per trace")
            .isNull();
      }
      double minMarginMs = Double.MAX_VALUE;
      double maxMarginMs = 0;
      int holds = 0;
      List<String> violations = new ArrayList<>();
      for (TransactionRecord caller : callers) {
        TransactionRecord callee = calleeByTrace.get(caller.traceId());
        assertThat(callee).as("B transaction in the trace of %s", caller).isNotNull();
        assertThat(callee.path()).isEqualTo(caller.path());
        assertThat(caller.field("incoming_trace")).isEqualTo(false);
        assertThat(callee.field("incoming_trace")).as("B incoming_trace").isEqualTo(true);
        assertThat(callee.field("parent_span_id"))
            .as("B parent span = A's span (the traceparent parent id is the caller's span)")
            .isNotNull()
            .isEqualTo(caller.field("span_id"));
        assertThat(callee.field("caller_app_id"))
            .as("B caller_app_id = A's app id (tracestate argos entry)")
            .isEqualTo((long) appIdA);
        List<StepRecord> steps = caller.steps("HTTP");
        assertThat(steps).as("A's HTTP steps: %s", caller).hasSize(1);
        StepRecord step = steps.get(0);
        assertThat(step.field("failure")).isEqualTo("FAILURE_NONE");
        assertThat(step.name()).isEqualTo(Run.hostText(b.app.port()));
        double margin = step.elapsedMs() - callee.elapsedMs();
        if (margin >= 0) {
          holds++;
        } else if (violations.size() < 10) {
          violations.add(
              String.format(
                  "%s step %.3f ms < callee %.3f ms",
                  caller.path(), step.elapsedMs(), callee.elapsedMs()));
        }
        minMarginMs = Math.min(minMarginMs, margin);
        maxMarginMs = Math.max(maxMarginMs, margin);
      }
      System.out.println(
          "[wo14-acc05] "
              + combo
              + ": caller step >= callee transaction in "
              + holds
              + "/"
              + CALLS
              + " calls, margin min "
              + String.format("%.3f", minMarginMs)
              + " ms, max "
              + String.format("%.3f", maxMarginMs)
              + " ms; caller app id "
              + appIdA
              + ", callee app id "
              + appIdB
              + (violations.isEmpty() ? "" : "; violations " + violations));
      assertThat(holds)
          .as("T-ACC-05: caller HTTP step >= callee elapsed; violations %s", violations)
          .isEqualTo(CALLS);

      threeHop(a, b, appIdA, appIdB);
      assertThatAgentLog(a.app.agentLog()).hasStartBanner().hasNoErrors();
      assertThatAgentLog(b.app.agentLog()).hasStartBanner().hasNoErrors();
    } finally {
      HttpClientRuns.deleteTree(root);
    }
  }

  /** depth=2: A -> B -> B (B's default hop target is itself). */
  static void threeHop(Run a, Run b, int appIdA, int appIdB) throws Exception {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < DEEP_CALLS; i++) {
      long id = HttpClientRuns.nextId();
      ids.add(id);
      HttpResult result = a.app.get("/api/hop/" + id + "?depth=2&client=hurl");
      assertThat(result.status()).as(result.body()).isEqualTo(200);
    }
    for (long id : ids) {
      String path = "/api/hop/" + id;
      TransactionRecord caller =
          a.collected()
              .awaitTransactions(tx -> path.equals(tx.path()), 1, HttpClientRuns.WAIT)
              .get(0);
      List<TransactionRecord> callees =
          b.collected().awaitTransactions(tx -> path.equals(tx.path()), 2, HttpClientRuns.WAIT);
      assertThat(callees).as("two B transactions of %s", path).hasSize(2);
      Map<Long, Integer> callers = new LinkedHashMap<>();
      for (TransactionRecord callee : callees) {
        assertThat(callee.traceId()).isEqualTo(caller.traceId());
        assertThat(callee.field("incoming_trace")).isEqualTo(true);
        callers.merge((Long) callee.field("caller_app_id"), 1, Integer::sum);
      }
      assertThat(callers)
          .as("callers of the two B hops: A, then B itself")
          .containsEntry((long) appIdA, 1)
          .containsEntry((long) appIdB, 1);
    }
  }

  static Run start(String name, Combo combo, Map<String, String> env, Path root) throws Exception {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("app.name", name);
    settings.put("sampling.profile.normal-rate", "1.0");
    settings.put("propagation.argos-state.hosts", HttpClientRuns.HOST);
    return HttpClientRuns.start(
        name + "-jdk" + combo.jdk(),
        SampleApp.named(combo.app()),
        combo.jdk(),
        settings,
        env,
        root);
  }
}
