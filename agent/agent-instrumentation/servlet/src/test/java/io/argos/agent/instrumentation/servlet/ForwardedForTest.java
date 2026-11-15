/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
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
 * The {@code X-Forwarded-For} value the servlet helpers hand to the tracer (16 §6.2, SEC-37): every
 * header line is read and the last {@value ServletHelperCommon#MAX_FORWARDED_LINES} are kept (the
 * lines that trusted proxies append come last), joined and cut on the left at an entry boundary to
 * {@value ServletHelperCommon#MAX_FORWARDED_CHARS} characters. Real core tracer; the request is the
 * recording proxy of {@link FakeHttp}.
 */
@Tag("req:SEC-37")
class ForwardedForTest {
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
    tracer.setCapture(
        CaptureConfig.builder()
            .remoteIpMode(CaptureConfig.REMOTE_IP_FULL)
            .trustedProxies(new String[] {"10.0.0.0/8"})
            .build());
    control.installTracer(tracer);
  }

  @AfterEach
  void uninstallTracer() {
    control.installTracer(null);
  }

  @ParameterizedTest(name = "T-AG-017a/T-SEC-37a 40 forged lines + the line of the proxy ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-017a/T-SEC-37a 40 forged X-Forwarded-For lines before the line the trusted proxy"
          + " appended: the proxy's line (the real client) wins, not a forged value")
  void lastLinesAreKept(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.remoteAddr = "10.0.0.1"; // the trusted proxy
    for (int i = 0; i < 40; i++) {
      request.header("X-Forwarded-For", "198.51.100.66");
    }
    request.header("X-Forwarded-For", "203.0.113.9"); // appended by the proxy (HAProxy style)
    assertThat(run(helper, request).remoteIp()).isEqualTo("203.0.113.9");

    // The proxy's own address appended after the client's: still read from the right.
    FakeHttp.Request twoProxies = FakeHttp.Request.of(helper.api);
    twoProxies.remoteAddr = "10.0.0.1";
    for (int i = 0; i < 40; i++) {
      twoProxies.header("X-Forwarded-For", "198.51.100.66");
    }
    twoProxies.header("X-Forwarded-For", "203.0.113.9").header("X-Forwarded-For", "10.9.9.9");
    assertThat(run(helper, twoProxies).remoteIp()).isEqualTo("203.0.113.9");
  }

  @ParameterizedTest(name = "T-AG-017a/T-SEC-37a 2,048-char cut at an entry boundary ({0})")
  @MethodSource("helpers")
  @DisplayName(
      "T-AG-017a/T-SEC-37a a value over 2,048 characters is cut at an entry boundary: 110.0.0.1"
          + " is dropped, never read as 10.0.0.1")
  void cutAtEntryBoundary(ServletHelperTest.Helper helper) {
    FakeHttp.Request request = FakeHttp.Request.of(helper.api);
    request.remoteAddr = "10.0.0.1";
    // "110.0.0.1" + 20 lines of 101 characters: 9 + 20 * 102 = 2,049 characters, so a plain cut
    // of the left 1 character would leave "10.0.0.1" (a trusted address) as the leftmost entry.
    request.header("X-Forwarded-For", "110.0.0.1");
    String padded = spaces(93) + "10.2.2.2";
    assertThat(padded).hasSize(101);
    for (int i = 0; i < 20; i++) {
      request.header("X-Forwarded-For", padded);
    }
    String client = run(helper, request).remoteIp();
    assertThat(client).isNotEqualTo("10.0.0.1").isEqualTo("10.2.2.2");
  }

  @Test
  @DisplayName(
      "T-AG-017a/T-SEC-37a the joined value: the last 32 lines, right 2,048 characters, a split"
          + " entry dropped, a single entry over the bound gives nothing")
  void joinedValue() {
    assertThat(ServletHelperCommon.MAX_FORWARDED_LINES).isEqualTo(32);
    assertThat(ServletHelperCommon.MAX_FORWARDED_CHARS).isEqualTo(2048);
    assertThat(ServletHelperCommon.forwarded(new String[32], 0)).isNull();

    // 40 lines a0..a39 through the ring: a8..a39 remain.
    String[] ring = new String[ServletHelperCommon.MAX_FORWARDED_LINES];
    for (int i = 0; i < 40; i++) {
      ring[i % ring.length] = "a" + i;
    }
    String joined = ServletHelperCommon.forwarded(ring, 40);
    assertThat(joined).startsWith("a8,a9,").endsWith(",a38,a39");
    assertThat(joined.split(",")).hasSize(32);

    // 170 entries "203.0.113.7" joined: 170 * 12 - 1 = 2,039 characters.
    String entries = entries(170);
    assertThat(entries).hasSize(2039);

    // "110.0.0.1," + entries = 2,049 characters: the cut would split the first entry, which is
    // dropped as a whole (a plain cut of 1 character would leave "10.0.0.1").
    String split = "110.0.0.1," + entries;
    assertThat(split).hasSize(2049);
    String cut = ServletHelperCommon.forwarded(new String[] {split}, 1);
    assertThat(cut).isEqualTo(entries);
    assertThat(cut.split(",")).hasSize(170).doesNotContain("10.0.0.1");

    // Exactly 2,048 characters: unchanged.
    String exact = "99.9.9.9," + entries;
    assertThat(exact).hasSize(2048);
    assertThat(ServletHelperCommon.forwarded(new String[] {exact}, 1)).isEqualTo(exact);

    // The cut right after a comma keeps the whole entry that follows it.
    String boundary = "ab," + exact;
    assertThat(boundary).hasSize(2051);
    assertThat(ServletHelperCommon.forwarded(new String[] {boundary}, 1)).isEqualTo(exact);

    // Lines are joined with commas before the cut; an empty line stays an (empty) entry.
    assertThat(ServletHelperCommon.forwarded(new String[] {"110.0.0.1", entries}, 2))
        .isEqualTo(entries);

    // A single entry over the bound: nothing is left, the tracer uses the socket address.
    assertThat(ServletHelperCommon.forwarded(new String[] {spaces(3000)}, 1)).isNull();
    assertThat(ServletHelperCommon.forwarded(new String[] {"", ""}, 2)).isEqualTo(",");
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

  /** {@code count} entries {@code 203.0.113.7} joined with commas. */
  private static String entries(int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append("203.0.113.7");
    }
    return out.toString();
  }

  private static String spaces(int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(' ');
    }
    return out.toString();
  }
}
