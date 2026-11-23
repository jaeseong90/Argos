/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.boot.Jdk11TestInstaller;
import io.argos.agent.core.context.Propagation;
import io.argos.agent.core.context.PropagationSettings;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.instrumentation.httpclient.jdk11.testagent.InstrumentationHolderAgent;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The httpclient-jdk11 advice inlined, as the agent does, into the real {@code
 * jdk.internal.net.http.HttpClientImpl} and {@code MultiExchange} (the core's installer
 * retransforms them, injects the helper into the platform class loader and adds the read edges),
 * calling a loopback server with the real tracer: one HTTP step per {@code send}/{@code sendAsync},
 * the HOST text of ADR-0010 C-142, propagation headers (10 §8, SEC-38), the failure classes of 10
 * §6.5 and the {@code sendAsync} completion point (U-10). Runs on JDK 11 ({@code test}) and JDK 21
 * ({@code testJdk21}).
 */
@Tag("req:F-A05")
@Tag("req:F-A06")
@Tag("req:F-A32")
@Tag("req:SEC-38")
class Jdk11AdviceTest {
  private static final String APP_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  /** RFC 5737 TEST-NET-1: never routed, so a connect attempt waits for the connect timeout. */
  private static final URI UNROUTABLE = URI.create("http://192.0.2.1:81/x");

  private static LocalHttpServer server;
  private static Jdk11TestInstaller.Result installed;
  private static AgentControl control;
  private static HttpClient client;

  private final Dictionary dictionary = new Dictionary();
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private TracerImpl tracer;
  private int errorsBefore;

