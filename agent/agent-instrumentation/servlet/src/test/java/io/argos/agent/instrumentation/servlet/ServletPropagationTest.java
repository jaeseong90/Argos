/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Incoming W3C trace context on the REQUEST dispatch (10 §8, F-A06, F-A32; WO-14): the servlet
 * helpers of both namespaces hand the raw {@code traceparent} and {@code tracestate} headers to the
 * real core tracer, which adopts a valid trace and ignores anything else. The request is the
 * recording proxy of {@link FakeHttp} (the D-26 exception to "no mocks" of DSN-18-03).
 */
@Tag("req:F-A06")
@Tag("req:F-A32")
class ServletPropagationTest {
  private static final String TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private TracerImpl tracer;

  static List<ServletHelperTest.Helper> helpers() {
    return ServletHelperTest.helpers();
  }

  @BeforeEach
  void installTracer() {
    tracer =
        TracerImpl.builder()
            .control(control)
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
    control.installTracer(null);
  }

  /** One request through filter + servlet; returns the ended transaction's context. */
  private TraceContext run(ServletHelperTest.Helper helper, FakeHttp.Request request) {
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    return ended.get(ended.size() - 1).context();
  }

  @ParameterizedTest(name = "T-AG-057d valid traceparent adopted with tracestate lines ({0})")
  @MethodSource("helpers")
  void adoptsValidTraceparent(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.header("traceparent", TRACEPARENT);
    request.header("tracestate", "rojo=1");
    request.header("tracestate", "argos=2a:3:4");
    TraceContext ctx = run(helper, request);
    assertThat(ctx.traceIdHi).isEqualTo(0x0af7651916cd43ddL);
    assertThat(ctx.traceIdLo).isEqualTo(0x8448eb211c80319cL);
    assertThat(ctx.parentSpanId).isEqualTo(0xb7ad6b7169203331L);
    assertThat(ctx.spanId).isNotEqualTo(ctx.parentSpanId);
    assertThat(ctx.incomingTracestate)
        .as("several tracestate lines are one list (W3C)")
        .isEqualTo("rojo=1,argos=2a:3:4");
    assertThat(ctx.callerAppId).isEqualTo(0x2a);
  }

  @ParameterizedTest(name = "T-AG-057d invalid traceparent: new trace, tracestate not kept ({0})")
  @MethodSource("helpers")
  void newTraceForInvalidTraceparent(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.header("traceparent", "00-00000000000000000000000000000000-b7ad6b7169203331-01");
    request.header("tracestate", "argos=2a:3:4");
    TraceContext ctx = run(helper, request);
    assertThat(ctx.parentSpanId).isZero();
    assertThat(ctx.traceIdHi | ctx.traceIdLo).isNotZero();
    assertThat(ctx.incomingTracestate).isNull();
    assertThat(ctx.callerAppId).isZero();
  }

  @ParameterizedTest(name = "T-AG-057d no traceparent: tracestate is not even read ({0})")
  @MethodSource("helpers")
  void noTraceparent(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.header("tracestate", "argos=2a:3:4");
    TraceContext ctx = run(helper, request);
    assertThat(ctx.parentSpanId).isZero();
    assertThat(ctx.incomingTracestate).isNull();
    assertThat(request.called("getHeaders", "tracestate")).isFalse();
  }

  @ParameterizedTest(name = "T-AG-057d an oversized tracestate is dropped as a whole ({0})")
  @MethodSource("helpers")
  void oversizedTracestate(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.header("traceparent", TRACEPARENT);
    StringBuilder line = new StringBuilder("k=");
    while (line.length() < 300) {
      line.append('v');
    }
    // Two 256+ character lines: each line alone may be valid member text, together over 512.
    request.header("tracestate", "a=" + line.substring(2, 250));
    request.header("tracestate", "b=" + line.substring(2, 250));
    request.header("tracestate", "c=" + line.substring(2, 250));
    TraceContext ctx = run(helper, request);
    assertThat(ctx.parentSpanId).as("the traceparent is still adopted").isNotZero();
    assertThat(ctx.incomingTracestate).isNull();
  }

  @Test
  @DisplayName("T-AG-057d tracestate lines are joined up to 513 characters and 32 lines at most")
  void appendBound() {
    StringBuilder joined = new StringBuilder();
    int lines = 0;
    boolean more = true;
    while (more && lines < 1_000) {
      lines++;
      more = ServletHelperCommon.appendTracestate(joined, "k" + lines + "=v", lines);
    }
    assertThat(lines).isEqualTo(ServletHelperCommon.MAX_TRACESTATE_LINES);
    StringBuilder longJoined = new StringBuilder();
    StringBuilder big = new StringBuilder();
    while (big.length() < 2_000) {
      big.append('x');
    }
    assertThat(ServletHelperCommon.appendTracestate(longJoined, big.toString(), 1)).isFalse();
    assertThat(longJoined.length()).isEqualTo(ServletHelperCommon.MAX_TRACESTATE_CHARS);
    assertThat(ServletHelperCommon.appendTracestate(new StringBuilder(), null, 1)).isTrue();
  }
}
