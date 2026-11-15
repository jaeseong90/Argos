/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.TraceClock;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.watch.MaxDurationEnforcer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The ends of an async request that do not come from a dispatch exit (10 §6.7, §7.1): an {@code
 * AsyncListener} event, the first flush of a streaming response and {@code
 * transaction.max-duration-ms}, and the transaction that one of them ends while the request thread
 * is still inside the REQUEST dispatch. Pins the WO-12 review (r1) items: the Servlet 4 mapping
 * names every such end, the end flags belong to the end that wins, the request thread drops the
 * binding of a transaction another thread ended, and the listener (a container callback, not an
 * inlined advice) never lets an exception out. Real core tracer; requests and responses are the
 * recording proxies of {@link FakeHttp} and the servlet API's own wrapper classes.
 */
@Tag("req:F-A34")
class AsyncEndTest {
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private final AgentCounters counters = new AgentCounters();
  private final ActiveRegistry registry = new ActiveRegistry(64);
  private TracerImpl tracer;

  static List<ServletHelperTest.Helper> helpers() {
    return ServletHelperTest.helpers();
  }

  @BeforeEach
  void installTracer() {
    tracer =
        TracerImpl.builder()
            .counters(counters)
            .control(control)
            .activeRegistry(registry)
            .sink(
                new TracerImpl.Sink() {
                  @Override
                  public void accept(Transaction tx) {
                    ended.add(tx);
                  }
                })
            .build();
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

  // --- the mapping names every async end (request-boundary-5, T-AG-020) ----------------------

  @ParameterizedTest(name = "T-AG-020a async servlet named by its mapping, whatever ends it ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A02")
  @DisplayName(
      "T-AG-020a a Spring-less async servlet mapped to /events/* is named 'GET /events/*' when"
          + " onComplete, the first flush or max-duration ends it")
  void mappingNamesEveryAsyncEnd(ServletHelperTest.Helper helper) {
    // onComplete (AsyncContext.complete() without a dispatch)
    FakeHttp.Request completed = eventsRequest(helper);
    FakeHttp.Response completedResponse = FakeHttp.Response.of(helper.api);
    TraceContext first = suspended(helper, completed, completedResponse);
    helper.onComplete(
        completed.listeners.get(0),
        helper.api.event(completed.proxy, completedResponse.proxy, null));
    assertThat(first.isEnded()).isTrue();
    assertThat(first.serviceName).isEqualTo("GET /events/*");

    // the first flush of a streaming response
    FakeHttp.Request streamed = eventsRequest(helper);
    FakeHttp.Response streamedResponse = FakeHttp.Response.of(helper.api);
    TraceContext second = suspended(helper, streamed, streamedResponse);
    ServletHelperCommon.flushed(streamedResponse.proxy);
    assertThat(second.isEnded()).isTrue();
    assertThat(second.streaming).isTrue();
    assertThat(second.serviceName).isEqualTo("GET /events/*");

    // transaction.max-duration-ms on the watch thread
    FakeHttp.Request hung = eventsRequest(helper);
    TraceContext third = suspended(helper, hung, FakeHttp.Response.of(helper.api));
    new MaxDurationEnforcer(registry, tracer, twoHoursAhead(), 3_600_000L).run();
    assertThat(third.isEnded()).isTrue();
    assertThat(third.timedOut).isTrue();
    assertThat(third.serviceName).isEqualTo("GET /events/*");
    assertThat(ended).hasSize(3);
  }

  // --- end flags belong to the winning end (request-boundary-6) ------------------------------

  @ParameterizedTest(name = "T-AG-016a flush after another end sets nothing ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-016a a flush or endStreaming after another thread ended the transaction leaves"
          + " streaming false; max-duration after it leaves timed_out and leaked false")
  void lateEndsChangeNothing(ServletHelperTest.Helper helper) throws Exception {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    final TraceContext context = suspended(helper, request, response);
    runOn(
        "async-complete",
        new Runnable() {
          @Override
          public void run() {
            tracer.end(context, 204, null, null, null, System.nanoTime());
          }
        });
    ServletHelperCommon.flushed(response.proxy);
    Argos.tracer().endStreaming(context, 200, null, System.nanoTime());
    assertThat(tracer.endTimedOut(context, System.nanoTime())).isFalse();
    assertThat(ended).hasSize(1);
    assertThat(context.status).isEqualTo(204);
    assertThat(context.streaming).isFalse();
    assertThat(context.timedOut).isFalse();
    assertThat(context.hasFlags(TraceContext.FLAG_LEAKED)).isFalse();
    assertThat(counters.transactionsLeaked()).isZero();
  }

  @ParameterizedTest(name = "T-AG-016a race first flush vs onComplete ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-016a first flush racing onComplete (100 rounds): one end; streaming only when the flush"
          + " won (status 200 of the flush, 202 of onComplete)")
  void flushRacingListener(final ServletHelperTest.Helper helper) throws Exception {
    for (int round = 0; round < 100; round++) {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      TraceContext context = suspended(helper, request, response);
      response.status = 202; // what onComplete reads; the flush ends with the 200 seen so far
      final Object listener = request.listeners.get(0);
      final Object event = helper.api.event(request.proxy, response.proxy, null);
      race(
          new Runnable() {
            @Override
            public void run() {
              ServletHelperCommon.flushed(response.proxy);
            }
          },
          new Runnable() {
            @Override
            public void run() {
              helper.onComplete(listener, event);
            }
          });
      assertThat(ended).as("round " + round).hasSize(round + 1);
      assertThat(context.status).as("round " + round).isIn(200, 202);
      assertThat(context.streaming).as("round " + round).isEqualTo(context.status == 200);
    }
  }

  @ParameterizedTest(name = "T-AG-016a race first flush vs max-duration ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-016a first flush racing max-duration (100 rounds): one end; timed_out, the leaked flag"
          + " and leaked_total (+1) when max-duration won, else streaming only")
  void flushRacingMaxDuration(ServletHelperTest.Helper helper) throws Exception {
    for (int round = 0; round < 100; round++) {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      final TraceContext context = suspended(helper, request, response);
      final AtomicBoolean won = new AtomicBoolean();
      long leakedBefore = counters.transactionsLeaked();
      race(
          new Runnable() {
            @Override
            public void run() {
              ServletHelperCommon.flushed(response.proxy);
            }
          },
          new Runnable() {
            @Override
            public void run() {
              won.set(tracer.endTimedOut(context, System.nanoTime()));
            }
          });
      boolean timedOut = won.get();
      assertThat(ended).as("round " + round).hasSize(round + 1);
      assertThat(context.timedOut).as("round " + round).isEqualTo(timedOut);
      assertThat(context.hasFlags(TraceContext.FLAG_LEAKED))
          .as("round " + round)
          .isEqualTo(timedOut);
      assertThat(context.streaming).as("round " + round).isEqualTo(!timedOut);
      assertThat(counters.transactionsLeaked() - leakedBefore)
          .as("round " + round)
          .isEqualTo(timedOut ? 1 : 0);
    }
  }

  // --- the request thread drops an ended transaction (request-boundary-7) --------------------

  @ParameterizedTest(name = "T-AG-016a request thread unbinds a transaction ended elsewhere ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-016a a transaction ended on another thread during the REQUEST dispatch (end, first"
          + " flush, max-duration): after the outermost exit current() is null on the request"
          + " thread")
  void requestThreadDropsEndedTransaction(ServletHelperTest.Helper helper) throws Exception {
    for (int ender = 0; ender < 3; ender++) {
      FakeHttp.Request request = FakeHttp.Request.of(helper.api);
      final FakeHttp.Response response = FakeHttp.Response.of(helper.api);
      final TraceContext filter = helper.enter(request.proxy, false);
      TraceContext servlet = helper.enter(request.proxy, true);
      assertThat(tracer.current()).isSameAs(filter);
      final int kind = ender;
      if (kind == 1) {
        // startAsync (the servlet-async hook) maps the response; an emitter thread flushes.
        request.asyncStarted = true;
        helper.startAsync(request.proxy, response.proxy);
      }
      runOn(
          "other-end",
          new Runnable() {
            @Override
            public void run() {
              if (kind == 0) {
                tracer.end(filter, 200, null, null, null, System.nanoTime());
              } else if (kind == 1) {
                ServletHelperCommon.flushed(response.proxy);
              } else {
                tracer.endTimedOut(filter, System.nanoTime());
              }
            }
          });
      assertThat(filter.isEnded()).as("ender " + kind).isTrue();
      assertThat(tracer.current()).as("still bound before the exit").isSameAs(filter);
      helper.exit(servlet, request.proxy, response.proxy, null);
      helper.exit(filter, request.proxy, response.proxy, null);
      assertThat(tracer.current()).as("ender " + kind).isNull();
      assertThat(Argos.tracer().current()).as("ender " + kind).isNull();
      if (kind == 1) {
        // Still in async mode: the ended anchor stays for the dispatch that follows (r1-new-3).
        assertThat(request.attributes)
            .as("ender " + kind)
            .containsEntry(ServletHelperCommon.TX_ATTRIBUTE, filter);
      } else {
        assertThat(request.attributes)
            .as("ender " + kind)
            .doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
      }
      assertThat(ended).hasSize(kind + 1);
    }
  }

  // --- the ended anchor of an async request (r1-new-3, 10 §7.1 unanchored) -------------------

  @ParameterizedTest(name = "T-AG-016a ended non-async request: ERROR dispatch unanchored ({0})")
  @MethodSource("helpers")
  @Tag("req:D-04")
  @DisplayName(
      "T-AG-016a a synchronous request that max-duration ended during its REQUEST dispatch: the"
          + " exit removes the anchor and the container's ERROR dispatch that follows is counted"
          + " unanchored once, as before (T-AG-011a)")
  void endedSynchronousRequestErrorDispatchIsUnanchored(ServletHelperTest.Helper helper)
      throws Exception {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    final TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    runOn(
        "argos-watch",
        new Runnable() {
          @Override
          public void run() {
            tracer.endTimedOut(filter, System.nanoTime());
          }
        });
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    assertThat(request.attributes).doesNotContainKey(ServletHelperCommon.TX_ATTRIBUTE);
    long before = counters.transactionsUnanchored();
    request.dispatcher = "ERROR";
    Object errorRequest = request.wrapper();
    assertThat(helper.enter(errorRequest, false)).isNull();
    assertThat(helper.enter(errorRequest, true)).isNull();
    assertThat(counters.transactionsUnanchored() - before).as("once per request").isEqualTo(1);
    assertThat(ended).hasSize(1);
    assertThat(filter.timedOut).isTrue();
  }

  // --- the mapping names a flush end on the request thread (r1-new-2, T-AG-020) ---------------

  @ParameterizedTest(name = "T-AG-020a flush on the request thread after startAsync ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A02")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-020a a Spring-less SSE servlet mapped to /events/* that flushes on the request thread"
          + " right after startAsync (enter, startAsync hook, flush, exit) is named 'GET /events/*'"
          + " with streaming=true; one end")
  void sameThreadFlushNamedByMapping(ServletHelperTest.Helper helper) {
    // The facade's flushBuffer() right after startAsync.
    FakeHttp.Request request = eventsRequest(helper);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    request.asyncStarted = true;
    helper.startAsync(request.proxy, response.proxy);
    ServletHelperCommon.flushed(response.proxy);
    assertThat(filter.isEnded()).as("ended by the flush, before any exit").isTrue();
    assertThat(filter.streaming).isTrue();
    assertThat(filter.serviceName).isEqualTo("GET /events/*");
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).context()).isSameAs(filter);
    assertThat(tracer.current()).isNull();

    // The writer opened before startAsync (recorded on the transaction), flushed after it.
    FakeHttp.Request writer = eventsRequest(helper);
    FakeHttp.Response writerResponse = FakeHttp.Response.of(helper.api);
    TraceContext second = helper.enter(writer.proxy, false);
    TraceContext secondServlet = helper.enter(writer.proxy, true);
    Object stream = new Object();
    ServletHelperCommon.streamOpened(writerResponse.proxy, stream);
    writer.asyncStarted = true;
    helper.startAsync(writer.proxy, writerResponse.proxy);
    ServletHelperCommon.flushed(stream);
    assertThat(second.isEnded()).isTrue();
    assertThat(second.streaming).isTrue();
    assertThat(second.serviceName).isEqualTo("GET /events/*");
    helper.exit(secondServlet, writer.proxy, writerResponse.proxy, null);
    helper.exit(second, writer.proxy, writerResponse.proxy, null);
    assertThat(ended).hasSize(2);
    assertThat(tracer.current()).isNull();
  }

  @Test
  @Tag("req:F-A02")
  @Tag("req:F-A29")
  @DisplayName(
      "T-AG-020a javax on a Servlet 3.x container: no HttpServletMapping candidate (the Servlet 4"
          + " helper is never called); the same-thread flush end keeps the path fallback name")
  void servlet3HasNoMappingCandidate() {
    ServletHelperTest.Helper javax = helpers().get(0);
    assertThat(javax.api).isSameAs(FakeHttp.JAVAX);
    FakeHttp.Request request = eventsRequest(javax);
    request.servletMajorVersion = 3;
    FakeHttp.Response response = FakeHttp.Response.of(javax.api);
    TraceContext filter = javax.enter(request.proxy, false);
    TraceContext servlet = javax.enter(request.proxy, true);
    request.asyncStarted = true;
    javax.startAsync(request.proxy, response.proxy);
    ServletHelperCommon.flushed(response.proxy);
    javax.exit(servlet, request.proxy, response.proxy, null);
    javax.exit(filter, request.proxy, response.proxy, null);
    assertThat(filter.isEnded()).isTrue();
    assertThat(filter.streaming).isTrue();
    assertThat(filter.serviceName).isEqualTo("GET /events/abc");
    assertThat(request.count("getHttpServletMapping")).isZero();
    assertThat(ended).hasSize(1);
  }

  // --- the AsyncListener never throws (privacy-agent-safety-2) -------------------------------

  @ParameterizedTest(name = "T-AG-016a listener guards application wrappers ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-016a onComplete/onError with application wrappers that throw (removeAttribute,"
          + " getStatus, getResponse): nothing escapes, a module error is counted, the transaction"
          + " ends once")
  void listenerGuardsApplicationWrappers(ServletHelperTest.Helper helper) {
    int asyncModule = ServletHelperCommon.SERVLET_ASYNC_MODULE_ID;

    // onComplete: the request wrapper's removeAttribute and the response wrapper's getStatus throw.
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    final Object listener = request.listeners.get(0);
    final Object event =
        helper.api.event(
            failingRemoveAttribute(helper.api, request.proxy),
            failingStatus(helper.api, response.proxy),
            null);
    long before = counters.moduleErrors(asyncModule);
    final ServletHelperTest.Helper h = helper;
    assertThatCode(
            new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() {
                h.onComplete(listener, event);
              }
            })
        .doesNotThrowAnyException();
    assertThat(context.isEnded()).isTrue();
    assertThat(ended).hasSize(1);
    assertThat(context.status).as("the status recorded so far").isEqualTo(200);
    assertThat(counters.moduleErrors(asyncModule)).isEqualTo(before + 1);
    assertThatCode(
            new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() {
                h.onComplete(listener, event);
              }
            })
        .doesNotThrowAnyException();
    assertThat(ended).as("one end").hasSize(1);

    // onError: the response wrapper's getResponse throws (the unmapping); its status still counts.
    FakeHttp.Request failed = FakeHttp.Request.of(helper.api);
    FakeHttp.Response failedResponse = FakeHttp.Response.of(helper.api);
    TraceContext other = suspended(helper, failed, failedResponse);
    failedResponse.status = 503;
    final Object otherListener = failed.listeners.get(0);
    final Object errorEvent =
        helper.api.event(
            failed.proxy,
            failingGetResponse(helper.api, failedResponse.proxy),
            new IllegalStateException("broken"));
    long beforeError = counters.moduleErrors(asyncModule);
    assertThatCode(
            new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() {
                h.onError(otherListener, errorEvent);
              }
            })
        .doesNotThrowAnyException();
    assertThat(other.isEnded()).isTrue();
    assertThat(ended).hasSize(2);
    assertThat(other.status).isEqualTo(503);
    assertThat(ended.get(1).errorClass()).isEqualTo("java.lang.IllegalStateException");
    assertThat(counters.moduleErrors(asyncModule)).isEqualTo(beforeError + 1);
  }

  @ParameterizedTest(name = "T-AG-016a listener swallows a failing end ({0})")
  @MethodSource("helpers")
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-016a an exception inside the listener's end (a failing tracer) never reaches the"
          + " container thread: moduleError(servlet-async) records it")
  void listenerSwallowsAnyFailure(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = suspended(helper, request, response);
    final Object listener = request.listeners.get(0);
    final Object event = helper.api.event(request.proxy, response.proxy, null);
    final List<Object[]> moduleErrors = Collections.synchronizedList(new ArrayList<Object[]>());
    control.installTracer(failingEnd(moduleErrors));
    final ServletHelperTest.Helper h = helper;
    try {
      assertThatCode(
              new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
                @Override
                public void call() {
                  h.onComplete(listener, event);
                }
              })
          .doesNotThrowAnyException();
    } finally {
      control.installTracer(tracer);
    }
    assertThat(moduleErrors).hasSize(1);
    assertThat(moduleErrors.get(0)[0]).isEqualTo(ServletHelperCommon.SERVLET_ASYNC_MODULE_ID);
    assertThat(moduleErrors.get(0)[1]).isInstanceOf(IllegalStateException.class);
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
  }

  // --- helpers --------------------------------------------------------------------------------

  /** A request of a servlet mapped to {@code /events/*} (PATH match, no Spring). */
  private static FakeHttp.Request eventsRequest(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.uri = "/events/abc";
    request.mappingPattern = "/events/*";
    request.mappingMatch = "PATH";
    return request;
  }

  /** A REQUEST dispatch whose outermost exit leaves the request in async mode. */
  private static TraceContext suspended(
      ServletHelperTest.Helper helper, FakeHttp.Request request, FakeHttp.Response response) {
    TraceContext context = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    request.asyncStarted = true;
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(context, request.proxy, response.proxy, null);
    assertThat(context.isEnded()).isFalse();
    assertThat(request.listeners).hasSize(1);
    return context;
  }

  /** The watch thread's clock two hours after now: every running transaction is over 1 hour. */
  private static TraceClock twoHoursAhead() {
    return new TraceClock() {
      @Override
      public long nanoTime() {
        return System.nanoTime() + TimeUnit.HOURS.toNanos(2);
      }

      @Override
      public long currentTimeMillis() {
        return System.currentTimeMillis();
      }
    };
  }

  private static void runOn(String name, Runnable task) throws InterruptedException {
    Thread thread = new Thread(task, name);
    thread.start();
    thread.join(10_000);
    assertThat(thread.isAlive()).isFalse();
  }

  /** Runs both tasks on their own threads, released together. */
  private static void race(final Runnable a, final Runnable b) throws InterruptedException {
    final CountDownLatch start = new CountDownLatch(1);
    Thread first =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                ServletHelperTest.await(start);
                a.run();
              }
            },
            "race-a");
    Thread second =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                ServletHelperTest.await(start);
                b.run();
              }
            },
            "race-b");
    first.start();
    second.start();
    start.countDown();
    first.join(10_000);
    second.join(10_000);
    assertThat(first.isAlive() || second.isAlive()).isFalse();
  }

  /** An application request wrapper whose {@code removeAttribute} throws. */
  private static Object failingRemoveAttribute(FakeHttp.Api api, Object request) {
    if (api == FakeHttp.JAVAX) {
      return new javax.servlet.http.HttpServletRequestWrapper(
          (javax.servlet.http.HttpServletRequest) request) {
        @Override
        public void removeAttribute(String name) {
          throw new IllegalStateException("application wrapper: removeAttribute");
        }
      };
    }
    return new jakarta.servlet.http.HttpServletRequestWrapper(
        (jakarta.servlet.http.HttpServletRequest) request) {
      @Override
      public void removeAttribute(String name) {
        throw new IllegalStateException("application wrapper: removeAttribute");
      }
    };
  }

  /** An application response wrapper whose {@code getStatus} throws. */
  private static Object failingStatus(FakeHttp.Api api, Object response) {
    if (api == FakeHttp.JAVAX) {
      return new javax.servlet.http.HttpServletResponseWrapper(
          (javax.servlet.http.HttpServletResponse) response) {
        @Override
        public int getStatus() {
          throw new IllegalStateException("application wrapper: getStatus");
        }
      };
    }
    return new jakarta.servlet.http.HttpServletResponseWrapper(
        (jakarta.servlet.http.HttpServletResponse) response) {
      @Override
      public int getStatus() {
        throw new IllegalStateException("application wrapper: getStatus");
      }
    };
  }

  /**
   * An application response wrapper whose {@code getResponse} throws ({@code innermost}); its
   * {@code getStatus} still answers through the servlet API's own delegation.
   */
  private static Object failingGetResponse(FakeHttp.Api api, Object response) {
    if (api == FakeHttp.JAVAX) {
      return new javax.servlet.http.HttpServletResponseWrapper(
          (javax.servlet.http.HttpServletResponse) response) {
        @Override
        public javax.servlet.ServletResponse getResponse() {
          throw new IllegalStateException("application wrapper: getResponse");
        }
      };
    }
    return new jakarta.servlet.http.HttpServletResponseWrapper(
        (jakarta.servlet.http.HttpServletResponse) response) {
      @Override
      public jakarta.servlet.ServletResponse getResponse() {
        throw new IllegalStateException("application wrapper: getResponse");
      }
    };
  }

  /** A tracer whose ends throw; its module error reports go to {@code recorded}. */
  private static Tracer failingEnd(final List<Object[]> recorded) {
    return (Tracer)
        Proxy.newProxyInstance(
            Tracer.class.getClassLoader(),
            new Class<?>[] {Tracer.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if (name.equals("end") || name.equals("endStreaming")) {
                  throw new IllegalStateException("tracer end failed");
                }
                if (name.equals("moduleError")) {
                  recorded.add(new Object[] {args[0], args[1]});
                  return null;
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                  return false;
                }
                if (type == int.class) {
                  return 0;
                }
                if (type == long.class) {
                  return 0L;
                }
                return null;
              }
            });
  }
}
