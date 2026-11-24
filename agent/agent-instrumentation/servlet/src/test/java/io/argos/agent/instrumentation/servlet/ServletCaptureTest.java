/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.UserIds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the servlet helpers read from a request and what the tracer keeps of it (16 §6.1-§6.4, 10
 * §6.1, D-26): the request is a recording {@code java.lang.reflect.Proxy} of each namespace's
 * {@code HttpServletRequest} driven through the real helper path (the D-26 exception to "no mocks"
 * of DSN-18-03); the tracer is the real core tracer.
 */
@Tag("req:F-A01")
class ServletCaptureTest {
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private final AtomicReference<byte[]> salt = new AtomicReference<byte[]>(new byte[0]);
  private final AgentCounters counters = new AgentCounters();
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
            .userIdSalts(
                new UserIds.SaltSource() {
                  @Override
                  public byte[] salt() {
                    return salt.get();
                  }
                })
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

  /** One request through filter + servlet, ended with status 200; returns its record. */
  private Transaction run(ServletHelperTest.Helper helper, FakeHttp.Request request) {
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext filter = helper.enter(request.proxy, false);
    TraceContext servlet = helper.enter(request.proxy, true);
    helper.exit(servlet, request.proxy, response.proxy, null);
    helper.exit(filter, request.proxy, response.proxy, null);
    return ended.get(ended.size() - 1);
  }

  // --- D-26 ---------------------------------------------------------------------------------

  @ParameterizedTest(name = "T-ACC-26 request not consumed, every capture mode on ({0})")
  @MethodSource("helpers")
  @Tag("req:D-26")
  void requestIsNeverConsumed(ServletHelperTest.Helper helper) {
    tracer.setCapture(
        CaptureConfig.builder()
            .queryStringMode(CaptureConfig.QUERY_STRING_FULL)
            .remoteIpMode(CaptureConfig.REMOTE_IP_FULL)
            .trustedProxies(new String[] {"192.0.2.0/24"})
            .headerWhitelist(new String[] {"x-tenant"})
            .requestIdHeader("X-Request-Id")
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("session:userId")
            .build());
    // With a session: read through getSession(false) only.
    FakeHttp.Request withSession = FakeHttp.Request.of(helper.api);
    withSession.queryString = "phone=010-1234-5678";
    withSession.header("X-Forwarded-For", "198.51.100.7").header("X-Tenant", "t1");
    withSession.header("X-Request-Id", "req-1");
    withSession.sessionAttributes = new HashMap<String, Object>();
    withSession.sessionAttributes.put("userId", "kim");
    Transaction tx = run(helper, withSession);
    assertThat(tx.userId()).isEqualTo("kim");
    assertD26(withSession);

    // Without a session: getSession(false) returns null and no session is created.
    FakeHttp.Request without = FakeHttp.Request.of(helper.api);
    run(helper, without);
    assertD26(without);
    assertThat(without.called("getSession", false)).isTrue();
  }

  private static void assertD26(FakeHttp.Request request) {
    assertThat(request.calls).doesNotContainAnyElementsOf(ServletHelperTest.FORBIDDEN);
    assertThat(request.called("getSession", true)).as("getSession(true)").isFalse();
    int noArgument = 0;
    for (FakeHttp.Call call : request.detailed) {
      if (call.name.equals("getSession") && call.args.length == 0) {
        noArgument++;
      }
    }
    assertThat(noArgument).as("getSession()").isZero();
    for (FakeHttp.Call call : request.detailed) {
      if (call.name.equals("getSession")) {
        assertThat(call.args).containsExactly(false);
      }
    }
  }

  @ParameterizedTest(name = "T-ACC-26 defaults read nothing optional ({0})")
  @MethodSource("helpers")
  @Tag("req:D-26")
  @Tag("req:SEC-32")
  @Tag("req:SEC-34")
  void defaultsReadNothingOptional(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.queryString = "phone=010";
    request.header("X-Forwarded-For", "198.51.100.7");
    Transaction tx = run(helper, request);
    assertThat(request.calls)
        .doesNotContain("getQueryString", "getHeaders", "getSession")
        .doesNotContainAnyElementsOf(ServletHelperTest.FORBIDDEN);
    assertThat(tx.path()).isEqualTo("/api/orders/1");
    assertThat(tx.userId()).isNull();
    assertThat(tx.headers()).isEmpty();
    assertThat(tx.requestId()).isNull();
    assertThat(tx.remoteIp()).isEqualTo("192.0.2.0");
  }

  // --- query string (T-AG-014a, SEC-34) ------------------------------------------------------

