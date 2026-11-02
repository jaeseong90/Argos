/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TracerImpl (10 §3, §8, §9.2): thread binding, single end, steps, errors, module errors. */
class TracerImplTest {
  private static final int MODULE = 50;

  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private final TracerImpl tracer =
      new TracerImpl(
          new Dictionary(),
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          control,
          new TxIdGenerator(7),
          1000,
          3);

  @AfterEach
  void reset() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(MODULE, ModuleHealth.ENABLED);
  }

  @Test
  @Tag("req:D-04")
  @DisplayName("begin binds the context to the thread; end unbinds it and exports exactly once")
  void beginEnd() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(ctx).isNotNull();
    assertThat(tracer.current()).isSameAs(ctx);
    assertThat(ctx.serviceName).isEqualTo("GET /x");
    assertThat(ctx.kind).isEqualTo(Kind.HTTP);
    assertThat(ctx.txId).isNotZero();
    assertThat(ctx.spanId).isNotZero();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    tracer.end(ctx, 500, "x", "y", null, System.nanoTime());
    assertThat(tracer.current()).isNull();
    assertThat(ended).hasSize(1);
    assertThat(ctx.status).isEqualTo(200);
    assertThat(ctx.error).isFalse();
    assertThat(ended.get(0).elapsedNanos()).isGreaterThanOrEqualTo(0);
    assertThat(tracer.begunTransactions()).isEqualTo(1);
    assertThat(tracer.endedTransactions()).isEqualTo(1);
  }

  @Test
  @Tag("req:D-21")
  @DisplayName(
      "errors: thrown exception class and message (cut at error-message-max), 5xx is an error")
  void errors() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /e", 0);
    StringBuilder message = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      message.append('m');
    }
    tracer.end(ctx, 500, null, null, new IllegalStateException(message.toString()), 10);
    Transaction tx = ended.get(0);
    assertThat(ctx.error).isTrue();
    assertThat(tx.errorClass()).isEqualTo("java.lang.IllegalStateException");
    assertThat(tx.errorMessage()).hasSize(1024);

    TraceContext server = tracer.begin(Kind.HTTP, "GET /f", 0);
    tracer.end(server, 503, null, null, null, 10);
    assertThat(server.error).isTrue();

    // SEC-36: the message is masked before it is cut (16 §6.6).
    TraceContext seeded = tracer.begin(Kind.HTTP, "GET /g", 0);
    tracer.end(
        seeded,
        500,
        null,
        null,
        new IllegalStateException("order 42 not found for kim@example.test"),
        10);
    assertThat(ended.get(2).errorMessage()).isEqualTo("order 42 not found for <email>");
  }

  @Test
  @DisplayName("steps are recorded until the transaction ends; afterwards stepStart returns -1")
  void steps() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /s", 0);
    long sql = tracer.dict(DictKind.SQL, "select 1");
    int seq = tracer.stepStart(ctx, StepType.SQL, sql, 0, 5);
    tracer.stepFetch(ctx, seq, 3, 7);
    tracer.stepDetail(ctx, seq, "detail");
    tracer.stepStack(ctx, seq, new Throwable().getStackTrace(), 2);
    tracer.stepEnd(ctx, seq, 0, false, 1, 15);
    tracer.end(ctx, 200, null, null, null, 20);
    assertThat(tracer.stepStart(ctx, StepType.SQL, sql, 0, 30)).isEqualTo(-1);
    assertThat(tracer.stepStart(null, StepType.SQL, sql, 0, 30)).isEqualTo(-1);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.rows(0)).isEqualTo(4);
    assertThat(steps.elapsedNanos(0)).isEqualTo(10);
    assertThat(steps.stack(0)).hasSize(2);
    assertThat(tracer.dictionary().text(steps.stack(0)[0])).contains("TracerImplTest.steps(");
    // SQL texts are normalized before they are kept (literals become ?, SEC-35).
    assertThat(tracer.dictionary().text(steps.nameId(0))).isEqualTo("select ?");
  }

  @Test
  @DisplayName("activate binds a context on another thread and close restores the previous one")
  void activate() {
    TraceContext outer = tracer.begin(Kind.HTTP, "GET /a", 0);
    TraceContext other = new TraceContext(1, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
    Scope scope = tracer.activate(other);
    assertThat(tracer.current()).isSameAs(other);
    scope.close();
    assertThat(tracer.current()).isSameAs(outer);
    assertThat(tracer.activate(outer)).isSameAs(Scope.NOOP);
    tracer.end(outer, 200, null, null, null, 1);
  }

  @Test
  @DisplayName("service name: fix=true freezes it; http data is cut to the documented bounds")
  void serviceNameAndHttp() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders/1", 0);
    tracer.setServiceName(ctx, "Controller#method", false);
    tracer.setServiceName(ctx, "GET /orders/{id}", true);
    tracer.setServiceName(ctx, "GET /other", true);
    StringBuilder path = new StringBuilder("/");
    for (int i = 0; i < 3000; i++) {
      path.append('p');
    }
    tracer.setHttp(ctx, "GET", path.toString(), "10.0.0.1", "user");
    tracer.end(ctx, 200, null, null, null, 1);
    assertThat(ctx.serviceName).isEqualTo("GET /orders/{id}");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ended.get(0).path()).hasSize(Transaction.PATH_MAX_CHARS);
    // capture.remote-ip defaults to masked (SEC-31): the last IPv4 octet is zero.
    assertThat(ended.get(0).remoteIp()).isEqualTo("10.0.0.0");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("T-AG-040 moduleError counts advice errors and disables the module at the threshold")
  void moduleErrorsDisableModule() {
    control.setModuleState(MODULE, ModuleHealth.ENABLED);
    int before = ModuleHealth.errors(MODULE);
    tracer.moduleError(MODULE, new IllegalStateException("1"));
    tracer.moduleError(MODULE, new IllegalStateException("2"));
    assertThat(ModuleHealth.errors(MODULE)).isEqualTo(before + 2);
    if (before + 2 < 3) {
      assertThat(ModuleHealth.enabled(MODULE)).isTrue();
    }
    tracer.moduleError(MODULE, new IllegalStateException("3"));
    assertThat(ModuleHealth.state(MODULE)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
  }

  @Test
  @DisplayName("traceparent (W3C level 1), span ids, counters, dbId without credentials")
  void propagationAndCounters() {
    TraceContext ctx =
        new TraceContext(1, 0x0102L, 0x0304L, 5, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
    assertThat(tracer.traceparent(ctx, 0xabcL))
        .isEqualTo("00-00000000000001020000000000000304-0000000000000abc-01");
    assertThat(tracer.traceparent(null, 1)).isNull();
    assertThat(tracer.tracestate(ctx, "vendor=1")).isEqualTo("vendor=1");
    assertThat(tracer.newSpanId()).isNotZero();
    tracer.countIgnored("path");
    tracer.countIgnored("path");
    tracer.countOrphan(StepType.SQL);
    assertThat(tracer.ignoredRequests("path")).isEqualTo(2);
    assertThat(tracer.orphanSteps(StepType.SQL)).isEqualTo(1);
    long id = tracer.dbId("jdbc:mysql://scott:tiger@db:3306/shop?password=x");
    assertThat(tracer.dictionary().text(id)).isEqualTo("jdbc:mysql://db:3306/shop");
  }

  @Test
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName("ignoreReason applies the default exclusion rules (10 §7.2) and replaceable rules")
  void ignoreReasonUsesRules() {
    assertThat(tracer.ignoreReason("OPTIONS", "/api/orders", null)).isEqualTo("method");
    assertThat(tracer.ignoreReason("GET", "/actuator/health", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/actuator/health/liveness", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/static/app.js", null)).isEqualTo("extension");
    assertThat(tracer.ignoreReason("GET", "/api/orders/1", "kube-probe/1.29")).isNull();
    tracer.setIgnoreRules(
        IgnoreRules.of(
            Arrays.asList("/internal/**"),
            Collections.<String>emptyList(),
            Arrays.asList("kube-probe/*"),
            Arrays.asList("TRACE")));
    assertThat(tracer.ignoreReason("GET", "/api/orders/1", "kube-probe/1.29"))
        .isEqualTo("user_agent");
    assertThat(tracer.ignoreReason("GET", "/internal/a/b", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/actuator/health", null)).isNull();
    assertThat(tracer.ignoreReason("OPTIONS", "/x", null)).isNull();
  }

  @Test
  @Tag("req:F-A29")
  @DisplayName(
      "an HTTP name no module fixed gets the F-A29 fallback; fixed names stay; cap applies")
  void serviceNameFallbackAndCap() {
    TraceContext unnamed = tracer.begin(Kind.HTTP, "GET /api/orders/42", 0);
    tracer.end(unnamed, 404, null, null, null, 1);
    assertThat(unnamed.serviceName).isEqualTo("GET /api/orders/{*}");

    TraceContext fixed = tracer.begin(Kind.HTTP, "GET /api/orders/42", 0);
    tracer.setServiceName(fixed, "GET /api/orders/{id}", true);
    tracer.end(fixed, 200, null, null, null, 1);
    assertThat(fixed.serviceName).isEqualTo("GET /api/orders/{id}");

    TraceContext batch = tracer.begin(Kind.BATCH, "BATCH job/42", 0);
    tracer.end(batch, 0, null, null, null, 1);
    assertThat(batch.serviceName).isEqualTo("BATCH job/42");

    tracer.setServiceNames(new ServiceNames(1));
    TraceContext first = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.end(first, 200, null, null, null, 1);
    TraceContext second = tracer.begin(Kind.HTTP, "GET /b", 0);
    tracer.end(second, 200, null, null, null, 1);
    assertThat(first.serviceName).isEqualTo("GET /a");
    assertThat(second.serviceName).isEqualTo(ServiceNames.OTHER);
    assertThat(tracer.serviceNames().overflowCount()).isEqualTo(1);
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName("setHttp drops path parameters and applies capture.remote-ip full/masked/off")
  void httpPathAndRemoteIpModes() {
    TraceContext masked = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(masked, "GET", "/a;jsessionid=SECRET/b", "2001:db8:1:2::7", null);
    tracer.end(masked, 200, null, null, null, 1);
    assertThat(ended.get(0).path()).isEqualTo("/a/b");
    assertThat(ended.get(0).remoteIp()).isEqualTo("2001:db8:1::");

    tracer.setCapture(capture(CaptureConfig.REMOTE_IP_FULL));
    TraceContext full = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(full, "GET", "/a", "192.0.2.17", null);
    tracer.end(full, 200, null, null, null, 1);
    assertThat(ended.get(1).remoteIp()).isEqualTo("192.0.2.17");

    tracer.setCapture(capture(CaptureConfig.REMOTE_IP_OFF));
    TraceContext off = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(off, "GET", "/a", "192.0.2.17", null);
    tracer.end(off, 200, null, null, null, 1);
    assertThat(ended.get(2).remoteIp()).isNull();
  }

  private static CaptureConfig capture(int remoteIpMode) {
    CaptureConfig d = CaptureConfig.DEFAULT;
    return new CaptureConfig(
        d.queryStringMode(),
        d.queryStringWhitelist(),
        d.headerWhitelist(),
        remoteIpMode,
        d.trustedProxies(),
        d.userIdMode(),
        d.userIdSource(),
        d.sqlParamMode(),
        d.sqlParamWhitelistColumns(),
        d.errorMessageMax(),
        d.stackDepth());
  }
}