  @BeforeAll
  static void install() throws Exception {
    Instrumentation inst = InstrumentationHolderAgent.instrumentation();
    assertThat(inst).as("-javaagent holder of build.gradle.kts").isNotNull();
    assertThat(Argos.class.getClassLoader())
        .as("bootstrap API on the boot class path, as with the agent jar")
        .isNull();
    installed = Jdk11TestInstaller.install(inst, new HttpClientJdk11Module());
    Constructor<AgentControl> constructor = AgentControl.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    control = constructor.newInstance();
    server = LocalHttpServer.start();
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  @AfterAll
  static void stop() throws Exception {
    server.close();
  }

  @BeforeEach
  void installTracer() {
    tracer = TracerImpl.builder().control(control).dictionary(dictionary).sink(ended::add).build();
    control.installTracer(tracer);
    control.setModuleState(HttpClientJdk11Module.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(HttpClientJdk11Module.MODULE_ID);
    server.clear();
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(ModuleHealth.errors(HttpClientJdk11Module.MODULE_ID))
        .as("advice and helper errors (suppressed exceptions)")
        .isEqualTo(errorsBefore);
    assertThat(installed.errors).as("transformation errors").isEmpty();
  }

  /** What the application saw and what the agent recorded for one transaction. */
  private static final class Outcome {
    final Transaction tx;
    final Object value;
    final Throwable error;

    Outcome(Transaction tx, Object value, Throwable error) {
      this.tx = tx;
      this.value = value;
      this.error = error;
    }

    StepBuffer steps() {
      return tx.steps();
    }

    HttpResponse<?> response() {
      return (HttpResponse<?>) value;
    }
  }

  /** Runs {@code body} inside a new HTTP transaction and ends it right after it returns. */
  private Outcome inTransaction(Callable<?> body) {
    ended.clear();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/pay/{id}", System.nanoTime());
    assertThat(ctx).isNotNull();
    Object value = null;
    Throwable error = null;
    try {
      value = body.call();
    } catch (Throwable t) {
      error = t;
    }
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
    return new Outcome(ended.get(0), value, error);
  }

  private static HttpRequest get(String path) {
    return HttpRequest.newBuilder(URI.create(server.url(path))).build();
  }

  private Outcome send(HttpRequest request) {
    return inTransaction(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
  }

  /** {@code sendAsync(..).join()}: the transaction ends as soon as join() returns. */
  private Outcome sendAsync(HttpRequest request) {
    return inTransaction(
        () -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join());
  }

  private String hostPort() {
    return "127.0.0.1:" + server.port();
  }

  private void assertOneStep(Outcome outcome, String detail, int status, int failure) {
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).as("HTTP steps").isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.HTTP);
    assertThat(steps.isClosed(0)).as("step closed before the transaction ended").isTrue();
    assertThat(steps.status(0)).isEqualTo(status);
    assertThat(steps.failure(0)).isEqualTo(failure);
    assertThat(steps.error(0)).isEqualTo(failure != FailureKind.NONE);
    assertThat(steps.targetId(0)).isEqualTo(steps.nameId(0));
    if (detail != null) {
      assertThat(steps.detail(0)).isEqualTo(detail);
    }
    assertThat(outcome.tx.context().extCount).as("ext_count").isEqualTo(1);
  }

  private static String traceparentOf(Outcome outcome) {
    TraceContext ctx = outcome.tx.context();
    return Propagation.formatTraceparent(ctx.traceIdHi, ctx.traceIdLo, ctx.spanId);
  }

  @Test
  @DisplayName(
      "T-AG-056m send 200: one HTTP step (HOST http://127.0.0.1:port, detail without query), the"
          + " transaction's traceparent, no tracestate by default, the response unchanged")
  void send200() {
    Outcome outcome = send(get("/ok?card=4111111111111111"));
    assertThat(outcome.error).isNull();
    assertThat(outcome.response().statusCode()).isEqualTo(200);
    assertThat(outcome.response().body()).isEqualTo("status 200");
    assertThat(outcome.response().request().getClass().getName())
        .as("the response's request is the JDK's, not the wrapper")
        .isNotEqualTo(Jdk11RequestHelper.class.getName());
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertThat(dictionary.text(outcome.steps().nameId(0))).isEqualTo("http://" + hostPort());
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).traceparent).isEqualTo(traceparentOf(outcome));
    assertThat(seen.get(0).tracestate).isNull();
    assertThat(installed.transformed)
        .contains(HttpClientJdk11Module.CLIENT_CLASS, HttpClientJdk11Module.EXCHANGE_CLASS);
  }

  @Test
  @DisplayName(
      "T-AG-056m U-10 sendAsync(..).join(): the step is closed when join() returns (20 calls),"
          + " one step each, the header reaches the server")
  void sendAsyncCompletesBeforeTheReturnedFuture() {
    for (int i = 0; i < 20; i++) {
      server.clear();
      Outcome outcome = sendAsync(get("/ok"));
      assertThat(outcome.error).isNull();
      assertThat(outcome.response().statusCode()).isEqualTo(200);
      assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
      assertThat(server.seen().get(0).traceparent).isEqualTo(traceparentOf(outcome));
    }
  }

  @Test
  @DisplayName(
      "T-AG-056m sendAsync returns the JDK's own future (never replaced or wrapped); the"
          + " three-argument sendAsync records one step too")
  void sendAsyncFutureUntouched() throws Exception {
    CompletableFuture<HttpResponse<String>> outside =
        client.sendAsync(get("/ok"), HttpResponse.BodyHandlers.ofString());
    String jdkFuture = outside.getClass().getName();
    outside.join();
    Outcome outcome =
        inTransaction(
            () -> {
              CompletableFuture<HttpResponse<String>> future =
                  client.sendAsync(get("/ok"), HttpResponse.BodyHandlers.ofString(), null);
              assertThat(future.getClass().getName()).isEqualTo(jdkFuture);
              return future.join();
            });
    assertThat(outcome.error).isNull();
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
  }

  @Test
  @DisplayName(
      "T-AG-056m POST: the body publisher is the application's (body length unchanged), one step")
  void post() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.url("/ok")))
            .POST(HttpRequest.BodyPublishers.ofString("amount=10"))
            .build();
    Outcome outcome = send(request);
    assertThat(outcome.error).isNull();
    assertOneStep(outcome, "POST " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertThat(server.seen().get(0).method).isEqualTo("POST");
    assertThat(server.seen().get(0).bodyLength).isEqualTo(9);
  }

  @Test
  @DisplayName(
      "T-AG-057m an application's own traceparent and tracestate are sent unchanged; the kept"
          + " traceparent is recorded in the step detail")
  void preservesTheApplicationsTraceparent() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.url("/ok")))
            .header("traceparent", APP_TRACEPARENT)
            .header("tracestate", "vendor=abc")
            .build();
    Outcome outcome = send(request);
    assertOneStep(
        outcome, "GET " + hostPort() + "/ok traceparent=" + APP_TRACEPARENT, 200, FailureKind.NONE);
    assertThat(server.seen().get(0).traceparent).isEqualTo(APP_TRACEPARENT);
    assertThat(server.seen().get(0).tracestate).isEqualTo("vendor=abc");
  }

  @Test
  @DisplayName(
      "T-AG-057m/T-SEC-38m argos tracestate entry only for propagation.argos-state.hosts, first,"
          + " before the application's entries")
  void argosEntryOnlyForArgosStateHosts() {
    tracer.setSessionIds(0x1a, 0x2b);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.url("/ok")))
            .header("tracestate", "vendor=abc")
            .build();
    send(request);
    assertThat(server.seen().get(0).tracestate)
        .as("default: no argos entry")
        .isEqualTo("vendor=abc");

    server.clear();
    tracer.setPropagation(
        PropagationSettings.of(
            Collections.<String>emptyList(), Collections.singletonList("127.0.0.1"), true));
    sendAsync(request);
    assertThat(server.seen().get(0).tracestate).startsWith("argos=1a:2b:").endsWith(",vendor=abc");
  }

  @Test
  @DisplayName(
      "T-SEC-38m propagation.exclude-hosts: neither traceparent nor tracestate is sent, the step"
          + " is still recorded (send and sendAsync)")
  void excludedHostGetsNoHeaders() {
    tracer.setSessionIds(1, 2);
    tracer.setPropagation(
        PropagationSettings.of(
            Arrays.asList("10.*", "127.0.0.?"), Collections.singletonList("127.0.0.1"), true));
    assertOneStep(send(get("/ok")), "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertOneStep(sendAsync(get("/ok")), "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    for (LocalHttpServer.Seen seen : server.seen()) {
      assertThat(seen.traceparent).isNull();
      assertThat(seen.tracestate).isNull();
    }
  }

  @Test
  @DisplayName(
      "T-AG-056m status 500: HTTP_5XX with the status (send and sendAsync); 404: a response, not a"
          + " failure")
  void statuses() {
    Outcome failed = send(get("/status/500"));
    assertThat(failed.response().statusCode()).isEqualTo(500);
    assertOneStep(failed, "GET " + hostPort() + "/status/500", 500, FailureKind.HTTP_5XX);
    Outcome failedAsync = sendAsync(get("/status/503"));
    assertOneStep(failedAsync, "GET " + hostPort() + "/status/503", 503, FailureKind.HTTP_5XX);
    Outcome missing = send(get("/status/404"));
    assertOneStep(missing, "GET " + hostPort() + "/status/404", 404, FailureKind.NONE);
  }

  @Test
  @DisplayName(
      "T-AG-056m request timeout: TIMEOUT, the HttpTimeoutException reaches the application (send"
          + " and sendAsync)")
  void requestTimeout() {
    HttpRequest slow =
        HttpRequest.newBuilder(URI.create(server.url("/slow")))
            .timeout(Duration.ofMillis(200))
            .build();
    Outcome outcome = send(slow);
    assertThat(outcome.error).isInstanceOf(HttpTimeoutException.class);
    assertOneStep(outcome, "GET " + hostPort() + "/slow", 0, FailureKind.TIMEOUT);
    Outcome async = sendAsync(slow);
    assertThat(async.error)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(HttpTimeoutException.class);
    assertOneStep(async, "GET " + hostPort() + "/slow", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName("T-AG-056m connect timeout: TIMEOUT (HttpConnectTimeoutException, 10 §6.5)")
  void connectTimeout() {
    HttpClient shortConnect =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(300))
            .build();
    Outcome outcome =
        inTransaction(
            () ->
                shortConnect.send(
                    HttpRequest.newBuilder(UNROUTABLE).build(),
                    HttpResponse.BodyHandlers.ofString()));
    assumeTrue(
        outcome.error instanceof HttpConnectTimeoutException,
        "this network answers TEST-NET-1 instead of dropping it: " + outcome.error);
    assertOneStep(outcome, "GET 192.0.2.1:81/x", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056m connection refused: CONNECT_FAIL, the ConnectException reaches the application"
          + " (send and sendAsync)")
  void connectionRefused() throws Exception {
    HttpRequest refused =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + LocalHttpServer.closedPort() + "/x"))
            .build();
    Outcome outcome = send(refused);
    assertThat(outcome.error).isInstanceOf(ConnectException.class);
    assertOneStep(outcome, null, 0, FailureKind.CONNECT_FAIL);
    Outcome async = sendAsync(refused);
    assertThat(async.error)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ConnectException.class);
    assertOneStep(async, null, 0, FailureKind.CONNECT_FAIL);
  }

  @Test
  @DisplayName("T-AG-056m connection closed without a response: OTHER, one step")
  void closedWithoutResponse() {
    Outcome outcome = send(HttpRequest.newBuilder(URI.create(server.dropUrl())).build());
    assertThat(outcome.error).isInstanceOf(IOException.class);
    assertThat(outcome.error).isNotInstanceOf(HttpTimeoutException.class);
    assertOneStep(outcome, null, 0, FailureKind.OTHER);
  }

  @Test
  @DisplayName(
      "T-AG-056m redirect (Redirect.NORMAL): one step with the final status, both requests carry"
          + " the header")
  void redirect() {
    HttpClient following =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    Outcome outcome =
        inTransaction(() -> following.send(get("/redirect"), HttpResponse.BodyHandlers.ofString()));
    assertThat(outcome.response().statusCode()).isEqualTo(200);
    assertOneStep(outcome, "GET " + hostPort() + "/redirect", 200, FailureKind.NONE);
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(2);
    for (LocalHttpServer.Seen request : seen) {
      assertThat(request.traceparent).as(request.path).isEqualTo(traceparentOf(outcome));
    }
  }

  @Test
  @DisplayName("T-AG-056m outside a transaction: the request is not replaced, nothing is recorded")
  void outsideATransaction() throws Exception {
    ended.clear();
    HttpResponse<String> response = client.send(get("/ok"), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(client.sendAsync(get("/ok"), HttpResponse.BodyHandlers.ofString()).join().body())
        .isEqualTo("status 200");
    assertThat(ended).isEmpty();
    for (LocalHttpServer.Seen seen : server.seen()) {
      assertThat(seen.traceparent).isNull();
      assertThat(seen.tracestate).isNull();
    }
  }
}
