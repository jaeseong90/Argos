/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.core.trace.StepBuffer;
import java.net.ConnectException;
import java.util.ArrayList;
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
 * The Reactor Netty advice inlined, as the agent does, into the real library classes (advising
 * class loader), calling a loopback server with the real tracer from a "servlet" transaction on the
 * test thread: one HTTP step per request for {@code block()}, {@code toFuture()} and {@code
 * subscribe()}, closed before the result reaches the caller; the HOST text of ADR-0010 C-142;
 * propagation headers (10 §8, SEC-38); the failure classes of 10 §6.5; nothing for a subscription
 * on a thread without a transaction (the WebFlux case of F-A33).
 */
@Tag("req:F-A05")
@Tag("req:F-A06")
@Tag("req:F-A32")
@Tag("req:F-A33")
@Tag("req:SEC-38")
class ReactorNettyAdviceTest {
  private static final String FIXTURE =
      "io.argos.agent.instrumentation.httpclient.reactornetty.fixture.ReactorNettyFixture";
  private static final String APP_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private static LocalHttpServer server;
  private static Class<?> fixture;

  private final TestTracing tracing = new TestTracing(HttpClientReactorNettyModule.MODULE_ID);

  @BeforeAll
  static void startServer() throws Exception {
    server = LocalHttpServer.start();
    AdvisingLoader loader =
        new AdvisingLoader(
            ReactorNettyAdviceTest.class.getClassLoader(),
            new HttpClientReactorNettyModule(),
            "reactor.netty.",
            ReactorNettyHelper.class.getName(),
            "io.argos.agent.instrumentation.httpclient.reactornetty.fixture.");
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
    assertThat(steps.isClosed(0)).as("closed before the result reached the caller").isTrue();
    assertThat(steps.status(0)).isEqualTo(status);
    assertThat(steps.failure(0)).isEqualTo(failure);
    assertThat(steps.error(0)).isEqualTo(failure != FailureKind.NONE);
    assertThat(outcome.tx.context().extCount).isEqualTo(1);
  }

  private static List<String> causes(Throwable error) {
    List<String> names = new ArrayList<>();
    for (Throwable t = error; t != null && names.size() < 10; t = t.getCause()) {
      names.add(t.getClass().getName());
    }
    return names;
  }

  private static boolean hasCause(Throwable error, Class<?> type) {
    int depth = 0;
    for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName(
      "T-AG-055m/T-AG-056m block(): one HTTP step (HOST http://127.0.0.1:port, detail without"
          + " query), traceparent of the transaction, no tracestate by default")
  void block() {
    TestTracing.Outcome outcome = get(server.url("/ok?card=4111111111111111"), 2000, null);
    assertThat(outcome.error).isNull();
    assertThat(outcome.value).isEqualTo(200);
    assertOneStep(outcome, "/ok", 200, FailureKind.NONE);
    assertThat(outcome.steps().detail(0)).isEqualTo("GET " + host() + "/ok");
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).traceparent).matches("00-" + outcome.traceId() + "-[0-9a-f]{16}-01");
    assertThat(seen.get(0).tracestate).as("SEC-38: no argos entry by default").isNull();
  }

  @Test
  @DisplayName("T-AG-055m toFuture() and subscribe(): one HTTP step each, with the traceparent")
  void futureAndSubscribe() {
    TestTracing.Outcome future =
        tracing.inTransaction(() -> TestTracing.call(fixture, "future", server.url("/ok")));
    assertThat(future.value).isEqualTo(200);
    assertOneStep(future, "/ok", 200, FailureKind.NONE);
    TestTracing.Outcome subscribed =
        tracing.inTransaction(() -> TestTracing.call(fixture, "subscribe", server.url("/ok")));
    assertThat(subscribed.value).isEqualTo(200);
    assertOneStep(subscribed, "/ok", 200, FailureKind.NONE);
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).traceparent).startsWith("00-" + future.traceId() + "-");
    assertThat(seen.get(1).traceparent).startsWith("00-" + subscribed.traceId() + "-");
  }

  @Test
  @DisplayName(
      "T-AG-055m subscribed on a thread without a transaction (the WebFlux case): no step, no"
          + " header")
  void schedulerThread() {
    TestTracing.Outcome outcome =
        tracing.inTransaction(
            () -> TestTracing.call(fixture, "onSchedulerThread", server.url("/ok")));
    assertThat(outcome.value).isEqualTo(200);
    assertThat(outcome.steps().size()).isZero();
    assertThat(server.seen().get(0).traceparent).isNull();
  }

  @Test
  @DisplayName("T-AG-056m status 500: HTTP_5XX, the response reaches the application")
  void serverError() {
    TestTracing.Outcome outcome = get(server.url("/status/500"), 2000, null);
    assertThat(outcome.value).isEqualTo(500);
    assertOneStep(outcome, "/status/500", 500, FailureKind.HTTP_5XX);
  }

  @Test
  @DisplayName("T-AG-056m response timeout (ReadTimeoutException): TIMEOUT")
  void responseTimeout() {
    TestTracing.Outcome outcome = get(server.url("/slow"), 300, null);
    assertThat(causes(outcome.error)).contains("io.netty.handler.timeout.ReadTimeoutException");
    assertOneStep(outcome, "/slow", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056m connection refused: CONNECT_FAIL, the step opened although no request was"
          + " written")
  void refused() throws Exception {
    int port = LocalHttpServer.closedPort();
    TestTracing.Outcome outcome = get("http://127.0.0.1:" + port + "/ok", 2000, null);
    assertThat(hasCause(outcome.error, ConnectException.class))
        .as(causes(outcome.error).toString())
        .isTrue();
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(tracing.text(steps.nameId(0))).isEqualTo("http://127.0.0.1:" + port);
    assertThat(steps.detail(0)).isEqualTo("GET 127.0.0.1:" + port + "/ok");
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
      "T-AG-057m an application's own traceparent (client default header) is sent unchanged and"
          + " kept in the step detail")
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
        HttpClientReactorNettyModule.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    TestTracing.Outcome outcome = get(server.url("/ok"), 2000, null);
    assertThat(outcome.value).isEqualTo(200);
    assertThat(outcome.steps().size()).isZero();
    assertThat(server.seen().get(0).traceparent).isNull();
  }
}
