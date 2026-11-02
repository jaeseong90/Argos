/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The request boundary state machine (10 §7.1) of {@link ServletHelperJavax} and {@link
 * ServletHelperJakarta}, against the real tracer. Each test drives the helpers the way the inlined
 * advice does: enter on every {@code doFilter}/{@code service} call, exit in reverse order, the
 * scope of {@code activate} around an ASYNC dispatch on another thread.
 */
@Tag("req:F-A28")
class ServletHelperTest {
  /** The static helper methods of one namespace. */
  abstract static class Helper {
    final FakeHttp.Api api;

    Helper(FakeHttp.Api api) {
      this.api = api;
    }

    abstract TraceContext enter(Object request);

    abstract void exit(TraceContext context, Object request, Object response, Throwable error);

    /** A ServletException of this namespace wrapping {@code cause}. */
    abstract Throwable wrap(Throwable cause);

    @Override
    public String toString() {
      return api.toString();
    }
  }

  static List<Helper> helpers() {
    return Arrays.<Helper>asList(
        new Helper(FakeHttp.JAVAX) {
          @Override
          TraceContext enter(Object request) {
            return ServletHelperJavax.enter(request);
          }

          @Override
          void exit(TraceContext context, Object request, Object response, Throwable error) {
            ServletHelperJavax.exit(context, request, response, error);
          }

          @Override
          Throwable wrap(Throwable cause) {
            return new javax.servlet.ServletException("Request processing failed", cause);
          }
        },
        new Helper(FakeHttp.JAKARTA) {
          @Override
          TraceContext enter(Object request) {
            return ServletHelperJakarta.enter(request);
          }

          @Override
          void exit(TraceContext context, Object request, Object response, Throwable error) {
            ServletHelperJakarta.exit(context, request, response, error);
          }

          @Override
          Throwable wrap(Throwable cause) {
            return new jakarta.servlet.ServletException("Request processing failed", cause);
          }
        });
  }

  /** Request methods that would consume the body or create a session (D-26). */
  static final List<String> FORBIDDEN =
      Arrays.asList(
          "getParameter",
          "getParameterMap",
          "getParameterNames",
          "getParameterValues",
          "getReader",
          "getInputStream",
          "getSession",
          "getParts",
          "getPart");

  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private TracerImpl tracer;