  @ParameterizedTest(name = "T-AG-014a/T-SEC-34a query string off, whitelist, full ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-34")
  void queryStringModes(ServletHelperTest.Helper helper) {
    FakeHttp.Request off = FakeHttp.Request.of(helper.api);
    off.uri = "/api/x/12345";
    off.queryString = "phone=010-1234-5678&page=2";
    Transaction first = run(helper, off);
    assertThat(first.path()).isEqualTo("/api/x/12345");
    assertThat(first.context().serviceName).isEqualTo("GET /api/x/{*}");

    tracer.setCapture(
        CaptureConfig.builder()
            .queryStringMode(CaptureConfig.QUERY_STRING_WHITELIST)
            .queryStringWhitelist(new String[] {"size", "page"})
            .build());
    StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      longValue.append('v');
    }
    FakeHttp.Request whitelist = FakeHttp.Request.of(helper.api);
    whitelist.uri = "/api/x/7;jsessionid=abc";
    whitelist.queryString = "phone=010&size=" + longValue + "&page=2&page=3&flag";
    Transaction second = run(helper, whitelist);
    assertThat(second.path())
        .isEqualTo("/api/x/7?page=2&page=3&size=" + longValue.substring(0, 256))
        .doesNotContain("phone");
    assertThat(second.context().serviceName).isEqualTo("GET /api/x/{*}");

