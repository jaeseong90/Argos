/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.context.Propagation;
import io.argos.agent.core.context.PropagationSettings;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.SuperMethodCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The HttpURLConnection advice inlined, as the agent does, into the four state-machine methods of
 * the real JDK implementation {@code sun.net.www.protocol.http.HttpURLConnection} (a ByteBuddy
 * subclass whose overrides call the JDK code, so the JDK's own nested calls run through the advice
 * as they would in the retransformed class), calling a loopback server with the real tracer: one
 * HTTP step per connection, the HOST text of ADR-0010 C-142, propagation headers (10 §8, SEC-38)
 * and the failure classes of 10 §6.5. Tests run on JDK 8.
 */
@Tag("req:F-A05")
@Tag("req:F-A06")
@Tag("req:F-A32")
@Tag("req:SEC-38")
class HttpUrlConnectionAdviceTest {
  private static final String APP_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  /** RFC 5737 TEST-NET-1: never routed, so a connect attempt waits for the connect timeout. */
  private static final String UNROUTABLE = "http://192.0.2.1:81/x";

  private static LocalHttpServer server;
  private static Class<?> advised;

  private final AgentControl control = TestControls.newControl();
  private final Dictionary dictionary = new Dictionary();
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private TracerImpl tracer;
  private int errorsBefore;

