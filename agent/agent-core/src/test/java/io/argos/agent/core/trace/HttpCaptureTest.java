/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The capture rules of the request data (16 §6.1-§6.4, SEC-31, SEC-32, SEC-34, SEC-37, ADR-0010
 * C-124, C-125): {@link HttpPaths#recorded}, {@link RemoteIps#client}, {@link UserIds}, {@link
 * HeaderCapture}, and the tracer methods that apply them.
 */
class HttpCaptureTest {

  // --- query string (SEC-34) ------------------------------------------------------------------

  @Test
  @Tag("req:SEC-34")
  @DisplayName(
      "T-AG-014a/T-SEC-34a query string: off keeps the path, whitelist sorts and cuts, full keeps"
          + " the raw query; 2 KiB in total")
  void queryStringModes() {
    String[] names = {"page", "size"};
    assertThat(HttpPaths.recorded("/a", "phone=010", CaptureConfig.QUERY_STRING_OFF, names))
        .isEqualTo("/a");
    assertThat(
            HttpPaths.recorded(
                "/a;jsessionid=1",
                "size=10&phone=010&page=2",
                CaptureConfig.QUERY_STRING_WHITELIST,
                names))
        .isEqualTo("/a?page=2&size=10");
    assertThat(HttpPaths.recorded("/a", "phone=010", CaptureConfig.QUERY_STRING_WHITELIST, names))
        .isEqualTo("/a");
    assertThat(HttpPaths.recorded("/a", "page", CaptureConfig.QUERY_STRING_WHITELIST, names))
        .isEqualTo("/a?page");
    assertThat(HttpPaths.recorded("/a", "page=1", CaptureConfig.QUERY_STRING_WHITELIST, null))
        .isEqualTo("/a");
    assertThat(HttpPaths.recorded("/a", "x=%41&y", CaptureConfig.QUERY_STRING_FULL, null))
        .isEqualTo("/a?x=%41&y");
    assertThat(HttpPaths.recorded(null, null, CaptureConfig.QUERY_STRING_FULL, null))
        .isEqualTo("/");
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      value.append('v');
    }
    String whitelisted =
        HttpPaths.recorded("/a", "page=" + value, CaptureConfig.QUERY_STRING_WHITELIST, names);
    assertThat(whitelisted).hasSize("/a?page=".length() + HttpPaths.QUERY_VALUE_MAX_CHARS);
    assertThat(HttpPaths.recorded("/a", "q=" + value, CaptureConfig.QUERY_STRING_FULL, null))
        .hasSize(HttpPaths.MAX_URL_CHARS);
  }

  // --- client address (SEC-37) ----------------------------------------------------------------

  @Test
  @Tag("req:SEC-37")
  @DisplayName(
      "T-SEC-37a X-Forwarded-For: ignored without trusted proxies; read from the right through"
          + " trusted hops; a direct client or a malformed entry gives the socket address")
  void forwardedFor() {
    RemoteIps.Trusted none = RemoteIps.Trusted.of(null);
    assertThat(RemoteIps.client("192.0.2.1", "203.0.113.9", none)).isEqualTo("192.0.2.1");
    RemoteIps.Trusted trusted =
        RemoteIps.Trusted.of(new String[] {"10.0.0.0/8", "2001:db8::/32", "192.0.2.1", " bad "});
    assertThat(trusted.isEmpty()).isFalse();
    assertThat(RemoteIps.client("10.1.1.1", "203.0.113.9, 10.2.2.2", trusted))
        .isEqualTo("203.0.113.9");
    assertThat(RemoteIps.client("10.1.1.1", "198.51.100.1, 203.0.113.9", trusted))
        .as("the first untrusted hop from the right, not the leftmost (spoofable) one")
        .isEqualTo("203.0.113.9");
    assertThat(RemoteIps.client("198.51.100.5", "203.0.113.9", trusted))
        .as("a peer outside the trusted proxies is the client")
        .isEqualTo("198.51.100.5");
    assertThat(RemoteIps.client("10.1.1.1", "garbage, 203.0.113.9", trusted))
        .isEqualTo("203.0.113.9");
    assertThat(RemoteIps.client("10.1.1.1", "203.0.113.9, garbage", trusted)).isEqualTo("10.1.1.1");
    assertThat(RemoteIps.client("10.1.1.1", "", trusted)).isEqualTo("10.1.1.1");
    assertThat(RemoteIps.client("10.1.1.1", null, trusted)).isEqualTo("10.1.1.1");
    assertThat(RemoteIps.client("10.1.1.1", "10.3.3.3, 10.4.4.4", trusted))
        .as("only trusted hops: the leftmost")
        .isEqualTo("10.3.3.3");
    assertThat(RemoteIps.client("2001:db8::1", "[2001:db9::7]:443", trusted))
        .isEqualTo("2001:db9::7");
    assertThat(RemoteIps.client("::ffff:10.0.0.1", "203.0.113.9:8080", trusted))
        .isEqualTo("203.0.113.9");
    assertThat(trusted.compiledFrom(new String[] {"10.0.0.0/8"})).isFalse();
    assertThat(none.compiledFrom(new String[0])).isTrue();
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName("T-SEC-31a IP modes after the client choice: full, masked (IPv4 /24, IPv6 /48), off")
  void ipModes() {
    assertThat(RemoteIps.apply("203.0.113.9", CaptureConfig.REMOTE_IP_FULL))
        .isEqualTo("203.0.113.9");
    assertThat(RemoteIps.apply("203.0.113.9", CaptureConfig.REMOTE_IP_MASKED))
        .isEqualTo("203.0.113.0");
    assertThat(RemoteIps.apply("2001:db8:aa:bb::1", CaptureConfig.REMOTE_IP_MASKED))
        .isEqualTo("2001:db8:aa::");
    assertThat(RemoteIps.apply("203.0.113.9", CaptureConfig.REMOTE_IP_OFF)).isNull();
  }

  // --- user id (SEC-32, C-125) ----------------------------------------------------------------

  @Test
  @Tag("req:SEC-32")
  @DisplayName(
      "T-SEC-32a user id: control characters removed and 128 chars kept; hashed is the first 16"
          + " chars of base64url(HMAC-SHA256(salt, id)); no salt sends nothing and counts")
  void userIds() throws Exception {
    assertThat(UserIds.sanitize("  \u0000ki\tm\u007f ", 128)).isEqualTo("kim");
    assertThat(UserIds.sanitize("\n\r", 128)).isNull();
    assertThat(UserIds.sanitize("😀😀", 3)).isEqualTo("😀");

    byte[] salt = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    byte[] digest = mac.doFinal("kim".getBytes(StandardCharsets.UTF_8));
    String expected =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);

    AgentCounters counters = new AgentCounters();
    final AtomicReference<byte[]> current = new AtomicReference<byte[]>(new byte[0]);
    UserIds ids =
        new UserIds(
            counters,
            new UserIds.SaltSource() {
              @Override
              public byte[] salt() {
                return current.get();
              }
            });
    CaptureConfig hashed =
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_HASHED)
            .userIdSource("header:x-user")
            .build();
    Transaction noSalt = transaction();
    ids.apply(noSalt, hashed, "kim");
    assertThat(noSalt.userId()).isNull();
    assertThat(noSalt.userIdDecided()).isTrue();
    ids.apply(noSalt, hashed, "kim");
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(1);

    current.set(salt);
    Transaction withSalt = transaction();
    ids.apply(withSalt, hashed, "kim");
    assertThat(withSalt.userId()).isEqualTo(expected);
    for (int i = 0; i < 20; i++) {
      byte[] bytes = new byte[i];
      for (int b = 0; b < i; b++) {
        bytes[b] = (byte) (b * 37 + i);
      }
      String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      assertThat(UserIds.base64Url(bytes, 16))
          .isEqualTo(encoded.length() > 16 ? encoded.substring(0, 16) : encoded);
    }

    Transaction empty = transaction();
    ids.apply(empty, hashed, "   ");
    assertThat(empty.userIdDecided()).as("nothing usable yet: a later call may decide").isFalse();

    CaptureConfig mdc =
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("mdc:user")
            .build();
    Transaction fromMdc = transaction();
    ids.apply(fromMdc, mdc, "kim");
    assertThat(fromMdc.userId()).isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_UNSUPPORTED)).isEqualTo(1);

    Transaction off = transaction();
    ids.apply(off, CaptureConfig.DEFAULT, "kim");
    assertThat(off.userId()).isNull();
    assertThat(off.userIdDecided()).isFalse();
  }

  @Test
  @DisplayName("CaptureConfig parses capture.user-id.source once: type and key")
  void userIdSourceParsing() {
    CaptureConfig header = CaptureConfig.builder().userIdSource("header: X-User ").build();
    assertThat(header.userIdSourceType()).isEqualTo(CaptureConfig.USER_ID_SOURCE_HEADER);
    assertThat(header.userIdSourceKey()).isEqualTo("X-User");
    assertThat(CaptureConfig.builder().userIdSource("session:uid").build().userIdSourceType())
        .isEqualTo(CaptureConfig.USER_ID_SOURCE_SESSION);
    assertThat(CaptureConfig.builder().userIdSource("cookie:x").build().userIdSourceType())
        .isNull();
    assertThat(CaptureConfig.builder().userIdSource("header:").build().userIdSourceType()).isNull();
    assertThat(CaptureConfig.DEFAULT.userIdSourceType()).isNull();
    assertThat(CaptureConfig.DEFAULT.requestIdHeader()).isNull();
    assertThat(CaptureConfig.builder().requestIdHeader("  ").build().requestIdHeader()).isNull();
  }

  // --- headers and request id (C-124) -----------------------------------------------------------

  @Test
  @Tag("req:SEC-30")
  @DisplayName(
      "T-AG-017a headers: at most 10 pairs, names lower case, values 1 KiB, empty values dropped;"
          + " request id 128 chars")
  void headers() {
    List<String> pairs = new ArrayList<String>();
    for (int i = 0; i < 12; i++) {
      pairs.add("X-H" + i);
      pairs.add(i == 3 ? null : "v" + i);
    }
    String[] kept = HeaderCapture.headers(pairs.toArray(new String[0]));
    assertThat(kept).hasSize(20);
    assertThat(kept[19]).isEqualTo("v10");
    assertThat(kept[0]).isEqualTo("x-h0");
    assertThat(kept[6]).isEqualTo("x-h4");
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      value.append('x');
    }
    assertThat(HeaderCapture.headers(new String[] {"A", value.toString()})[1]).hasSize(1024);
    assertThat(HeaderCapture.headers(new String[] {"A"})).isEmpty();
    assertThat(HeaderCapture.headers(null)).isEmpty();
    assertThat(HeaderCapture.requestId(value.toString())).hasSize(128);
    assertThat(HeaderCapture.requestId("  ")).isNull();
    assertThat(HeaderCapture.requestId(" r-1 ")).isEqualTo("r-1");
  }

  // --- tracer -----------------------------------------------------------------------------------

  @Test
  @Tag("req:SEC-37")
  @DisplayName(
      "T-AG-017a the tracer applies every mode: URL by query mode, client by trusted proxies and IP"
          + " mode, headers, request id; recompiles trusted proxies on a new snapshot")
  void tracerAppliesModes() {
    final List<Transaction> ended = new ArrayList<Transaction>();
    TracerImpl tracer =
        TracerImpl.builder()
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
            .queryStringMode(CaptureConfig.QUERY_STRING_FULL)
            .remoteIpMode(CaptureConfig.REMOTE_IP_FULL)
            .trustedProxies(new String[] {"10.0.0.0/8"})
            .build());
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /a", System.nanoTime());
    tracer.setHttpRequest(ctx, "GET", "/a;x=1", "q=1", "10.0.0.1", "203.0.113.9");
    tracer.setHeaders(ctx, new String[] {"X-Tenant", "t1"});
    tracer.setRequestId(ctx, "req-7");
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    Transaction tx = ended.get(0);
    assertThat(tx.path()).isEqualTo("/a?q=1");
    assertThat(tx.remoteIp()).isEqualTo("203.0.113.9");
    assertThat(tx.headers()).containsExactly("x-tenant", "t1");
    assertThat(tx.requestId()).isEqualTo("req-7");
    assertThat(ctx.serviceName).doesNotContain("q=1");

    tracer.setCapture(CaptureConfig.builder().remoteIpMode(CaptureConfig.REMOTE_IP_MASKED).build());
    TraceContext second = tracer.begin(Kind.HTTP, "GET /b", System.nanoTime());
    tracer.setHttpRequest(second, "GET", "/b", "q=1", "10.0.0.1", "203.0.113.9");
    tracer.end(second, 200, null, null, null, System.nanoTime());
    assertThat(ended.get(1).path()).isEqualTo("/b");
    assertThat(ended.get(1).remoteIp()).isEqualTo("10.0.0.0");

    // After the end nothing changes and nothing throws.
    tracer.setHttpRequest(second, "GET", "/c", null, null, null);
    tracer.setUserId(second, "kim");
    tracer.setHeaders(second, new String[] {"a", "b"});
    tracer.setRequestId(second, "x");
    tracer.setUserId(null, "kim");
    assertThat(ended.get(1).path()).isEqualTo("/b");
    tracer.countUnanchored();
    assertThat(tracer.counters().transactionsUnanchored()).isEqualTo(1);
  }

  private static Transaction transaction() {
    TraceContext ctx =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    return new Transaction(ctx, 10, "t", "t", 0);
  }
}
