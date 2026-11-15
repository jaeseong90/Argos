/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import io.argos.agent.instrumentation.servletasync.AsyncHelperJakarta;
import io.argos.agent.instrumentation.servletasync.AsyncHelperJavax;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 * scope of {@code activate} around an ASYNC dispatch on another thread, the async listener the
 * helper registers, and the flush advice of the container's response classes.
 */
@Tag("req:F-A28")
class ServletHelperTest {
  /** The static helper methods of one namespace. */
  abstract static class Helper {
    final FakeHttp.Api api;

    Helper(FakeHttp.Api api) {
      this.api = api;
    }

    abstract TraceContext enter(Object request, boolean servlet);

    abstract void exit(TraceContext context, Object request, Object response, Throwable error);

    /** A ServletException of this namespace wrapping {@code cause}. */
    abstract Throwable wrap(Throwable cause);

    abstract void onComplete(Object listener, Object event);

    abstract void onTimeout(Object listener, Object event);

    abstract void onError(Object listener, Object event);

    /**
     * The servlet-async module's hook at the exit of the container's {@code
     * Request.startAsync(request, response)} ({@code AsyncHelperJavax/Jakarta.started}).
     */
    abstract void startAsync(Object containerRequest, Object response);

    @Override
    public String toString() {
      return api.toString();
    }
  }