  @BeforeAll
  static void start() throws Exception {
    server = LocalHttpServer.start();
    Class<?> jdk = Class.forName(HttpClientJdkModule.CONNECTION_CLASS);
    advised =
        new ByteBuddy()
            .subclass(jdk)
            .name("io.argos.test.httpclient.jdk.AdvisedHttpURLConnection")
            .method(HttpClientJdkModule.statePoints())
            .intercept(SuperMethodCall.INSTANCE)
            .visit(
                TestAdvice.of(HttpUrlConnectionAdvice.class, HttpClientJdkModule.MODULE_ID)
                    .on(HttpClientJdkModule.statePoints()))
            .make()
            .load(
                HttpUrlConnectionAdviceTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
  }

  @AfterAll
  static void stop() throws Exception {
    server.close();
  }

  @BeforeEach
  void installTracer() {
    tracer = TracerImpl.builder().control(control).dictionary(dictionary).sink(ended::add).build();
    control.installTracer(tracer);
    control.setModuleState(HttpClientJdkModule.MODULE_ID, ModuleHealth.ENABLED);
    errorsBefore = ModuleHealth.errors(HttpClientJdkModule.MODULE_ID);
    server.clear();
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(ModuleHealth.errors(HttpClientJdkModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(errorsBefore);
  }

  /** A connection of the advised JDK implementation, with short timeouts. */
  private static HttpURLConnection open(String url) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection)
            advised
                .getConstructor(URL.class, Proxy.class)
                .newInstance(new URL(url), Proxy.NO_PROXY);
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(5000);
    connection.setUseCaches(false);
    return connection;
  }

  private static HttpURLConnection refusing(String url) throws Exception {
    HttpURLConnection connection = open(url);
    connection.setConnectTimeout(10_000);
    return connection;
  }

  /** What the application saw and what the agent recorded for one transaction. */
  private static final class Outcome {
    final Transaction tx;
    final int status;
    final Throwable error;

    Outcome(Transaction tx, int status, Throwable error) {
      this.tx = tx;
      this.status = status;
      this.error = error;
    }

    StepBuffer steps() {
      return tx.steps();
    }
  }

  private interface Exchange {
    int run(HttpURLConnection connection) throws IOException;
  }

  /** Runs {@code exchange} on {@code connection} inside a new HTTP transaction and ends it. */
  private Outcome inTransaction(HttpURLConnection connection, Exchange exchange) {
    ended.clear();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/pay/{id}", System.nanoTime());
    assertThat(ctx).isNotNull();
    int status = -1;
    Throwable error = null;
    try {
      status = exchange.run(connection);
    } catch (Throwable t) {
      error = t;
    }
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
    return new Outcome(ended.get(0), status, error);
  }

  /** The common application pattern: status, then the body (or the error stream). */
  private static int readAll(HttpURLConnection connection) throws IOException {
    int status = connection.getResponseCode();
    InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (in != null) {
      drain(in);
    }
    return status;
  }

  private static void drain(InputStream in) throws IOException {
    try {
      byte[] buffer = new byte[256];
      while (in.read(buffer) >= 0) {
        // discard
      }
    } finally {
      in.close();
    }
  }

  private String hostPort() {
    return "127.0.0.1:" + server.port();
  }

  private void assertOneStep(Outcome outcome, String detail, int status, int failure) {
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).as("HTTP steps").isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.HTTP);
    assertThat(steps.isClosed(0)).isTrue();
    assertThat(steps.status(0)).isEqualTo(status);
    assertThat(steps.failure(0)).isEqualTo(failure);
    assertThat(steps.error(0)).isEqualTo(failure != FailureKind.NONE);
    assertThat(steps.elapsedNanos(0)).isGreaterThanOrEqualTo(0L);
    assertThat(steps.targetId(0)).isEqualTo(steps.nameId(0));
    if (detail != null) {
      assertThat(steps.detail(0)).isEqualTo(detail);
    }
    assertThat(outcome.tx.context().extCount).as("ext_count").isEqualTo(1);
  }

  private String expectedTraceparent(Outcome outcome) {
    TraceContext ctx = outcome.tx.context();
    return Propagation.formatTraceparent(ctx.traceIdHi, ctx.traceIdLo, ctx.spanId);
  }

  @Test
  @DisplayName(
      "T-AG-056m 200: one HTTP step (HOST http://127.0.0.1:port, detail without query), the"
          + " transaction's traceparent and no tracestate by default")
  void success() throws Exception {
    Outcome outcome = inTransaction(open(server.url("/ok?card=4111111111111111")), c -> readAll(c));
    assertThat(outcome.error).isNull();
    assertThat(outcome.status).isEqualTo(200);
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertThat(dictionary.text(outcome.steps().nameId(0))).isEqualTo("http://" + hostPort());
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).traceparent).isEqualTo(expectedTraceparent(outcome));
    assertThat(seen.get(0).tracestate).isNull();
  }

  @Test
  @DisplayName(
      "T-AG-056m POST through getOutputStream: the step starts before the body is written, one"
          + " step, the header reaches the server")
  void postWithBody() throws Exception {
    HttpURLConnection connection = open(server.url("/ok"));
    connection.setDoOutput(true); // GET with a body: the JDK sends POST
    Outcome outcome =
        inTransaction(
            connection,
            c -> {
              OutputStream out = c.getOutputStream();
              out.write("amount=10".getBytes(StandardCharsets.UTF_8));
              out.close();
              return readAll(c);
            });
    assertThat(outcome.error).isNull();
    assertOneStep(outcome, "POST " + hostPort() + "/ok", 200, FailureKind.NONE);
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).method).isEqualTo("POST");
    assertThat(seen.get(0).bodyLength).isEqualTo(9);
    assertThat(seen.get(0).traceparent).isEqualTo(expectedTraceparent(outcome));
  }

  @Test
  @DisplayName(
      "T-AG-056m connect() first (RestTemplate's pattern): the header is set before the JDK"
          + " connects, one step ending with the response")
  void connectFirst() throws Exception {
    Outcome outcome =
        inTransaction(
            open(server.url("/ok")),
            c -> {
              c.connect();
              return readAll(c);
            });
    assertThat(outcome.error).isNull();
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertThat(server.seen().get(0).traceparent).isEqualTo(expectedTraceparent(outcome));
  }

  @Test
  @DisplayName(
      "T-AG-056m repeated getInputStream/getErrorStream calls after the response add no step;"
          + " outside a transaction nothing is recorded or sent")
  void endsOnceAndOnlyInTransactions() throws Exception {
    Outcome outcome =
        inTransaction(
            open(server.url("/status/500")),
            c -> {
              int status = c.getResponseCode();
              c.getResponseCode();
              drain(c.getErrorStream());
              try {
                c.getInputStream();
              } catch (IOException expected) {
                // 5xx: the JDK rethrows its remembered exception
              }
              return status;
            });
    assertThat(outcome.status).isEqualTo(500);
    assertOneStep(outcome, "GET " + hostPort() + "/status/500", 500, FailureKind.HTTP_5XX);

    server.clear();
    ended.clear();
    assertThat(readAll(open(server.url("/ok")))).isEqualTo(200);
    assertThat(ended).isEmpty();
    assertThat(server.seen()).hasSize(1);
    assertThat(server.seen().get(0).traceparent).isNull();
    assertThat(server.seen().get(0).tracestate).isNull();
  }

  @Test
  @DisplayName(
      "T-AG-057m an application's own traceparent and tracestate are sent unchanged; the kept"
          + " traceparent is recorded in the step detail")
  void preservesTheApplicationsTraceparent() throws Exception {
    HttpURLConnection connection = open(server.url("/ok"));
    connection.setRequestProperty("traceparent", APP_TRACEPARENT);
    connection.setRequestProperty("tracestate", "vendor=abc");
    Outcome outcome = inTransaction(connection, c -> readAll(c));
    assertOneStep(
        outcome, "GET " + hostPort() + "/ok traceparent=" + APP_TRACEPARENT, 200, FailureKind.NONE);
    assertThat(server.seen().get(0).traceparent).isEqualTo(APP_TRACEPARENT);
    assertThat(server.seen().get(0).tracestate).isEqualTo("vendor=abc");
  }

  @Test
  @DisplayName(
      "T-AG-057m/T-SEC-38m argos tracestate entry only for propagation.argos-state.hosts (app id"
          + " from the session), first, before the application's entries")
  void argosEntryOnlyForArgosStateHosts() throws Exception {
    tracer.setSessionIds(0x1a, 0x2b);
    HttpURLConnection plain = open(server.url("/ok"));
    plain.setRequestProperty("tracestate", "vendor=abc");
    inTransaction(plain, c -> readAll(c));
    assertThat(server.seen().get(0).tracestate)
        .as("default: no argos entry")
        .isEqualTo("vendor=abc");

    server.clear();
    tracer.setPropagation(
        PropagationSettings.of(
            Collections.<String>emptyList(), Collections.singletonList("127.0.0.1"), true));
    HttpURLConnection internal = open(server.url("/ok"));
    internal.setRequestProperty("tracestate", "vendor=abc");
    inTransaction(internal, c -> readAll(c));
    assertThat(server.seen().get(0).tracestate).startsWith("argos=1a:2b:").endsWith(",vendor=abc");
  }

  @Test
  @DisplayName(
      "T-SEC-38m propagation.exclude-hosts: neither traceparent nor tracestate is sent, the step"
          + " is still recorded")
  void excludedHostGetsNoHeaders() throws Exception {
    tracer.setSessionIds(1, 2);
    tracer.setPropagation(
        PropagationSettings.of(
            Arrays.asList("10.*", "127.0.0.?"), Collections.singletonList("127.0.0.1"), true));
    Outcome outcome = inTransaction(open(server.url("/ok")), c -> readAll(c));
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
    assertThat(server.seen().get(0).traceparent).isNull();
    assertThat(server.seen().get(0).tracestate).isNull();
  }

  @Test
  @DisplayName("T-AG-056m redirect: one step with the final status, both requests carry the header")
  void redirect() throws Exception {
    Outcome outcome = inTransaction(open(server.url("/redirect")), c -> readAll(c));
    assertThat(outcome.status).isEqualTo(200);
    assertOneStep(outcome, "GET " + hostPort() + "/redirect", 200, FailureKind.NONE);
    List<LocalHttpServer.Seen> seen = server.seen();
    assertThat(seen).hasSize(2);
    for (LocalHttpServer.Seen request : seen) {
      assertThat(request.traceparent).as(request.path).isEqualTo(expectedTraceparent(outcome));
    }
  }

  @Test
  @DisplayName(
      "T-AG-056m status 500: HTTP_5XX with the status; 404: a response, not a failure; the"
          + " application's exception is unchanged")
  void statuses() throws Exception {
    Outcome failed = inTransaction(open(server.url("/status/500")), c -> c.getInputStream().read());
    assertThat(failed.error).isInstanceOf(IOException.class).hasMessageContaining("500");
    assertOneStep(failed, "GET " + hostPort() + "/status/500", 500, FailureKind.HTTP_5XX);

    Outcome missing =
        inTransaction(open(server.url("/status/404")), c -> c.getInputStream().read());
    assertThat(missing.error).isInstanceOf(FileNotFoundException.class);
    assertOneStep(missing, "GET " + hostPort() + "/status/404", 404, FailureKind.NONE);
  }

  @Test
  @DisplayName(
      "T-AG-056m read timeout: TIMEOUT, the SocketTimeoutException reaches the application")
  void readTimeout() throws Exception {
    HttpURLConnection connection = open(server.url("/slow"));
    connection.setReadTimeout(200);
    Outcome outcome = inTransaction(connection, c -> readAll(c));
    assertThat(outcome.error).isInstanceOf(SocketTimeoutException.class);
    assertOneStep(outcome, "GET " + hostPort() + "/slow", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056m connect timeout: TIMEOUT (SocketTimeoutException 'connect timed out', 10 §6.5)")
  void connectTimeout() throws Exception {
    HttpURLConnection connection = open(UNROUTABLE);
    connection.setConnectTimeout(300);
    Outcome outcome = inTransaction(connection, c -> readAll(c));
    assumeTrue(
        outcome.error instanceof SocketTimeoutException,
        "this network answers TEST-NET-1 instead of dropping it: " + outcome.error);
    assertOneStep(outcome, "GET 192.0.2.1:81/x", 0, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056m connection refused: CONNECT_FAIL, from getResponseCode() and from an explicit"
          + " connect(), the ConnectException unchanged")
  void connectionRefused() throws Exception {
    String url = "http://127.0.0.1:" + LocalHttpServer.closedPort() + "/x";
    // Windows answers a refused loopback connect after its SYN retries (about 2 s).
    Outcome viaResponse = inTransaction(refusing(url), c -> readAll(c));
    assertThat(viaResponse.error).isInstanceOf(ConnectException.class);
    assertOneStep(viaResponse, null, 0, FailureKind.CONNECT_FAIL);

    Outcome viaConnect =
        inTransaction(
            refusing(url),
            c -> {
              c.connect();
              return 0;
            });
    assertThat(viaConnect.error).isInstanceOf(ConnectException.class);
    assertOneStep(viaConnect, null, 0, FailureKind.CONNECT_FAIL);
  }

  @Test
  @DisplayName("T-AG-056m connection closed without a response: OTHER, one step")
  void closedWithoutResponse() throws Exception {
    Outcome outcome = inTransaction(open(server.dropUrl()), c -> readAll(c));
    assertThat(outcome.error).isInstanceOf(IOException.class);
    assertThat(outcome.error).isNotInstanceOf(SocketTimeoutException.class);
    assertOneStep(outcome, null, 0, FailureKind.OTHER);
  }

  @Test
  @DisplayName("T-AG-056m path parameters such as ;jsessionid are dropped from the detail")
  void pathParameters() throws Exception {
    Outcome outcome = inTransaction(open(server.url("/ok;jsessionid=ABC?card=1")), c -> readAll(c));
    assertOneStep(outcome, "GET " + hostPort() + "/ok", 200, FailureKind.NONE);
  }
}
