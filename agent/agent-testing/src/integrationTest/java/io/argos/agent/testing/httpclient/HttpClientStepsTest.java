/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.httpclient.HttpClientRuns.Call;
import io.argos.agent.testing.httpclient.HttpClientRuns.Combo;
import io.argos.agent.testing.httpclient.HttpClientRuns.Profile;
import io.argos.agent.testing.httpclient.HttpClientRuns.Run;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The outgoing-call checks of 10 §6.5 with the release agent jar over gRPC, for every client of
 * every shop jar app of {@code -Pagent.e2e} (shop-boot2: hurl, hc4, okhttp (OkHttp 3), resttemplate
 * on HC4; shop-boot3 and shop-boot4: hurl, hc5, okhttp (OkHttp 4), jdk11, webclient, resttemplate
 * on HC5), against the run's ext-stub (GET /api/pay/{id}, 18 §4.2):
 *
 * <ul>
 *   <li>T-AG-056 (F-A05, F-A32): exactly one HTTP step per call, RestTemplate included (its request
 *       factory's client records it, no second step); ext-stub received a {@code traceparent} with
 *       the transaction's trace id; {@code ext_count} 1. The failure classes on ext-stub's routes
 *       (the "ext-stub classification" acceptance item of 04 WO-14): {@code timeout=1} (read
 *       timeout) is TIMEOUT, {@code refuse=1} (port 1, nothing listens) is CONNECT_FAIL, {@code
 *       status=500} is HTTP_5XX with status 500, {@code redirect=1} is no failure with the final
 *       status 200.
 *   <li>ADR-0010 C-142: the step's name and target are the HOST dictionary text {@code
 *       http://127.0.0.1:<port>} (scheme, host and port, no path).
 *   <li>10 U-10 and U-11 end to end: java.net.http {@code sendAsync} and OkHttp {@code enqueue}
 *       ({@code mode=async}) record the call in the request's transaction, with the header, and
 *       classify their timeout.
 *   <li>The agent log of every run has no ERROR line.
 * </ul>
 */
@Tag("req:F-A05")
@Tag("req:F-A32")
@Tag("req:F-A33")
@ExtendWith(HttpClientRuns.class)
class HttpClientStepsTest {

  /** The clients each app registers (GET /api/runtime {@code clients}, 18 §4.1). */
  static final Map<String, List<String>> CLIENTS =
      Map.of(
          "shop-boot2", List.of("hurl", "hc4", "okhttp", "resttemplate"),
          "shop-boot3", List.of("hurl", "hc5", "okhttp", "jdk11", "webclient", "resttemplate"),
          "shop-boot4", List.of("hurl", "hc5", "okhttp", "jdk11", "webclient", "resttemplate"));

  /** The clients with {@code mode=async} (10 U-10, U-11). */
  static final List<String> ASYNC_CLIENTS = List.of("okhttp", "jdk11");

  /** One finding line for the report ({@code [wo14-table]} in the result XML's system-out). */
  static void record(String key, String value) {
    System.out.println("[wo14-table] " + key + " | " + value);
  }

  @TestFactory
  @DisplayName("T-AG-056 / C-142 / U-10 / U-11 per app and client (10 §6.5)")
  Stream<DynamicNode> perClient() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (Combo combo : HttpClientRuns.jarCombos()) {
      List<DynamicNode> tests = new ArrayList<>();
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-056 " + combo + ": GET /api/runtime reports the clients of 18 §4.1",
              () ->
                  assertThat(new LinkedHashSet<>(run(combo).clients()))
                      .containsExactlyInAnyOrderElementsOf(CLIENTS.get(combo.app()))));
      for (String client : CLIENTS.get(combo.app())) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-056 "
                    + combo
                    + " "
                    + client
                    + ": one HTTP step, traceparent of the transaction, C-142 HOST text;"
                    + " timeout TIMEOUT, refused CONNECT_FAIL, 500 HTTP_5XX, redirect no failure",
                () -> classify(run(combo), combo, client, "sync")));
        if (ASYNC_CLIENTS.contains(client)) {
          tests.add(
              DynamicTest.dynamicTest(
                  "T-AG-056 "
                      + combo
                      + " "
                      + client
                      + " mode=async ("
                      + (client.equals("jdk11") ? "U-10 sendAsync" : "U-11 enqueue")
                      + "): one HTTP step in the request's transaction, traceparent, TIMEOUT",
                  () -> asyncMode(run(combo), combo, client)));
        }
      }
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-056 " + combo + ": no ERROR in the agent log after every client and failure",
              () -> {
                Run run = run(combo);
                assertThatAgentLog(run.app.agentLog()).hasStartBanner().hasNoErrors();
              }));
      nodes.add(DynamicContainer.dynamicContainer(combo.toString(), tests.stream()));
    }
    return nodes.stream();
  }

  static Run run(Combo combo) throws Exception {
    return HttpClientRuns.run(combo, Profile.DEFAULT);
  }

  /** T-AG-056 and C-142 for one client. */
  static void classify(Run run, Combo combo, String client, String mode) throws Exception {
    String query = "?client=" + client + (mode.equals("sync") ? "" : "&mode=" + mode);
    String expectedHost = Run.hostText(run.extStub.port());

    // OK: one step, the trace id at ext-stub, the HOST text.
    long id = HttpClientRuns.nextId();
    Call ok = run.call("/api/pay/" + id + query, 200);
    assertThat(ok.body()).containsEntry("outcome", "OK");
    StepRecord step = ok.onlyHttpStep(client + " OK");
    assertStep(step, "FAILURE_NONE", 200, client + " OK");
    assertThat(step.name()).as("C-142 step name (HOST text) of %s", client).isEqualTo(expectedHost);
    assertThat(step.target()).as("C-142 step target of %s", client).isEqualTo(expectedHost);
    assertThat(run.hostTexts()).as("HOST dictionary texts").contains(expectedHost);
    assertThat(HttpClientRuns.number(ok.tx().field("ext_count"), "ext_count of " + ok.tx()))
        .isEqualTo(1);
    Map<String, String> headers = run.stubHeaders("/pay/" + id);
    assertThat(headers.get("traceparent"))
        .as("traceparent ext-stub received from %s (F-A32)", client)
        .isNotNull()
        .matches(HttpClientRuns.TRACEPARENT_PATTERN)
        .startsWith("00-" + ok.tx().traceId() + "-");

    // Read timeout: TIMEOUT.
    Call timeout = run.call("/api/pay/" + HttpClientRuns.nextId() + query + "&timeout=1", 504);
    assertThat(timeout.body()).containsEntry("outcome", "TIMEOUT");
    assertStep(timeout.onlyHttpStep(client + " timeout"), "TIMEOUT", null, client + " timeout");

    // Connection refused (port 1 of the ext-stub host): CONNECT_FAIL.
    Call refused = run.call("/api/pay/" + HttpClientRuns.nextId() + query + "&refuse=1", 503);
    assertThat(refused.body()).containsEntry("outcome", "CONNECT_FAIL");
    StepRecord refusedStep = refused.onlyHttpStep(client + " refused");
    assertStep(refusedStep, "CONNECT_FAIL", null, client + " refused");
    assertThat(refusedStep.name())
        .as("C-142 HOST text of the refused call")
        .isEqualTo(Run.hostText(1));

    // 500: HTTP_5XX with the status.
    Call server = run.call("/api/pay/" + HttpClientRuns.nextId() + query + "&status=500", 502);
    assertThat(server.body()).containsEntry("upstreamStatus", 500L);
    assertStep(server.onlyHttpStep(client + " 500"), "HTTP_5XX", 500, client + " 500");

    // Redirect followed by the client: no failure, the final status.
    long redirectId = HttpClientRuns.nextId();
    Call redirect = run.call("/api/pay/" + redirectId + query + "&redirect=1", 200);
    assertThat(redirect.body()).containsEntry("outcome", "OK");
    assertStep(
        redirect.onlyHttpStep(client + " redirect"), "FAILURE_NONE", 200, client + " redirect");

    record(
        combo + " " + client + (mode.equals("sync") ? "" : " " + mode),
        "OK "
            + step.elapsedMs()
            + " ms ["
            + step.detail()
            + "], TIMEOUT, CONNECT_FAIL, HTTP_5XX, redirect "
            + redirect.httpSteps().get(0).status()
            + "; traceparent "
            + headers.get("traceparent"));
  }

  /** U-10 / U-11: {@code mode=async}. */
  static void asyncMode(Run run, Combo combo, String client) throws Exception {
    long id = HttpClientRuns.nextId();
    Call ok = run.call("/api/pay/" + id + "?client=" + client + "&mode=async", 200);
    assertThat(ok.body()).containsEntry("outcome", "OK").containsEntry("mode", "async");
    assertStep(ok.onlyHttpStep(client + " async"), "FAILURE_NONE", 200, client + " async");
    assertThat(run.stubHeaders("/pay/" + id).get("traceparent"))
        .as("traceparent of the %s async call", client)
        .isNotNull()
        .startsWith("00-" + ok.tx().traceId() + "-");
    Call timeout =
        run.call(
            "/api/pay/" + HttpClientRuns.nextId() + "?client=" + client + "&mode=async&timeout=1",
            504);
    assertStep(timeout.onlyHttpStep(client + " async timeout"), "TIMEOUT", null, client);
    record(
        combo + " " + client + " async",
        "OK " + ok.httpSteps().get(0).elapsedMs() + " ms in the request's transaction, TIMEOUT");
  }

  /** The failure class (11 FailureKind name) and, when given, the step status. */
  static void assertStep(StepRecord step, String failure, Integer status, String what) {
    assertThat(step.field("failure")).as("failure class of %s: %s", what, step).isEqualTo(failure);
    if (status != null) {
      assertThat(step.status()).as("status of %s: %s", what, step).isEqualTo(status);
    }
  }
}