    tracer.setCapture(
        CaptureConfig.builder().queryStringMode(CaptureConfig.QUERY_STRING_FULL).build());
    FakeHttp.Request full = FakeHttp.Request.of(helper.api);
    full.uri = "/api/x/9";
    full.queryString = "a=1&b=%20";
    Transaction third = run(helper, full);
    assertThat(third.path()).isEqualTo("/api/x/9?a=1&b=%20");
    assertThat(third.context().serviceName).isEqualTo("GET /api/x/{*}").doesNotContain("?");
  }

  // --- client address (T-AG-017a, SEC-31, SEC-37) --------------------------------------------

  @ParameterizedTest(name = "T-AG-017a/T-SEC-31a remote IP masked, full, off ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-31")
  void remoteIpModes(ServletHelperTest.Helper helper) {
    FakeHttp.Request masked = FakeHttp.Request.of(helper.api);
    masked.remoteAddr = "2001:db8:1:2:3:4:5:6";
    assertThat(run(helper, masked).remoteIp()).isEqualTo("2001:db8:1::");

    tracer.setCapture(CaptureConfig.builder().remoteIpMode(CaptureConfig.REMOTE_IP_FULL).build());
    FakeHttp.Request full = FakeHttp.Request.of(helper.api);
    assertThat(run(helper, full).remoteIp()).isEqualTo("192.0.2.17");

    tracer.setCapture(CaptureConfig.builder().remoteIpMode(CaptureConfig.REMOTE_IP_OFF).build());
    FakeHttp.Request off = FakeHttp.Request.of(helper.api);
    assertThat(run(helper, off).remoteIp()).isNull();
    assertThat(off.calls).doesNotContain("getRemoteAddr", "getHeaders");
  }

  @ParameterizedTest(name = "T-AG-017a/T-SEC-37a X-Forwarded-For and trusted proxies ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-37")
  void forwardedFor(ServletHelperTest.Helper helper) {
    tracer.setCapture(CaptureConfig.builder().remoteIpMode(CaptureConfig.REMOTE_IP_FULL).build());
    FakeHttp.Request spoofed = FakeHttp.Request.of(helper.api);
    spoofed.header("X-Forwarded-For", "203.0.113.9");
    assertThat(run(helper, spoofed).remoteIp()).as("no trusted proxies").isEqualTo("192.0.2.17");
    assertThat(spoofed.calls).doesNotContain("getHeaders");

    tracer.setCapture(
        CaptureConfig.builder()
            .remoteIpMode(CaptureConfig.REMOTE_IP_FULL)
            .trustedProxies(new String[] {"10.0.0.0/8", "192.0.2.17"})
            .build());
    FakeHttp.Request proxied = FakeHttp.Request.of(helper.api);
    proxied
        .header("X-Forwarded-For", "203.0.113.9, 10.1.2.3")
        .header("X-Forwarded-For", "10.0.0.5");
    assertThat(run(helper, proxied).remoteIp()).isEqualTo("203.0.113.9");

    FakeHttp.Request direct = FakeHttp.Request.of(helper.api);
    direct.remoteAddr = "198.51.100.20";
    direct.header("X-Forwarded-For", "203.0.113.9");
    assertThat(run(helper, direct).remoteIp())
        .as("a direct client cannot choose its address")
        .isEqualTo("198.51.100.20");

    FakeHttp.Request garbage = FakeHttp.Request.of(helper.api);
    garbage.header("X-Forwarded-For", "not-an-ip, 10.0.0.5");
    assertThat(run(helper, garbage).remoteIp()).isEqualTo("192.0.2.17");
  }

  // --- headers and request id (T-AG-017a, C-124) ---------------------------------------------

  @ParameterizedTest(name = "T-AG-017a header whitelist and request id ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-30")
  void headersAndRequestId(ServletHelperTest.Helper helper) {
    FakeHttp.Request none = FakeHttp.Request.of(helper.api);
    none.header("X-Request-Id", "abc");
    assertThat(run(helper, none).requestId()).as("C-124: off by default").isNull();

    StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 1500; i++) {
      longValue.append('h');
    }
    tracer.setCapture(
        CaptureConfig.builder()
            .headerWhitelist(new String[] {"x-tenant", "x-long", "x-absent"})
            .requestIdHeader("X-Request-Id")
            .build());
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.header("X-TENANT", "t-1").header("X-Long", longValue.toString());
    request.header("Authorization", "Bearer secret");
    StringBuilder requestId = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      requestId.append('r');
    }
    request.header("x-request-id", requestId.toString());
    Transaction tx = run(helper, request);
    assertThat(tx.headers())
        .containsExactly("x-tenant", "t-1", "x-long", longValue.substring(0, 1024));
    assertThat(tx.requestId()).hasSize(128);
    assertThat(Arrays.asList(tx.headers())).doesNotContain("Bearer secret");
    assertThat(request.called("getHeader", "Authorization")).isFalse();
  }

  // --- user id (T-AG-017a, SEC-32, C-125) ----------------------------------------------------

  @ParameterizedTest(name = "T-AG-017a/T-SEC-32a user id off, plain, hashed ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-32")
  void userIdModes(ServletHelperTest.Helper helper) {
    FakeHttp.Request off = FakeHttp.Request.of(helper.api);
    off.header("X-User", "kim");
    assertThat(run(helper, off).userId()).isNull();
    assertThat(off.called("getHeader", "X-User")).isFalse();

    tracer.setCapture(
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("header:X-User")
            .build());
    StringBuilder longId = new StringBuilder("ki\u0000m\n");
    for (int i = 0; i < 200; i++) {
      longId.append('x');
    }
    FakeHttp.Request plain = FakeHttp.Request.of(helper.api);
    plain.header("X-User", longId.toString());
    String recorded = run(helper, plain).userId();
    assertThat(recorded).startsWith("kimx").hasSize(128);

    tracer.setCapture(
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_HASHED)
            .userIdSource("header:X-User")
            .build());
    FakeHttp.Request noSalt = FakeHttp.Request.of(helper.api);
    noSalt.header("X-User", "kim");
    assertThat(run(helper, noSalt).userId()).as("C-125: never plain without salt").isNull();
    FakeHttp.Request again = FakeHttp.Request.of(helper.api);
    again.header("X-User", "kim");
    assertThat(run(helper, again).userId()).isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(2);

    salt.set("app-salt-32-bytes-0123456789abcd".getBytes(StandardCharsets.UTF_8));
    FakeHttp.Request hashed = FakeHttp.Request.of(helper.api);
    hashed.header("X-User", "kim");
    String first = run(helper, hashed).userId();
    FakeHttp.Request same = FakeHttp.Request.of(helper.api);
    same.header("X-User", "kim");
    FakeHttp.Request other = FakeHttp.Request.of(helper.api);
    other.header("X-User", "lee");
    assertThat(first).hasSize(16).matches("[A-Za-z0-9_-]{16}").isNotEqualTo("kim");
    assertThat(run(helper, same).userId()).isEqualTo(first);
    assertThat(run(helper, other).userId()).isNotEqualTo(first);
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(2);
  }

  @ParameterizedTest(name = "T-AG-017a user id sources session and mdc ({0})")
  @MethodSource("helpers")
  @Tag("req:SEC-32")
  void userIdSources(ServletHelperTest.Helper helper) {
    tracer.setCapture(
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("session:user")
            .build());
    FakeHttp.Request numeric = FakeHttp.Request.of(helper.api);
    numeric.sessionAttributes = new HashMap<String, Object>();
    numeric.sessionAttributes.put("user", 42L);
    assertThat(run(helper, numeric).userId()).isEqualTo("42");
    FakeHttp.Request object = FakeHttp.Request.of(helper.api);
    object.sessionAttributes = new HashMap<String, Object>();
    object.sessionAttributes.put("user", new java.util.ArrayList<String>());
    assertThat(run(helper, object).userId()).as("no toString() of other objects").isNull();

    // The session attribute set by this request (login) is read at the end.
    FakeHttp.Request login = FakeHttp.Request.of(helper.api);
    FakeHttp.Response response = FakeHttp.Response.of(helper.api);
    TraceContext context = helper.enter(login.proxy, false);
    login.sessionAttributes = new HashMap<String, Object>();
    login.sessionAttributes.put("user", "park");
    helper.exit(context, login.proxy, response.proxy, null);
    assertThat(ended.get(ended.size() - 1).userId()).isEqualTo("park");

    tracer.setCapture(
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("mdc:user")
            .build());
    FakeHttp.Request mdc = FakeHttp.Request.of(helper.api);
    assertThat(run(helper, mdc).userId()).isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT))
        .as("no log event carried the key (C-125 (3))")
        .isEqualTo(1);
    assertThat(mdc.calls).doesNotContain("getSession");
  }
}
