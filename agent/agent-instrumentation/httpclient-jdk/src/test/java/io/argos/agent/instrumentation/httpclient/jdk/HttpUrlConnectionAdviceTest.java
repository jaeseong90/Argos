/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TraceIds;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.SuperMethodCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The HttpURLConnection advice inlined by ByteBuddy into an {@link HttpURLConnection} subclass that
 * behaves like the JDK implementation (the exchange runs in the first {@code getInputStream()},
 * which calls itself again through {@code getResponseCode()}; 4xx/5xx throw after the status was
 * read), against the real tracer.
 */
@Tag("req:F-A05")
class HttpUrlConnectionAdviceTest {
  /** Behaves like {@code sun.net.www.protocol.http.HttpURLConnection} for the advice. */
  public static class JdkLikeConnection extends HttpURLConnection {
    int answer = 200;
    IOException failure;
    String sentTraceparent;
    int exchanges;
    private boolean exchanging;

    public JdkLikeConnection(URL url) {
      super(url);
    }

    @Override
    public void connect() {
      connected = true;
    }

    @Override
    public void disconnect() {}

    @Override
    public boolean usingProxy() {
      return false;
    }

    /** The status line, as the JDK's header parser provides it once the response arrived. */
    @Override
    public String getHeaderField(int n) {
      return n == 0 && responseCode != -1 ? "HTTP/1.1 " + responseCode + " Status" : null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      if (exchanging) {
        return new ByteArrayInputStream(new byte[0]);
      }
      if (responseCode == -1 && failure == null) {
        exchanging = true;
        try {
          exchanges++;
          sentTraceparent = connected ? null : getRequestProperty("traceparent");
          connected = true;
          responseCode = answer;
          // The JDK calls getInputStream() again from inside the exchange (getResponseCode()).
          getInputStream();
        } finally {
          exchanging = false;
        }
      }
      if (failure != null) {
        exchanges++;
        throw failure;
      }
      if (responseCode == 404) {
        throw new FileNotFoundException(url.toString());
      }
      if (responseCode >= 400) {
        throw new IOException("Server returned HTTP response code: " + responseCode);
      }
      return new ByteArrayInputStream(new byte[0]);
    }
  }

  private static final Class<? extends JdkLikeConnection> ADVISED =
      new ByteBuddy()
          .subclass(JdkLikeConnection.class)
          .method(HttpClientJdkModule.getInputStream())
          .intercept(SuperMethodCall.INSTANCE)
          .visit(
              TestAdvice.of(HttpUrlConnectionAdvice.class, HttpClientJdkModule.MODULE_ID)
                  .on(HttpClientJdkModule.getInputStream()))
          .make()
          .load(
              HttpUrlConnectionAdviceTest.class.getClassLoader(),
              ClassLoadingStrategy.Default.WRAPPER)
          .getLoaded();

  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final List<Integer> failureKinds = Collections.synchronizedList(new ArrayList<Integer>());
  private final AgentControl control = TestControls.newControl();
  private final Dictionary dictionary = new Dictionary();
  private TracerImpl tracer;
  private int adviceErrorsBefore;