  @BeforeEach
  void installTracer() {
    tracer =
        new TracerImpl(
            new Dictionary(),
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
    control.installTracer(tracer);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName("REQUEST: filter + servlet = one transaction, ended by the outermost exit")
  void nestedCallsMakeOneTransaction(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy);
    assertThat(filter).isNotNull();
    assertThat(tracer.current()).isSameAs(filter);
    assertThat(request.attributes).containsEntry(ServletHelperCommon.TX_ATTRIBUTE, filter);
    TraceContext servlet = helper.enter(request.proxy);
    assertThat(servlet).isSameAs(filter);
    assertThat(filter.dispatchDepth).isEqualTo(2);

    helper.exit(servlet, request.proxy, response.proxy, null);
    assertThat(ended).isEmpty();
    helper.exit(filter, request.proxy, response.proxy, null);

    assertThat(ended).hasSize(1);
    Transaction tx = ended.get(0);
    assertThat(filter.status).isEqualTo(200);
    assertThat(filter.error).isFalse();
    // No module fixed a name: the F-A29 fallback.
    assertThat(filter.serviceName).isEqualTo("GET /api/orders/{*}");
    assertThat(tx.httpMethod()).isEqualTo("GET");
    assertThat(tx.path()).isEqualTo("/api/orders/1");
    assertThat(tx.remoteIp()).isEqualTo("192.0.2.0");
    assertThat(tx.elapsedNanos()).isGreaterThanOrEqualTo(0);
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
    assertThat(tracer.current()).isNull();
    assertThat(request.calls).doesNotContainAnyElementsOf(FORBIDDEN);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName("FORWARD and INCLUDE dispatches join the transaction; only REQUEST ends it")
  void forwardJoins(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.uri = "/api/forward";
    TraceContext outer = helper.enter(request.proxy);
    for (String dispatch : Arrays.asList("FORWARD", "INCLUDE")) {
      request.dispatcher = dispatch;
      TraceContext inner = helper.enter(request.proxy);
      assertThat(inner).isSameAs(outer);
      helper.exit(inner, request.proxy, response.proxy, null);
      assertThat(ended).isEmpty();
    }
    request.dispatcher = "REQUEST";
    helper.exit(outer, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(outer.serviceName).isEqualTo("GET /api/forward");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @Tag("req:D-21")
  @DisplayName("C-48: the container's ERROR dispatch after the end is not recorded, 404 kept")
  void errorDispatchAfterEndIsIgnored(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.uri = "/api/not-found-x";
    TraceContext context = helper.enter(request.proxy);
    response.status = 404;
    helper.exit(context, request.proxy, response.proxy, null);

    request.dispatcher = "ERROR";
    request.uri = "/error";
    assertThat(helper.enter(request.proxy)).isNull();
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(404);
    assertThat(context.serviceName).isEqualTo("GET /api/not-found-x");
    assertThat(tracer.current()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-21")
  @Tag("req:SEC-36")
  @DisplayName("D-21: an escaped exception with status < 400 is 500; message masked")
  void escapedExceptionIs500(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(request.proxy);
    helper.exit(
        context,
        request.proxy,
        response.proxy,
        new IllegalStateException("order 42 not found for kim@example.test"));
    assertThat(context.status).isEqualTo(500);
    assertThat(context.error).isTrue();
    assertThat(ended.get(0).errorClass()).isEqualTo("java.lang.IllegalStateException");
    assertThat(ended.get(0).errorMessage()).isEqualTo("order 42 not found for <email>");

    // A ServletException wrapper (Spring: "Request processing failed") records its root cause.
    FakeHttp.Request wrapped = FakeHttp.Request.of(helper.api);
    TraceContext second = helper.enter(wrapped.proxy);
    helper.exit(second, wrapped.proxy, response.proxy, helper.wrap(new ArithmeticException("x")));
    assertThat(second.status).isEqualTo(500);
    assertThat(ended.get(1).errorClass()).isEqualTo("java.lang.ArithmeticException");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-21")
  @DisplayName("D-21: the response status is final (sendError 503 kept, 4xx with exception kept)")
  void statusIsFinal(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext first = helper.enter(request.proxy);
    response.status = 503;
    helper.exit(first, request.proxy, response.proxy, null);
    assertThat(first.status).isEqualTo(503);
    assertThat(first.error).isTrue();

    FakeHttp.Request second = FakeHttp.Request.of(helper.api);
    TraceContext badRequest = helper.enter(second.proxy);
    response.status = 400;
    helper.exit(badRequest, second.proxy, response.proxy, new IllegalArgumentException("x"));
    assertThat(badRequest.status).isEqualTo(400);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName("excluded requests: no transaction, counted once, later dispatches ignored")
  void excludedRequests(Helper helper) {
    FakeHttp.Request health = FakeHttp.Request.of(helper.api);
    health.uri = "/actuator/health";
    assertThat(helper.enter(health.proxy)).isNull();
    assertThat(health.attributes).containsEntry(ServletHelperCommon.IGNORED_ATTRIBUTE, true);
    assertThat(helper.enter(health.proxy)).isNull();
    health.dispatcher = "ERROR";
    assertThat(helper.enter(health.proxy)).isNull();
    assertThat(tracer.ignoredRequests("path")).isEqualTo(1);

    FakeHttp.Request preflight = FakeHttp.Request.of(helper.api);
    preflight.method = "OPTIONS";
    assertThat(helper.enter(preflight.proxy)).isNull();
    FakeHttp.Request script = FakeHttp.Request.of(helper.api);
    script.uri = "/static/app.js";
    assertThat(helper.enter(script.proxy)).isNull();
    assertThat(tracer.ignoredRequests("method")).isEqualTo(1);
    assertThat(tracer.ignoredRequests("extension")).isEqualTo(1);
    assertThat(ended).isEmpty();
    assertThat(tracer.current()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName("startAsync: the request thread drops the context; the last ASYNC dispatch ends it")
  void asyncDispatchEnds(Helper helper) throws Exception {
    final FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(request.proxy);
    request.asyncStarted = true;
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(ended).isEmpty();
    assertThat(context.asyncStarted).isTrue();
    assertThat(tracer.current()).isNull();
    assertThat(request.attributes).containsEntry(ServletHelperCommon.TX_ATTRIBUTE, context);

    final AtomicReference<TraceContext> onDispatch = new AtomicReference<TraceContext>();
    final AtomicReference<TraceContext> afterDispatch = new AtomicReference<TraceContext>();
    final Helper h = helper;
    Thread asyncThread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                request.dispatcher = "ASYNC";
                request.asyncStarted = false;
                response.status = 201;
                TraceContext joined = h.enter(request.proxy);
                Scope scope = tracer.activate(joined);
                onDispatch.set(tracer.current());
                scope.close();
                h.exit(joined, request.proxy, response.proxy, null);
                afterDispatch.set(tracer.current());
              }
            },
            "async-dispatch");
    asyncThread.start();
    asyncThread.join(10_000);
    assertThat(onDispatch.get()).isSameAs(context);
    assertThat(afterDispatch.get()).isNull();
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(201);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:F-A36")
  @DisplayName("context path: prefix in the provisional name, rules on the path within the app")
  void contextPath(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.contextPath = "/shop-a";
    request.uri = "/shop-a/api/orders/7";
    TraceContext context = helper.enter(request.proxy);
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(context.serviceName).isEqualTo("/shop-a GET /api/orders/{*}");
    assertThat(ended.get(0).path()).isEqualTo("/shop-a/api/orders/7");

    FakeHttp.Request health = FakeHttp.Request.of(helper.api);
    health.contextPath = "/shop-a";
    health.uri = "/shop-a/actuator/health";
    assertThat(helper.enter(health.proxy)).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @Tag("req:SEC-31")
  @DisplayName("capture.remote-ip=off: the remote address is never read")
  void remoteIpOff(Helper helper) {
    CaptureConfig d = CaptureConfig.DEFAULT;
    tracer.setCapture(
        new CaptureConfig(
            d.queryStringMode(),
            d.queryStringWhitelist(),
            d.headerWhitelist(),
            CaptureConfig.REMOTE_IP_OFF,
            d.trustedProxies(),
            d.userIdMode(),
            d.userIdSource(),
            d.sqlParamMode(),
            d.sqlParamWhitelistColumns(),
            d.errorMessageMax(),
            d.stackDepth()));
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    TraceContext context = helper.enter(request.proxy);
    helper.exit(context, request.proxy, FakeHttp.Response.of(helper.api).proxy, null);
    assertThat(request.calls).doesNotContain("getRemoteAddr");
    assertThat(ended.get(0).remoteIp()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("helpers")
  @DisplayName("not an HTTP request, or an anchor that already ended: nothing to join")
  void nothingToJoin(Helper helper) {
    assertThat(helper.enter(new Object())).isNull();
    assertThat(helper.enter(null)).isNull();
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    TraceContext context = helper.enter(request.proxy);
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(helper.enter(request.proxy)).isNull();
    assertThat(ended).hasSize(1);
  }
}
