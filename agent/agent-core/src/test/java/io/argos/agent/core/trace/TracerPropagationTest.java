/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.context.HostGlobs;
import io.argos.agent.core.context.PropagationSettings;
import io.argos.agent.core.context.TraceStateCodec;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The propagation and HTTP-step part of TracerImpl (10 §6.5, §8, F-A05, F-A06, F-A32, SEC-38;
 * WO-14): incoming adoption, outgoing headers, HOST ids, HTTP steps with their failure class and
 * the external-call totals.
 */
@Tag("req:F-A06")
class TracerPropagationTest {
  private static final String TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final TracerImpl tracer =
      new TracerImpl(
          new Dictionary(),
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          null,
          new TxIdGenerator(3),
          1000,
          100);

  @AfterEach
  void reset() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
  }

  private static PropagationSettings settings(
      List<String> exclude, List<String> argosHosts, boolean preserve) {
    return PropagationSettings.of(exclude, argosHosts, preserve);
  }

  // --- incoming ----------------------------------------------------------------------------

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057c a valid incoming traceparent: trace id adopted, parent span = incoming, new span")
  void adoptsValid() {
    TraceContext ctx =
        tracer.begin(Kind.HTTP, "GET /x", System.nanoTime(), TRACEPARENT, "rojo=1,argos=2a:3:4");
    assertThat(ctx.traceIdHi).isEqualTo(0x0af7651916cd43ddL);
    assertThat(ctx.traceIdLo).isEqualTo(0x8448eb211c80319cL);
    assertThat(ctx.parentSpanId).isEqualTo(0xb7ad6b7169203331L);
    assertThat(ctx.spanId).isNotZero().isNotEqualTo(ctx.parentSpanId);
    assertThat(ctx.incomingTracestate).isEqualTo("rojo=1,argos=2a:3:4");
    assertThat(ctx.callerAppId).isEqualTo(0x2a);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057c an invalid traceparent starts a new trace and ignores tracestate; bad tracestate"
          + " dropped")
  void rejectsInvalid() {
    TraceContext upper =
        tracer.begin(
            Kind.HTTP,
            "GET /x",
            System.nanoTime(),
            TRACEPARENT.toUpperCase(java.util.Locale.ROOT),
            "argos=2a:3:4");
    assertThat(upper.parentSpanId).isZero();
    assertThat(upper.traceIdLo).isNotEqualTo(0x8448eb211c80319cL);
    assertThat(upper.incomingTracestate).isNull();
    assertThat(upper.callerAppId).isZero();
    tracer.end(upper, 200, null, null, null, System.nanoTime());

    TraceContext badState =
        tracer.begin(Kind.HTTP, "GET /x", System.nanoTime(), TRACEPARENT, "Bad Key=1");
    assertThat(badState.parentSpanId).isEqualTo(0xb7ad6b7169203331L);
    assertThat(badState.incomingTracestate).isNull();
    assertThat(badState.callerAppId).isZero();
    tracer.end(badState, 200, null, null, null, System.nanoTime());

    TraceContext malformedArgos =
        tracer.begin(Kind.HTTP, "GET /x", System.nanoTime(), TRACEPARENT, "argos=2A:3:4");
    assertThat(malformedArgos.incomingTracestate).isEqualTo("argos=2A:3:4");
    assertThat(malformedArgos.callerAppId).as("malformed argos entry -> caller 0").isZero();
    tracer.end(malformedArgos, 200, null, null, null, System.nanoTime());

    TraceContext none = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(none.parentSpanId).isZero();
    assertThat(none.traceIdHi >>> 32).as("Unix seconds in the upper bits").isPositive();
    tracer.end(none, 200, null, null, null, System.nanoTime());
  }

  // --- outgoing headers --------------------------------------------------------------------

  @Test
  @Tag("req:SEC-38")
  @DisplayName(
      "T-SEC-38a defaults: traceparent with the transaction's ids, no argos tracestate entry")
  void defaultsNoArgosEntry() {
    tracer.setSessionIds(1, 2);
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    String[] headers = tracer.outgoingHeaders(ctx, "api.example.com", null, null);
    assertThat(headers).hasSize(2);
    assertThat(headers[0])
        .isEqualTo(
            "00-"
                + TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo)
                + "-"
                + TraceIds.hex16(ctx.spanId)
                + "-01");
    assertThat(headers[1]).as("no incoming tracestate, no argos entry").isNull();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @Tag("req:SEC-38")
  @DisplayName(
      "T-SEC-38a an incoming argos entry is not forwarded to a host outside argos-state.hosts")
  void incomingArgosStripped() {
    tracer.setSessionIds(1, 2);
    TraceContext ctx =
        tracer.begin(Kind.HTTP, "GET /x", System.nanoTime(), TRACEPARENT, "argos=9:9:9,rojo=1");
    String[] headers = tracer.outgoingHeaders(ctx, "third-party.example", null, null);
    assertThat(headers[0]).contains("0af7651916cd43dd8448eb211c80319c");
    assertThat(headers[1]).isEqualTo("rojo=1");
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057c argos-state.hosts: argos=<app>:<inst>:<svc> first, incoming members kept after it")
  void argosEntryForListedHosts() {
    tracer.setPropagation(settings(null, Arrays.asList("127.0.0.1", "*.internal.example"), true));
    tracer.setSessionIds(0x1fL, 7L);
    TraceContext ctx =
        tracer.begin(Kind.HTTP, "GET /x", System.nanoTime(), TRACEPARENT, "rojo=1,argos=9:9:9");
    tracer.setServiceName(ctx, "GET /orders/{id}", true);
    String[] internal = tracer.outgoingHeaders(ctx, "orders.internal.example", null, null);
    String expected =
        TraceStateCodec.argosEntry(0x1fL, 7L, Dictionary.hash64("GET /orders/{id}")) + ",rojo=1";
    assertThat(internal[1]).isEqualTo(expected);
    assertThat(TraceStateCodec.callerAppId(internal[1])).isEqualTo(0x1f);
    String[] external = tracer.outgoingHeaders(ctx, "api.example.com", null, null);
    assertThat(external[1]).isEqualTo("rojo=1");
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @DisplayName("T-AG-057c no argos entry before the first ServerHello (app id unknown)")
  void noEntryWithoutSession() {
    tracer.setPropagation(settings(null, Collections.singletonList("*"), true));
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(tracer.outgoingHeaders(ctx, "h", null, null)[1]).isNull();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @Tag("req:SEC-38")
  @DisplayName("T-SEC-38a exclude-hosts: neither header for a matching host")
  void excludedHosts() {
    tracer.setPropagation(
        settings(
            Collections.singletonList("*.partner.example"), Collections.singletonList("*"), true));
    tracer.setSessionIds(1, 2);
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(tracer.outgoingHeaders(ctx, "API.Partner.Example", null, null)).isNull();
    assertThat(tracer.outgoingHeaders(ctx, "own.example", null, null)).isNotNull();
    assertThat(tracer.shouldPropagate("api.partner.example")).isFalse();
    assertThat(tracer.shouldPropagate("own.example")).isTrue();
    assertThat(tracer.outgoingHeaders(ctx, null, null, null)).as("no host").isNull();
    assertThat(tracer.outgoingHeaders(null, "own.example", null, null)).as("no context").isNull();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(tracer.outgoingHeaders(ctx, "own.example", null, null)).as("ended").isNull();
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057c an application traceparent is kept (preserve-incoming) and replaced when false")
  void preserveIncoming() {
    String own = "00-11111111111111111111111111111111-2222222222222222-01";
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    String[] kept = tracer.outgoingHeaders(ctx, "h", own, null);
    assertThat(kept).isNotNull();
    assertThat(kept[0]).as("null = leave the application's header as it is").isNull();
    tracer.setPropagation(settings(null, null, false));
    String[] replaced = tracer.outgoingHeaders(ctx, "h", own, null);
    assertThat(replaced[0]).startsWith("00-" + TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo));
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057c an application tracestate is left alone, or gets the argos entry first when"
          + " listed")
  void applicationTracestate() {
    tracer.setSessionIds(5, 6);
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(tracer.outgoingHeaders(ctx, "h", null, "app=1")[1]).isNull();
    tracer.setPropagation(settings(null, Collections.singletonList("h"), true));
    String state = tracer.outgoingHeaders(ctx, "h", null, "app=1,argos=1:1:1")[1];
    assertThat(state).startsWith("argos=5:6:").endsWith(",app=1");
    assertThat(state.split("argos=")).hasSize(2);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @DisplayName("T-AG-057c the legacy tracestate() keeps a valid value without an argos entry")
  void legacyTracestate() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(tracer.tracestate(ctx, "vendor=1,argos=1:1:1")).isEqualTo("vendor=1");
    assertThat(tracer.tracestate(ctx, "Bad=1")).isNull();
    assertThat(tracer.tracestate(null, null)).isNull();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  // --- HOST ids and HTTP steps -------------------------------------------------------------

  @Test
  @Tag("req:F-A05")
  @DisplayName("T-AG-056b hostId is the HOST dictionary id of scheme://host:port (C-142)")
  void hostId() {
    long id = tracer.hostId("HTTP", "Example.com", -1);
    assertThat(id).isNotZero().isEqualTo(tracer.hostId("http", "example.com", 80));
    assertThat(tracer.dictionary().text(id)).isEqualTo("http://example.com:80");
    assertThat(tracer.dictionary().kind(id)).isEqualTo(DictKind.HOST);
    assertThat(tracer.hostId("http", null, 80)).isZero();
  }

  @Test
  @Tag("req:F-A05")
  @DisplayName(
      "T-AG-056c HTTP step: name and target = host id, detail without query, status and failure")
  void httpStep() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    long host = tracer.hostId("http", "127.0.0.1", 18080);
    long start = System.nanoTime();
    int seq = tracer.httpStart(ctx, host, "GET", "127.0.0.1:18080", "/pay/1?card=1", null, start);
    assertThat(seq).isZero();
    tracer.httpEnd(ctx, seq, host, 503, null, start, start + 5_000_000L);
    int timeout = tracer.httpStart(ctx, host, "GET", "127.0.0.1:18080", "/timeout", null, start);
    tracer.httpEnd(
        ctx, timeout, host, 0, new SocketTimeoutException("Read timed out"), start, start + 1);
    int refused = tracer.httpStart(ctx, host, "POST", "127.0.0.1:18080", "/x", null, start);
    tracer.httpEnd(ctx, refused, host, 0, new ConnectException("refused"), start, start + 1);
    int ok = tracer.httpStart(ctx, host, "GET", "127.0.0.1:18080", "/ok", null, start);
    tracer.httpEnd(ctx, ok, host, 200, null, start, start + 1);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());

    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.type(0)).isEqualTo(StepType.HTTP);
    assertThat(steps.nameId(0)).isEqualTo(host);
    assertThat(steps.targetId(0)).isEqualTo(host);
    assertThat(steps.detail(0)).isEqualTo("GET 127.0.0.1:18080/pay/1");
    assertThat(steps.status(0)).isEqualTo(503);
    assertThat(steps.failure(0)).isEqualTo(FailureKind.HTTP_5XX);
    assertThat(steps.error(0)).isTrue();
    assertThat(steps.elapsedNanos(0)).isEqualTo(5_000_000L);
    assertThat(steps.failure(1)).isEqualTo(FailureKind.TIMEOUT);
    assertThat(steps.status(1)).isZero();
    assertThat(steps.failure(2)).isEqualTo(FailureKind.CONNECT_FAIL);
    assertThat(steps.detail(2)).isEqualTo("POST 127.0.0.1:18080/x");
    assertThat(steps.failure(3)).isEqualTo(FailureKind.NONE);
    assertThat(steps.error(3)).isFalse();
    assertThat(ctx.extCount).isEqualTo(4);
    assertThat(ctx.extNanos).isEqualTo(5_000_003L);
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName("T-AG-057c a kept application traceparent is written into the HTTP step detail")
  void preservedTraceparentInDetail() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    int seq = tracer.httpStart(ctx, 1, "GET", "h", "/a", TRACEPARENT, System.nanoTime());
    tracer.httpEnd(ctx, seq, 1, 200, null, 0, 1);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended.get(0).steps().detail(seq)).isEqualTo("GET h/a traceparent=" + TRACEPARENT);
  }

  @Test
  @DisplayName(
      "T-AG-056c the HTTP step is a leaf: a later step on the request thread is not its child")
  void httpStepIsLeaf() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    int http = tracer.httpStart(ctx, 1, "GET", "h", "/a", null, System.nanoTime());
    int sql = tracer.stepStart(ctx, StepType.SQL, 2, 0, System.nanoTime());
    tracer.stepEnd(ctx, sql, 0, false, 0, System.nanoTime());
    tracer.httpEnd(ctx, http, 1, 200, null, 0, 1);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(ended.get(0).steps().parent(sql)).isEqualTo(StepBuffer.NO_PARENT);
  }

  @Test
  @DisplayName("T-AG-056c after the end: no step, no totals; no context: -1 and nothing recorded")
  void endedTransaction() throws Exception {
    final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    final int seq = tracer.httpStart(ctx, 1, "GET", "h", "/a", null, System.nanoTime());
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    final CountDownLatch done = new CountDownLatch(1);
    Thread loop =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                tracer.httpEnd(ctx, seq, 1, 500, null, 0, 1_000_000L);
                done.countDown();
              }
            });
    loop.start();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(ctx.extCount).isZero();
    assertThat(tracer.httpStart(ctx, 1, "GET", "h", "/a", null, 0)).isEqualTo(-1);
    assertThat(tracer.httpStart(null, 1, "GET", "h", "/a", null, 0)).isEqualTo(-1);
    tracer.httpEnd(null, -1, 1, 0, new RuntimeException(), 0, 1);
  }

  @Test
  @DisplayName("T-AG-056c external-call totals are exact when calls end on many threads at once")
  void concurrentTotals() throws Exception {
    final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    final int threads = 8;
    final int perThread = 2_000;
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                      tracer.httpEnd(ctx, -1, 1, 200, null, 0, 3);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    done.countDown();
                  }
                }
              })
          .start();
    }
    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(ctx.extCount).isEqualTo(threads * perThread);
    assertThat(ctx.extNanos).isEqualTo(3L * threads * perThread);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  @DisplayName("T-AG-056c settings from the configuration lists are applied as given")
  void settingsObject() {
    PropagationSettings s =
        PropagationSettings.of(
            Collections.singletonList("a"), Collections.singletonList("b"), false);
    tracer.setPropagation(s);
    assertThat(tracer.propagation().excludeHosts().matches("a")).isTrue();
    assertThat(tracer.propagation().argosStateHosts().matches("b")).isTrue();
    assertThat(tracer.propagation().preserveIncoming()).isFalse();
    tracer.setPropagation(null);
    assertThat(tracer.propagation()).isSameAs(s);
    assertThat(PropagationSettings.DEFAULTS.excludeHosts()).isSameAs(HostGlobs.NONE);
    assertThat(PropagationSettings.DEFAULTS.preserveIncoming()).isTrue();
  }
}
