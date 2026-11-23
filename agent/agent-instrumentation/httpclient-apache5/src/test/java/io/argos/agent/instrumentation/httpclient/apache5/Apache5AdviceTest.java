/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.core.trace.StepBuffer;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The classic HttpClient 5 advice inlined, as the agent does, into the real library classes
 * (advising class loader), calling a loopback server with the real tracer: one HTTP step per call,
 * the HOST text of ADR-0010 C-142, propagation headers (10 §8, SEC-38) and the failure classes of
 * 10 §6.5.
 */
@Tag("req:F-A05")
@Tag("req:F-A06")
@Tag("req:F-A32")
@Tag("req:SEC-38")
class Apache5AdviceTest {
  private static final String FIXTURE =
      "io.argos.agent.instrumentation.httpclient.apache5.fixture.Apache5Fixture";
  private static final String APP_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private static LocalHttpServer server;
  private static Class<?> fixture;

  private final TestTracing tracing = new TestTracing(HttpClientApache5Module.MODULE_ID);

  @BeforeAll
  static void startServer() throws Exception {
    server = LocalHttpServer.start();
    AdvisingLoader loader =
        new AdvisingLoader(
            Apache5AdviceTest.class.getClassLoader(),
            new HttpClientApache5Module(),
            "org.apache.hc.",
            Apache5Helper.class.getName(),
            "io.argos.agent.instrumentation.httpclient.apache5.fixture.");
    fixture = loader.loadClass(FIXTURE);
  }

  @AfterAll
  static void stopServer() throws Exception {
    server.close();
  }

  @BeforeEach
  void install() {
    tracing.install();
    server.clear();
  }

  @AfterEach
  void uninstall() {
    tracing.uninstall();
  }

  private TestTracing.Outcome get(String url, int readTimeoutMs, String traceparent) {
    return tracing.inTransaction(
        () -> TestTracing.call(fixture, "get", url, readTimeoutMs, traceparent));
  }

  private String host() {
    return "127.0.0.1:" + server.port();
  }