  @BeforeEach
  void installTracer() {
    tracer =
        new TracerImpl(
            dictionary,
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                ended.add(tx);
              }
            },
            control,
            new TxIdGenerator(),
            1000,
            100);
    // The real tracer behind a proxy that also records the failure kind of recordExtStat.
    control.installTracer(
        (Tracer)
            Proxy.newProxyInstance(
                Tracer.class.getClassLoader(),
                new Class<?>[] {Tracer.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args)
                      throws Throwable {
                    if (method.getName().equals("recordExtStat")) {
                      failureKinds.add((Integer) args[2]);
                    }
                    try {
                      return method.invoke(tracer, args);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                }));
    adviceErrorsBefore =
        io.argos.agent.bootstrap.ModuleHealth.errors(HttpClientJdkModule.MODULE_ID);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(io.argos.agent.bootstrap.ModuleHealth.errors(HttpClientJdkModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(adviceErrorsBefore);
  }

  private static JdkLikeConnection open(String url) throws Exception {
    return ADVISED.getConstructor(URL.class).newInstance(new URL(url));
  }

  private Transaction inTransaction(JdkLikeConnection connection, boolean expectFailure)
      throws Exception {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/pay/{id}", System.nanoTime());
    IOException seen = null;
    try {
      connection.getResponseCode();
      connection.getInputStream();
    } catch (IOException e) {
      seen = e;
    }
    assertThat(seen != null).isEqualTo(expectFailure);
    if (connection.failure != null) {
      assertThat(seen).isSameAs(connection.failure);
    }
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
    return ended.get(0);
  }

  @Test
  @DisplayName(
      "200: one HTTP step, host name, detail without query, traceparent of the transaction")
  void success() throws Exception {
    JdkLikeConnection connection = open("http://127.0.0.1:18080/pay/1?card=4111111111111111");
    connection.setRequestMethod("GET");
    Transaction tx = inTransaction(connection, false);
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.HTTP);
    assertThat(dictionary.text(steps.nameId(0))).isEqualTo("127.0.0.1");
    assertThat(steps.detail(0)).isEqualTo("GET 127.0.0.1:18080/pay/1");
    assertThat(steps.status(0)).isEqualTo(200);
    assertThat(steps.error(0)).isFalse();
    assertThat(steps.elapsedNanos(0)).isGreaterThanOrEqualTo(0L);
    assertThat(connection.exchanges).isEqualTo(1);
    TraceContext ctx = tx.context();
    assertThat(connection.sentTraceparent)
        .startsWith("00-" + TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo) + "-")
        .endsWith("-01")
        .hasSize(55);
    assertThat(failureKinds).containsExactly(FailureKind.NONE);
  }

  @Test
  @DisplayName("an existing traceparent is kept; a connected request gets no header")
  void headerRules() throws Exception {
    JdkLikeConnection own = open("http://example.test/a");
    own.setRequestProperty(
        "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    inTransaction(own, false);
    assertThat(own.sentTraceparent)
        .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    ended.clear();

    JdkLikeConnection connected = open("http://example.test/b");
    connected.connect();
    Transaction tx = inTransaction(connected, false);
    assertThat(connected.sentTraceparent).isNull();
    assertThat(tx.steps().size()).isEqualTo(1);
    assertThat(tx.steps().detail(0)).isEqualTo("GET example.test/b");
  }

  @Test
  @DisplayName(
      "path parameters such as ;jsessionid are dropped from the detail, as on the inbound side")
  void pathParameters() throws Exception {
    String[][] cases = {
      {"http://127.0.0.1:18080/pay;jsessionid=ABC/1?card=1", "GET 127.0.0.1:18080/pay/1"},
      {"http://127.0.0.1:18080/pay;jsessionid=ABC", "GET 127.0.0.1:18080/pay"},
      {"http://127.0.0.1:18080/;x=1", "GET 127.0.0.1:18080/"},
      {"http://127.0.0.1:18080/a;v=1;w=2/b;x/c", "GET 127.0.0.1:18080/a/b/c"},
    };
    for (String[] c : cases) {
      ended.clear();
      Transaction tx = inTransaction(open(c[0]), false);
      assertThat(tx.steps().size()).isEqualTo(1);
      assertThat(tx.steps().detail(0)).as(c[0]).isEqualTo(c[1]);
    }
  }

  @Test
  @DisplayName("500: status recorded, HTTP_5XX, the application's exception unchanged")
  void serverError() throws Exception {
    JdkLikeConnection connection = open("http://127.0.0.1:18080/status/500");
    connection.answer = 500;
    Transaction tx = inTransaction(connection, true);
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.status(0)).isEqualTo(500);
    assertThat(steps.error(0)).isTrue();
    assertThat(failureKinds).containsExactly(FailureKind.HTTP_5XX);
  }

  @Test
  @DisplayName("404: a response, not a failure class")
  void notFound() throws Exception {
    JdkLikeConnection connection = open("http://127.0.0.1:18080/missing");
    connection.answer = 404;
    Transaction tx = inTransaction(connection, true);
    assertThat(tx.steps().status(0)).isEqualTo(404);
    assertThat(tx.steps().error(0)).isFalse();
    assertThat(failureKinds).containsExactly(FailureKind.NONE);
  }

  @Test
  @DisplayName("no response: TIMEOUT, CONNECT_FAIL, OTHER")
  void failureClasses() throws Exception {
    IOException[] failures = {
      new SocketTimeoutException("Read timed out"),
      new ConnectException("Connection refused"),
      new IOException("closed")
    };
    for (IOException failure : failures) {
      ended.clear();
      JdkLikeConnection connection = open("http://127.0.0.1:1/x");
      connection.failure = failure;
      Transaction tx = inTransaction(connection, true);
      assertThat(tx.steps().size()).isEqualTo(1);
      assertThat(tx.steps().error(0)).isTrue();
      assertThat(tx.steps().status(0)).isZero();
    }
    assertThat(failureKinds)
        .containsExactly(FailureKind.TIMEOUT, FailureKind.CONNECT_FAIL, FailureKind.OTHER);
  }

  @Test
  @DisplayName("outside a transaction: no step, no header")
  void outsideATransaction() throws Exception {
    JdkLikeConnection connection = open("http://127.0.0.1:18080/a");
    assertThat(connection.getResponseCode()).isEqualTo(200);
    assertThat(connection.sentTraceparent).isNull();
    assertThat(failureKinds).isEmpty();
    assertThat(ended).isEmpty();
  }
}