  static List<Helper> helpers() {
    return Arrays.<Helper>asList(
        new Helper(FakeHttp.JAVAX) {
          @Override
          TraceContext enter(Object request, boolean servlet) {
            return ServletHelperJavax.enter(request, servlet);
          }

          @Override
          void exit(TraceContext context, Object request, Object response, Throwable error) {
            ServletHelperJavax.exit(context, request, response, error);
          }

          @Override
          Throwable wrap(Throwable cause) {
            return new javax.servlet.ServletException("Request processing failed", cause);
          }

          @Override
          void onComplete(Object listener, Object event) {
            try {
              ((javax.servlet.AsyncListener) listener).onComplete((javax.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void onTimeout(Object listener, Object event) {
            try {
              ((javax.servlet.AsyncListener) listener).onTimeout((javax.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void onError(Object listener, Object event) {
            try {
              ((javax.servlet.AsyncListener) listener).onError((javax.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void startAsync(Object containerRequest, Object response) {
            AsyncHelperJavax.started(containerRequest, response);
          }
        },
        new Helper(FakeHttp.JAKARTA) {
          @Override
          TraceContext enter(Object request, boolean servlet) {
            return ServletHelperJakarta.enter(request, servlet);
          }

          @Override
          void exit(TraceContext context, Object request, Object response, Throwable error) {
            ServletHelperJakarta.exit(context, request, response, error);
          }

          @Override
          Throwable wrap(Throwable cause) {
            return new jakarta.servlet.ServletException("Request processing failed", cause);
          }

          @Override
          void onComplete(Object listener, Object event) {
            try {
              ((jakarta.servlet.AsyncListener) listener)
                  .onComplete((jakarta.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void onTimeout(Object listener, Object event) {
            try {
              ((jakarta.servlet.AsyncListener) listener)
                  .onTimeout((jakarta.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void onError(Object listener, Object event) {
            try {
              ((jakarta.servlet.AsyncListener) listener)
                  .onError((jakarta.servlet.AsyncEvent) event);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }

          @Override
          void startAsync(Object containerRequest, Object response) {
            AsyncHelperJakarta.started(containerRequest, response);
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

  // --- REQUEST, FORWARD, INCLUDE, ERROR (T-AG-011a) ------------------------------------------

  @ParameterizedTest(name = "T-AG-011a REQUEST filter + servlet = one transaction ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName("T-AG-011a REQUEST: filter + servlet = one transaction, ended by the outermost exit")
  void nestedCallsMakeOneTransaction(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    assertThat(filter).isNotNull();
    assertThat(tracer.current()).isSameAs(filter);
    assertThat(request.attributes).containsEntry(ServletHelperCommon.TX_ATTRIBUTE, filter);
    TraceContext servlet = helper.enter(request.proxy, true);
    assertThat(servlet).isSameAs(filter);
    assertThat(filter.dispatchDepth).isEqualTo(2);

    helper.exit(servlet, request.proxy, response.proxy, null);
    assertThat(ended).isEmpty();
    helper.exit(filter, request.proxy, response.proxy, null);

    assertThat(ended).hasSize(1);
    Transaction tx = ended.get(0);
    assertThat(filter.status).isEqualTo(200);
    assertThat(filter.error).isFalse();
    assertThat(filter.dispatchCount).isEqualTo(1);
    // No module fixed a name: the F-A29 fallback.
    assertThat(filter.serviceName).isEqualTo("GET /api/orders/{*}");
    assertThat(tx.httpMethod()).isEqualTo("GET");
    assertThat(tx.path()).isEqualTo("/api/orders/1");
    assertThat(tx.remoteIp()).isEqualTo("192.0.2.0");
    assertThat(tx.elapsedNanos()).isGreaterThanOrEqualTo(0);
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
    assertThat(tracer.current()).isNull();
    assertThat(request.calls).doesNotContainAnyElementsOf(FORBIDDEN).doesNotContain("getSession");
  }

  @ParameterizedTest(name = "T-AG-011a FORWARD and INCLUDE join, dispatch_count 3 ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-011a FORWARD and INCLUDE dispatches join the transaction; only REQUEST ends it")
  void forwardJoins(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.uri = "/api/forward";
    TraceContext outer = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    for (String dispatch : Arrays.asList("FORWARD", "INCLUDE")) {
      request.dispatcher = dispatch;
      TraceContext filter = helper.enter(request.proxy, false);
      TraceContext target = helper.enter(request.proxy, true);
      assertThat(filter).isSameAs(outer);
      assertThat(target).isSameAs(outer);
      helper.exit(target, request.proxy, response.proxy, null);
      helper.exit(filter, request.proxy, response.proxy, null);
      assertThat(ended).isEmpty();
    }
    request.dispatcher = "REQUEST";
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(outer, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(outer.serviceName).isEqualTo("GET /api/forward");
    assertThat(outer.dispatchCount).isEqualTo(3);
  }

  @ParameterizedTest(name = "T-AG-011a a dispatch counts once per request object ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-011a dispatch_count: a servlet that hands the FORWARD request on to another servlet"
          + " (JspServlet to the JSP page) counts once (2); a nested forward with a new wrapper"
          + " counts (3); consecutive ASYNC dispatches with the same request object count each")
  void dispatchCountsOncePerRequestObject(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.uri = "/api/forward-jsp";
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    request.dispatcher = "FORWARD";
    Object forward = request.wrapper();
    TraceContext jspServlet = helper.enter(forward, true);
    // JspServletWrapper calls Servlet.service(ServletRequest, ServletResponse) of the page with the
    // same request object: HttpServlet.service is entered a second time.
    TraceContext page = helper.enter(forward, true);
    assertThat(page).isSameAs(filter);
    assertThat(filter.dispatchCount).isEqualTo(2);
    // A forward from the page: the container wraps the request anew.
    Object nested = request.wrapper();
    TraceContext nestedJspServlet = helper.enter(nested, true);
    TraceContext nestedPage = helper.enter(nested, true);
    assertThat(filter.dispatchCount).isEqualTo(3);
    helper.exit(nestedPage, nested, response.proxy, null);
    helper.exit(nestedJspServlet, nested, response.proxy, null);
    helper.exit(page, forward, response.proxy, null);
    helper.exit(jspServlet, forward, response.proxy, null);
    request.dispatcher = "REQUEST";
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(filter.dispatchCount).isEqualTo(3);

    // Two ASYNC dispatches with the same request object, each handed on to a second servlet.
    FakeHttp.Request async = FakeHttp.Request.of(helper.api);
    FakeHttp.Response asyncResponse = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, async, asyncResponse);
    async.dispatcher = "ASYNC";
    for (int round = 1; round <= 2; round++) {
      // The first ASYNC dispatch starts async again; the second completes the request.
      async.asyncStarted = round == 1;
      TraceContext asyncFilter = helper.enter(async.proxy, false);
      TraceContext asyncServlet = helper.enter(async.proxy, true);
      TraceContext handedOn = helper.enter(async.proxy, true);
      assertThat(handedOn).isSameAs(context);
      assertThat(context.dispatchCount).as("round %s", round).isEqualTo(1 + round);
      helper.exit(handedOn, async.proxy, asyncResponse.proxy, null);
      helper.exit(asyncServlet, async.proxy, asyncResponse.proxy, null);
      helper.exit(asyncFilter, async.proxy, asyncResponse.proxy, null);
    }
    assertThat(context.isEnded()).isTrue();
    assertThat(context.dispatchCount).isEqualTo(3);
    assertThat(ended).hasSize(2);
  }

  @ParameterizedTest(name = "T-AG-011a C-48 ERROR dispatch after the end ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @Tag("req:D-21")
  @DisplayName(
      "T-AG-011a C-48: the container's ERROR dispatch after the end is not recorded, 404 kept,"
          + " counted once as unanchored")
  void errorDispatchAfterEndIsIgnored(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.uri = "/api/not-found-x";
    TraceContext context = helper.enter(request.proxy, false);
    response.status = 404;
    helper.exit(context, request.proxy, response.proxy, null);

    request.dispatcher = "ERROR";
    request.uri = "/error";
    assertThat(helper.enter(request.proxy, false)).isNull();
    assertThat(helper.enter(request.proxy, true)).isNull();
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(404);
    assertThat(context.serviceName).isEqualTo("GET /api/not-found-x");
    assertThat(tracer.current()).isNull();
    assertThat(tracer.counters().transactionsUnanchored()).isEqualTo(1);
  }

  @ParameterizedTest(name = "T-AG-011a T-ACC-21a ERROR dispatch inside the request ({0})")
  @MethodSource("helpers")
  @Tag("req:D-21")
  @DisplayName(
      "T-AG-011a/T-ACC-21a an ERROR dispatch inside the request joins; its final status is kept")
  void errorDispatchInsideJoins(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext outer = helper.enter(request.proxy, false);
    request.dispatcher = "ERROR";
    TraceContext error = helper.enter(request.proxy, true);
    assertThat(error).isSameAs(outer);
    response.status = 503;
    helper.exit(error, request.proxy, response.proxy, null);
    request.dispatcher = "REQUEST";
    helper.exit(outer, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(outer.status).isEqualTo(503);
    assertThat(outer.error).isTrue();
    assertThat(outer.dispatchCount).isEqualTo(2);
    assertThat(tracer.counters().transactionsUnanchored()).isZero();
  }

  @ParameterizedTest(name = "T-AG-011a leaked context ended as 599 ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-011a a context left on the thread by an earlier request is ended as leaked (599,"
          + " flag, counter) when the next REQUEST begins")
  void leakedContextIsEnded(Helper helper) {
    TraceContext leaked = tracer.begin(Kind.HTTP, "GET /leaked", System.nanoTime());
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(request.proxy, false);
    assertThat(leaked.isEnded()).isTrue();
    assertThat(leaked.status).isEqualTo(TracerImpl.LEAKED_STATUS);
    assertThat(leaked.error).isTrue();
    assertThat(leaked.hasFlags(TraceContext.FLAG_LEAKED)).isTrue();
    assertThat(tracer.counters().transactionsLeaked()).isEqualTo(1);
    assertThat(tracer.current()).isSameAs(context);
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(2);
    assertThat(context.hasFlags(TraceContext.FLAG_LEAKED)).isFalse();
  }

  // --- D-21 (T-ACC-21a) --------------------------------------------------------------------

  @ParameterizedTest(name = "T-ACC-21a escaped exception is 500 ({0})")
  @MethodSource("helpers")
  @Tag("req:D-21")
  @Tag("req:SEC-36")
  @DisplayName("T-ACC-21a D-21: an escaped exception with status < 400 is 500; message masked")
  void escapedExceptionIs500(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(request.proxy, false);
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
    TraceContext second = helper.enter(wrapped.proxy, false);
    helper.exit(second, wrapped.proxy, response.proxy, helper.wrap(new ArithmeticException("x")));
    assertThat(second.status).isEqualTo(500);
    assertThat(ended.get(1).errorClass()).isEqualTo("java.lang.ArithmeticException");
  }

  @ParameterizedTest(name = "T-ACC-21a the response status is final ({0})")
  @MethodSource("helpers")
  @Tag("req:D-21")
  @DisplayName(
      "T-ACC-21a D-21: the response status is final (sendError 503 kept, 4xx with exception kept)")
  void statusIsFinal(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext first = helper.enter(request.proxy, false);
    response.status = 503;
    helper.exit(first, request.proxy, response.proxy, null);
    assertThat(first.status).isEqualTo(503);
    assertThat(first.error).isTrue();

    FakeHttp.Request second = FakeHttp.Request.of(helper.api);
    TraceContext badRequest = helper.enter(second.proxy, false);
    response.status = 400;
    helper.exit(badRequest, second.proxy, response.proxy, new IllegalArgumentException("x"));
    assertThat(badRequest.status).isEqualTo(400);
  }

  // --- exclusion (T-AG-012a) ---------------------------------------------------------------

  @ParameterizedTest(name = "T-AG-012a excluded requests ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName(
      "T-AG-012a excluded requests: no transaction, counted once, later dispatches ignored and"
          + " not counted as unanchored")
  void excludedRequests(Helper helper) {
    FakeHttp.Request health = FakeHttp.Request.of(helper.api);
    health.uri = "/actuator/health";
    assertThat(helper.enter(health.proxy, false)).isNull();
    assertThat(health.attributes).containsEntry(ServletHelperCommon.IGNORED_ATTRIBUTE, true);
    assertThat(helper.enter(health.proxy, true)).isNull();
    health.dispatcher = "ERROR";
    assertThat(helper.enter(health.proxy, true)).isNull();
    assertThat(tracer.ignoredRequests("path")).isEqualTo(1);
    assertThat(tracer.counters().transactionsUnanchored()).isZero();

    FakeHttp.Request preflight = FakeHttp.Request.of(helper.api);
    preflight.method = "OPTIONS";
    assertThat(helper.enter(preflight.proxy, false)).isNull();
    FakeHttp.Request script = FakeHttp.Request.of(helper.api);
    script.uri = "/static/app.js";
    assertThat(helper.enter(script.proxy, false)).isNull();
    assertThat(tracer.ignoredRequests("method")).isEqualTo(1);
    assertThat(tracer.ignoredRequests("extension")).isEqualTo(1);
    assertThat(ended).isEmpty();
    assertThat(tracer.current()).isNull();
  }

  @ParameterizedTest(name = "T-AG-012a 100 noise requests: 0 transactions, 100 counted ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A23")
  @Tag("req:D-19")
  void noiseIsCountedNotRecorded(Helper helper) {
    List<String> noise = Arrays.asList("/actuator/health", "/healthz", "/favicon.ico", "/a.css");
    for (int i = 0; i < 100; i++) {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      request.uri = noise.get(i % noise.size());
      TraceContext filter = helper.enter(request.proxy, false);
      TraceContext servlet = helper.enter(request.proxy, true);
      assertThat(filter).isNull();
      assertThat(servlet).isNull();
    }
    assertThat(ended).isEmpty();
    assertThat(tracer.ignoredRequests("path") + tracer.ignoredRequests("extension")).isEqualTo(100);
    assertThat(tracer.ignoredRequests("extension")).isEqualTo(25);
  }

  // --- async (T-AG-016a) -------------------------------------------------------------------

  @ParameterizedTest(name = "T-AG-016a startAsync then ASYNC dispatch ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a startAsync: the request thread drops the context, a listener is registered, the"
          + " ASYNC dispatch ends it once (listener after it changes nothing)")
  void asyncDispatchEnds(Helper helper) throws Exception {
    final FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    assertThat(ended).isEmpty();
    assertThat(context.asyncStarted).isTrue();
    assertThat(tracer.current()).isNull();
    assertThat(request.attributes).containsEntry(ServletHelperCommon.TX_ATTRIBUTE, context);
    assertThat(request.listeners).hasSize(1);

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
                TraceContext joined = h.enter(request.proxy, true);
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
    assertThat(context.dispatchCount).isEqualTo(2);
    assertThat(context.streaming).isFalse();

    helper.onComplete(
        request.listeners.get(0), helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).hasSize(1);
  }

  @ParameterizedTest(name = "T-AG-016a complete() without a dispatch ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a AsyncContext.complete() without a dispatch: onComplete ends with the response"
          + " status and removes the anchor")
  void listenerCompletes(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    response.status = 202;
    helper.onComplete(
        request.listeners.get(0), helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(202);
    assertThat(context.error).isFalse();
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
  }

  @ParameterizedTest(name = "T-AG-016a onTimeout keeps the status the container sets ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @Tag("req:D-21")
  @DisplayName(
      "T-AG-016a onTimeout: the end waits for the container; its ERROR dispatch (isAsyncStarted"
          + " still true, Tomcat's ERROR state) ends with its status (503 kept), registers no second"
          + " listener, and the onComplete after it changes nothing; onComplete without an ERROR"
          + " dispatch and a status below 400 is 500")
  void timeoutKeepsContainerStatus(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    Object listener = request.listeners.get(0);
    helper.onTimeout(listener, helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).isEmpty();
    assertThat(context.asyncTimedOut).isTrue();
    // Tomcat's ERROR dispatch to the error page after the timeout: the request stays in async mode
    // (AsyncStateMachine ERROR: isAsyncStarted() is true) while the dispatch runs.
    assertThat(request.asyncStarted).isTrue();
    request.dispatcher = "ERROR";
    Object errorRequest = request.wrapper();
    TraceContext filter = helper.enter(errorRequest, false);
    TraceContext error = helper.enter(errorRequest, true);
    assertThat(filter).isSameAs(context);
    assertThat(error).isSameAs(context);
    response.status = 503;
    helper.exit(error, errorRequest, response.proxy, null);
    assertThat(ended).isEmpty();
    helper.exit(filter, errorRequest, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(503);
    assertThat(context.dispatchCount).isEqualTo(2);
    assertThat(request.listeners).as("no second listener after the ERROR dispatch").hasSize(1);
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
    helper.onComplete(listener, helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(503);

    FakeHttp.Request second = FakeHttp.Request.of(helper.api);
    FakeHttp.Response secondResponse = FakeHttp.Response.of(helper.api);
    TraceContext other = suspended(helper, second, secondResponse);
    Object otherListener = second.listeners.get(0);
    helper.onTimeout(otherListener, helper.api.event(second.proxy, secondResponse.proxy, null));
    helper.onComplete(otherListener, helper.api.event(second.proxy, secondResponse.proxy, null));
    assertThat(ended).hasSize(2);
    assertThat(other.status).isEqualTo(500);
    assertThat(other.error).isTrue();
  }

  @ParameterizedTest(name = "T-AG-016a onError ends with 500 and the exception ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName("T-AG-016a onError ends with 500 (status below 400) and records the exception")
  void listenerError(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    helper.onError(
        request.listeners.get(0),
        helper.api.event(request.proxy, response.proxy, new IllegalStateException("broken")));
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(500);
    assertThat(ended.get(0).errorClass()).isEqualTo("java.lang.IllegalStateException");
  }

  @ParameterizedTest(name = "T-AG-016a end-once race onComplete vs onError ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-016a end once: onComplete and onError (and onTimeout) racing on two threads end each"
          + " transaction exactly once (200 rounds)")
  void listenerRaceEndsOnce(final Helper helper) throws Exception {
    for (int round = 0; round < 200; round++) {
      final FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext context = suspended(helper, request, response);
      final Object listener = request.listeners.get(0);
      final Object event = helper.api.event(request.proxy, response.proxy, null);
      final Object errorEvent =
          helper.api.event(request.proxy, response.proxy, new IllegalStateException("x"));
      final CountDownLatch start = new CountDownLatch(1);
      Thread complete =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  helper.onComplete(listener, event);
                }
              });
      Thread error =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  helper.onTimeout(listener, event);
                  helper.onError(listener, errorEvent);
                }
              });
      complete.start();
      error.start();
      start.countDown();
      complete.join(10_000);
      error.join(10_000);
      assertThat(context.isEnded()).isTrue();
      assertThat(ended).as("round " + round).hasSize(round + 1);
    }
  }

  @ParameterizedTest(name = "T-AG-016a end-once race ASYNC dispatch vs onComplete ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-016a end once: the ASYNC dispatch exit and onComplete racing end once (200 rounds)")
  void dispatchAndListenerRaceEndsOnce(final Helper helper) throws Exception {
    for (int round = 0; round < 200; round++) {
      final FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext context = suspended(helper, request, response);
      final Object listener = request.listeners.get(0);
      final Object event = helper.api.event(request.proxy, response.proxy, null);
      request.dispatcher = "ASYNC";
      request.asyncStarted = false;
      final TraceContext joined = helper.enter(request.proxy, true);
      final CountDownLatch start = new CountDownLatch(1);
      Thread dispatch =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  helper.exit(joined, request.proxy, response.proxy, null);
                }
              });
      Thread complete =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  await(start);
                  helper.onComplete(listener, event);
                }
              });
      dispatch.start();
      complete.start();
      start.countDown();
      dispatch.join(10_000);
      complete.join(10_000);
      assertThat(context.isEnded()).isTrue();
      assertThat(ended).as("round " + round).hasSize(round + 1);
    }
  }

  @ParameterizedTest(name = "T-AG-016a servlet-async disabled: no listener ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-016a instrumentation.disabled servlet-async: no listener; the ASYNC dispatch still"
          + " ends the request (WO-02 behaviour)")
  void noListenerWhenServletAsyncDisabled(Helper helper) {
    control.setModuleState(
        ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    try {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext context = suspended(helper, request, response);
      assertThat(request.listeners).isEmpty();
      request.dispatcher = "ASYNC";
      request.asyncStarted = false;
      TraceContext joined = helper.enter(request.proxy, true);
      helper.exit(joined, request.proxy, response.proxy, null);
      assertThat(context.isEnded()).isTrue();
    } finally {
      control.setModuleState(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.ENABLED);
    }
  }

  @ParameterizedTest(name = "T-AG-016a servlet-async disabled: the ERROR dispatch ends ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @Tag("req:D-21")
  @DisplayName(
      "T-AG-016a instrumentation.disabled servlet-async: the container's ERROR dispatch after a"
          + " timeout (isAsyncStarted still true) ends the request with its status; it does not"
          + " wait for transaction.max-duration-ms")
  void errorDispatchEndsWithoutListener(Helper helper) {
    control.setModuleState(
        ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    try {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext context = suspended(helper, request, response);
      assertThat(request.listeners).isEmpty();
      request.dispatcher = "ERROR";
      response.status = 500;
      Object errorRequest = request.wrapper();
      TraceContext error = helper.enter(errorRequest, true);
      assertThat(error).isSameAs(context);
      helper.exit(error, errorRequest, response.proxy, null);
      assertThat(context.isEnded()).isTrue();
      assertThat(context.status).isEqualTo(500);
      assertThat(context.error).isTrue();
      assertThat(request.listeners).isEmpty();
      assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
    } finally {
      control.setModuleState(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.ENABLED);
    }
  }

  // --- streaming (T-AG-016a) ---------------------------------------------------------------

  @ParameterizedTest(name = "T-AG-016a first flush ends a streaming response ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a SSE / StreamingResponseBody: the first flush after startAsync ends the transaction"
          + " with streaming=true; later flushes and onComplete change nothing")
  void firstFlushEndsStreaming(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    Object stream = new Object();
    ServletHelperCommon.streamOpened(response.proxy, stream);
    ServletHelperCommon.flushed(stream);
    assertThat(ended).hasSize(1);
    assertThat(context.streaming).isTrue();
    assertThat(context.status).isEqualTo(200);
    ServletHelperCommon.flushed(response.proxy);
    helper.onComplete(
        request.listeners.get(0), helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).hasSize(1);
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
  }

  @ParameterizedTest(name = "T-AG-016a synchronous requests add no stream link ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a Tomcat 10.1/11 (discardFacades=true): synchronous requests that each open a new"
          + " stream, ask for it again and flush it add no ContextStore entry")
  void synchronousStreamsAddNoStoreEntry(Helper helper) {
    int requests = 200;
    // The streams and facades stay reachable, so that no weak key can vanish during the count.
    List<Object> reachable = new ArrayList<Object>();
    long before = ContextStore.totalEntries();
    for (int i = 0; i < requests; i++) {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      Object stream = new Object();
      reachable.add(stream);
      reachable.add(response.proxy);
      TraceContext filter = helper.enter(request.proxy, false);
      TraceContext servlet = helper.enter(request.proxy, true);
      ServletHelperCommon.streamOpened(response.proxy, stream);
      ServletHelperCommon.streamOpened(response.proxy, stream);
      assertThat(filter.responseStream.get()).as("recorded on the transaction").isSameAs(stream);
      ServletHelperCommon.flushed(stream);
      ServletHelperCommon.flushed(response.proxy);
      assertThat(filter.isEnded()).isFalse();
      helper.exit(servlet, request.proxy, response.proxy, null);
      helper.exit(filter, request.proxy, response.proxy, null);
      assertThat(filter.isEnded()).isTrue();
      assertThat(filter.streaming).isFalse();
    }
    assertThat(ContextStore.totalEntries() - before)
        .as("ContextStore entries added by %d synchronous requests", requests)
        .isZero();
    assertThat(ended).hasSize(requests);
    assertThat(reachable).hasSize(2 * requests);
  }

  @ParameterizedTest(name = "T-AG-016a stream opened before startAsync ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a StreamingResponseBody order: the stream opened on the request thread before"
          + " startAsync and flushed from another thread ends the transaction at its first flush"
          + " with streaming=true, also while the request thread is still in the servlet; the"
          + " ASYNC dispatch that follows joins nothing and is not counted as unanchored")
  void streamOpenedBeforeStartAsync(Helper helper) throws Exception {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    // StreamingResponseBodyReturnValueHandler: outputMessage.getBody() (getOutputStream() on the
    // request thread) before startCallableProcessing.
    final Object stream = new Object();
    ServletHelperCommon.streamOpened(response.proxy, stream);
    // startAsync: the servlet-async module's hook (async mode, the facade mapped, the stream
    // recorded on the request thread linked to it).
    request.asyncStarted = true;
    helper.startAsync(request.proxy, response.proxy);
    assertThat(filter.asyncStarted).isTrue();
    // StreamingResponseBodyTask writes and flushes on the MVC task executor.
    Thread task =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                ServletHelperCommon.flushed(stream);
              }
            },
            "streaming-task");
    task.start();
    task.join(10_000);
    assertThat(ended).hasSize(1);
    assertThat(filter.streaming).isTrue();
    assertThat(filter.status).isEqualTo(200);
    ServletHelperCommon.flushed(stream);
    // The request thread leaves the servlet after the end: clean-up only.
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    // The request is still in async mode: the ended anchor stays (r1-new-3), so that the ASYNC
    // dispatch below joins nothing, exactly as when the flush comes after the exit; the
    // container's recycle removes it. Without async mode the exit removes it (T-AG-011a).
    assertThat(request.attributes).containsEntry(ServletHelperCommon.TX_ATTRIBUTE, filter);
    assertThat(request.listeners).isEmpty();
    assertThat(tracer.current()).isNull();
    // WebAsyncManager dispatches ASYNC when the task is done: no transaction, not unanchored.
    long unanchored = tracer.counters().transactionsUnanchored();
    request.dispatcher = "ASYNC";
    request.asyncStarted = false;
    Object asyncRequest = request.wrapper();
    assertThat(helper.enter(asyncRequest, false)).isNull();
    assertThat(helper.enter(asyncRequest, true)).isNull();
    assertThat(tracer.counters().transactionsUnanchored() - unanchored).isZero();
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.UNANCHORED_ATTRIBUTE);
    assertThat(ended).hasSize(1);
    assertThat(filter.streaming).isTrue();
    assertThat(tracer.current()).isNull();
  }

  @ParameterizedTest(name = "T-AG-016a stream linked at the suspending exit ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a servlet-async disabled (no startAsync hook): the stream recorded on the request"
          + " thread is linked at the suspending exit; its first flush from another thread, without"
          + " asking for the stream again, ends the transaction with streaming=true")
  void recordedStreamLinkedAtSuspend(Helper helper) throws Exception {
    control.setModuleState(
        ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    try {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext filter = helper.enter(request.proxy, false);
      TraceContext servlet = helper.enter(request.proxy, true);
      final Object stream = new Object();
      ServletHelperCommon.streamOpened(response.proxy, stream);
      assertThat(filter.responseStream.get()).isSameAs(stream);
      request.asyncStarted = true;
      ServletHelperCommon.flushed(stream);
      assertThat(filter.isEnded()).as("not async yet for the agent").isFalse();
      helper.exit(servlet, request.proxy, response.proxy, null);
      helper.exit(filter, request.proxy, response.proxy, null);
      assertThat(filter.isEnded()).isFalse();
      assertThat(request.listeners).isEmpty();
      Thread task =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  ServletHelperCommon.flushed(stream);
                }
              },
              "streaming-task");
      task.start();
      task.join(10_000);
      assertThat(ended).hasSize(1);
      assertThat(filter.streaming).isTrue();
      assertThat(filter.status).isEqualTo(200);
    } finally {
      control.setModuleState(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID, ModuleHealth.ENABLED);
    }
  }

  @ParameterizedTest(name = "T-AG-016a flush before startAsync does not end ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a Spring SSE: the flush that commits the response before startAsync does not end"
          + " the transaction; the first flush after startAsync (the stream asked for again on"
          + " another thread) does")
  void flushBeforeStartAsyncDoesNotEnd(Helper helper) throws Exception {
    final FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    final Object stream = new Object();
    // ResponseBodyEmitterReturnValueHandler (Spring 5): outputMessage.getBody() and flush().
    ServletHelperCommon.streamOpened(response.proxy, stream);
    ServletHelperCommon.flushed(stream);
    ServletHelperCommon.flushed(response.proxy);
    assertThat(filter.isEnded()).isFalse();
    request.asyncStarted = true;
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    assertThat(ended).isEmpty();
    assertThat(filter.streaming).isFalse();
    // The first event: ServletServerHttpResponse.flush() asks for the stream again and flushes it.
    Thread emitter =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                ServletHelperCommon.streamOpened(response.proxy, stream);
                ServletHelperCommon.flushed(stream);
              }
            },
            "sse-send");
    emitter.start();
    emitter.join(10_000);
    assertThat(ended).hasSize(1);
    assertThat(filter.streaming).isTrue();
    helper.onComplete(
        request.listeners.get(0), helper.api.event(request.proxy, response.proxy, null));
    assertThat(ended).hasSize(1);
  }

  @ParameterizedTest(name = "T-AG-016a flushes that are not streaming ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-016a a flush of a synchronous response, or inside the ASYNC dispatch that writes a"
          + " DeferredResult, does not end the transaction")
  void otherFlushesDoNotEnd(Helper helper) {
    FakeHttp.Request sync = FakeHttp.Request.of(helper.api);
    FakeHttp.Response syncResponse = FakeHttp.Response.of(helper.api);
    TraceContext plain = helper.enter(sync.proxy, false);
    ServletHelperCommon.flushed(syncResponse.proxy);
    assertThat(plain.isEnded()).isFalse();
    helper.exit(plain, sync.proxy, syncResponse.proxy, null);
    assertThat(plain.streaming).isFalse();

    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext deferred = suspended(helper, request, response);
    request.dispatcher = "ASYNC";
    request.asyncStarted = false;
    TraceContext joined = helper.enter(request.proxy, true);
    ServletHelperCommon.flushed(response.proxy);
    assertThat(deferred.isEnded()).isFalse();
    helper.exit(joined, request.proxy, response.proxy, null);
    assertThat(deferred.isEnded()).isTrue();
    assertThat(deferred.streaming).isFalse();
  }

  @ParameterizedTest(name = "T-AG-016a 101 upgrade ends with streaming ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A34")
  @DisplayName("T-AG-016a a 101 response (WebSocket upgrade) or an Upgrade header ends streaming")
  void upgradeIsStreaming(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(request.proxy, false);
    response.status = 101;
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(context.streaming).isTrue();
    assertThat(context.status).isEqualTo(101);

    FakeHttp.Request second = FakeHttp.Request.of(helper.api);
    FakeHttp.Response upgrade = FakeHttp.Response.of(helper.api);
    upgrade.headers.put("upgrade", "websocket");
    TraceContext other = helper.enter(second.proxy, false);
    helper.exit(other, second.proxy, upgrade.proxy, null);
    assertThat(other.streaming).isTrue();
  }

  // --- naming ------------------------------------------------------------------------------

  @ParameterizedTest(name = "T-AG-020a HttpServletMapping candidate ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A02")
  @Tag("req:F-A29")
  @DisplayName(
      "T-AG-020a Servlet 4+ HttpServletMapping: EXACT/PATH/EXTENSION name the service when no"
          + " module did; DEFAULT keeps the path fallback; a fixed name is kept")
  void mappingCandidate(Helper helper) {
    FakeHttp.Request plain = FakeHttp.Request.of(helper.api);
    plain.uri = "/items/42";
    plain.mappingPattern = "/items/*";
    plain.mappingMatch = "PATH";
    TraceContext first = helper.enter(plain.proxy, true);
    helper.exit(first, plain.proxy, FakeHttp.Response.of(helper.api).proxy, null);
    assertThat(first.serviceName).isEqualTo("GET /items/*");

    FakeHttp.Request dispatcher = FakeHttp.Request.of(helper.api);
    dispatcher.uri = "/missing/7";
    dispatcher.mappingPattern = "/";
    dispatcher.mappingMatch = "DEFAULT";
    TraceContext second = helper.enter(dispatcher.proxy, true);
    helper.exit(second, dispatcher.proxy, FakeHttp.Response.of(helper.api).proxy, null);
    assertThat(second.serviceName).isEqualTo("GET /missing/{*}");

    FakeHttp.Request spring = FakeHttp.Request.of(helper.api);
    spring.contextPath = "/shop-a";
    spring.uri = "/shop-a/api/orders/7";
    spring.mappingPattern = "*.do";
    spring.mappingMatch = "EXTENSION";
    TraceContext third = helper.enter(spring.proxy, true);
    assertThat(third.serviceName).as("the candidate from the start").isEqualTo("/shop-a GET *.do");
    tracer.setServiceName(third, "/shop-a GET /api/orders/{id}", true);
    helper.exit(third, spring.proxy, FakeHttp.Response.of(helper.api).proxy, null);
    assertThat(third.serviceName).isEqualTo("/shop-a GET /api/orders/{id}");
    // Read once, when the REQUEST dispatch began the transaction (r1-new-2); never again once a
    // module fixed the name.
    assertThat(spring.count("getHttpServletMapping")).isEqualTo(1);
  }

  @ParameterizedTest(name = "T-AG-011a context path prefix ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A36")
  @DisplayName(
      "T-AG-011a context path: prefix in the provisional name, rules on the path within the app")
  void contextPath(Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    request.contextPath = "/shop-a";
    request.uri = "/shop-a/api/orders/7";
    TraceContext context = helper.enter(request.proxy, false);
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(context.serviceName).isEqualTo("/shop-a GET /api/orders/{*}");
    assertThat(ended.get(0).path()).isEqualTo("/shop-a/api/orders/7");

    FakeHttp.Request health = FakeHttp.Request.of(helper.api);
    health.contextPath = "/shop-a";
    health.uri = "/shop-a/actuator/health";
    assertThat(helper.enter(health.proxy, false)).isNull();
  }

  @ParameterizedTest(name = "T-AG-011a nothing to join ({0})")
  @MethodSource("helpers")
  @DisplayName("T-AG-011a not an HTTP request, or an anchor that already ended: nothing to join")
  void nothingToJoin(Helper helper) {
    assertThat(helper.enter(new Object(), false)).isNull();
    assertThat(helper.enter(null, true)).isNull();
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    TraceContext context = helper.enter(request.proxy, false);
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(helper.enter(request.proxy, false)).isNull();
    request.dispatcher = "ASYNC";
    assertThat(helper.enter(request.proxy, true)).isNull();
    assertThat(ended).hasSize(1);
    assertThat(tracer.counters().transactionsUnanchored()).isZero();
  }

  /** A REQUEST dispatch whose outermost exit leaves the request in async mode. */
  private TraceContext suspended(
      Helper helper, FakeHttp.Request request, FakeHttp.Response response) {
    TraceContext context = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    request.asyncStarted = true;
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(context, request.proxy, response.proxy, null);
    return context;
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