  private void assertOneStep(TestTracing.Outcome outcome, String path, int status, int failure) {
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).as("HTTP steps").isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.HTTP);
    assertThat(tracing.text(steps.nameId(0))).isEqualTo("http://" + host());
    assertThat(steps.targetId(0)).isEqualTo(steps.nameId(0));
    assertThat(steps.detail(0)).startsWith("GET " + host() + path);
    assertThat(steps.isClosed(0)).isTrue();
    assertThat(steps.status(0)).isEqualTo(status);
    assertThat(steps.failure(0)).isEqualTo(failure);
    assertThat(steps.error(0)).isEqualTo(failure != FailureKind.NONE);
    assertThat(outcome.tx.context().extCount).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-AG-056m 200: one HTTP step (HOST http://127.0.0.1:port, detail without query),"
          + " traceparent of the transaction, no tracestate by default")
  void success() {
    TestTracing.Outcome outcome = get(server.url("/ok?card=4111111111111111"), 2000, null);
    assertThat(outcome.error).isNull();
    assertThat(outcome.value).isEqualTo(200);
    assertOneStep(outcome, "/ok", 200, FailureKind.NONE);
    assertThat(outcome.steps().detail(0)).isEqualTo("GET " + host() + "/ok");
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).traceparent)
        .matches("00-" + outcome.traceId() + "-[0-9a-f]{16}-01")
        .doesNotContain("-0000000000000000-");
    assertThat(seen.get(0).tracestate).as("SEC-38: no argos entry by default").isNull();
  }

  @Test
  @DisplayName("T-AG-056m status 500: HTTP_5XX, the response reaches the application")
  void serverError() {
    TestTracing.Outcome outcome = get(server.url("/status/500"), 2000, null);
    assertThat(outcome.value).isEqualTo(500);
    assertOneStep(outcome, "/status/500", 500, FailureKind.HTTP_5XX);
  }

  @Test
  @DisplayName(
      "T-AG-056m read timeout: TIMEOUT, the SocketTimeoutException reaches the application")
  void readTimeout() {
    TestTracing.Outcome outcome = get(server.url("/slow"), 300, null);
    assertThat(outcome.error).isInstanceOf(SocketTimeoutException.class);
    assertOneStep(outcome, "/slow", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName("T-AG-056m connection refused: CONNECT_FAIL, HttpHostConnectException unchanged")
  void refused() throws Exception {
    int port = LocalHttpServer.closedPort();
    TestTracing.Outcome outcome = get("http://127.0.0.1:" + port + "/ok", 2000, null);
    assertThat(outcome.error).isInstanceOf(ConnectException.class);
    assertThat(outcome.error.getClass().getName())
        .isEqualTo("org.apache.hc.client5.http.HttpHostConnectException");
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(tracing.text(steps.nameId(0))).isEqualTo("http://127.0.0.1:" + port);
    assertThat(steps.failure(0)).isEqualTo(FailureKind.CONNECT_FAIL);
  }

  @Test
  @DisplayName("T-AG-056m connection closed without a response: OTHER, one step")
  void noResponse() {
    TestTracing.Outcome outcome = get(server.dropUrl(), 2000, null);
    assertThat(outcome.error).isNotNull();
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.failure(0)).isEqualTo(FailureKind.OTHER);
    assertThat(steps.detail(0)).endsWith("/drop");
  }

  @Test
  @DisplayName(
      "T-AG-056m redirect: one step with the final status, both requests carry the traceparent")
  void redirect() {
    TestTracing.Outcome outcome = get(server.url("/redirect"), 2000, null);
    assertThat(outcome.value).isEqualTo(200);
    assertOneStep(outcome, "/redirect", 200, FailureKind.NONE);
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(2);
    assertThat(seen.get(1).path).isEqualTo("/ok");
    assertThat(seen.get(1).traceparent).isEqualTo(seen.get(0).traceparent).isNotNull();
  }

  @Test
  @DisplayName(
      "T-AG-057m an application's own traceparent is sent unchanged and kept in the step detail")
  void preservesApplicationTraceparent() {
    TestTracing.Outcome outcome = get(server.url("/ok"), 2000, APP_TRACEPARENT);
    assertThat(server.seen().get(0).traceparent).isEqualTo(APP_TRACEPARENT);
    assertThat(outcome.steps().detail(0))
        .isEqualTo("GET " + host() + "/ok traceparent=" + APP_TRACEPARENT);
  }

  @Test
  @DisplayName(
      "T-AG-057m/T-SEC-38m argos tracestate entry only for a host of propagation.argos-state.hosts")
  void argosStateHosts() {
    tracing.tracer.setSessionIds(0x2a, 0x7);
    tracing.propagation(Collections.<String>emptyList(), Collections.singletonList("127.0.0.1"));
    get(server.url("/ok"), 2000, null);
    assertThat(server.seen().get(0).tracestate).startsWith("argos=2a:7:");

    server.clear();
    tracing.propagation(Collections.<String>emptyList(), Collections.singletonList("other.test"));
    get(server.url("/ok"), 2000, null);
    assertThat(server.seen().get(0).tracestate).isNull();
  }

  @Test
  @DisplayName(
      "T-SEC-38m propagation.exclude-hosts: neither header is sent, the step is still recorded")
  void excludedHost() {
    tracing.tracer.setSessionIds(0x2a, 0x7);
    tracing.propagation(
        Collections.singletonList("127.0.0.*"), Collections.singletonList("127.0.0.1"));
    TestTracing.Outcome outcome = get(server.url("/ok"), 2000, null);
    assertThat(server.seen().get(0).traceparent).isNull();
    assertThat(server.seen().get(0).tracestate).isNull();
    assertOneStep(outcome, "/ok", 200, FailureKind.NONE);
  }

  @Test
  @DisplayName(
      "MinimalHttpClient, and executeOpen without a target (host taken from the request URI): one"
          + " step each")
  void otherClients() {
    TestTracing.Outcome minimal =
        tracing.inTransaction(() -> TestTracing.call(fixture, "getMinimal", server.url("/ok")));
    assertOneStep(minimal, "/ok", 200, FailureKind.NONE);
    TestTracing.Outcome open =
        tracing.inTransaction(() -> TestTracing.call(fixture, "getOpen", server.url("/ok?x=1")));
    assertOneStep(open, "/ok", 200, FailureKind.NONE);
    assertThat(server.seen()).allSatisfy(seen -> assertThat(seen.traceparent).isNotNull());
  }

  @Test
  @DisplayName("outside a transaction: no step, no header")
  void outsideTransaction() throws Exception {
    assertThat(TestTracing.call(fixture, "get", server.url("/ok"), 2000, null)).isEqualTo(200);
    assertThat(server.seen().get(0).traceparent).isNull();
    assertThat(tracing.ended).isEmpty();
  }

  @Test
  @DisplayName("module disabled (instrumentation.disabled): no step, no header")
  void disabled() {
    tracing.control.setModuleState(
        HttpClientApache5Module.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    TestTracing.Outcome outcome = get(server.url("/ok"), 2000, null);
    assertThat(outcome.value).isEqualTo(200);
    assertThat(outcome.steps().size()).isZero();
    assertThat(server.seen().get(0).traceparent).isNull();
  }
}
