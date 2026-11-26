/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF vectors of 16 §8.1 that {@code AlertChannelSecurityTest} (T-SEC-51b's 14 addresses, a 302 to
 * another loopback receiver) does not cover (WO-46, 18 §12 T-SEC-51):
 *
 * <ul>
 *   <li>T-SEC-51f: host names that resolve to the loopback ({@code localhost}, its absolute form
 *       {@code localhost.}, upper case), the inet_aton forms resolvers read as addresses (octal
 *       {@code 0177.0.0.1}, hexadecimal {@code 0x7f000001} and {@code 0x7f.0.0.1}, short {@code
 *       127.1}, a 32-bit octal integer), the unspecified IPv6 address {@code [::]}, the long and
 *       dotted IPv4-mapped forms of {@code 127.0.0.1}, the IPv4-compatible form, and NAT64 and 6to4
 *       prefixes carrying the cloud metadata address and the loopback. Saving any of them as a
 *       webhook or Slack destination answers 422 {@code webhook_destination_blocked}, and the
 *       answer does not echo the URL. The server under test has no {@code allow-cidrs}: the shared
 *       suite server with the default settings.
 *   <li>T-SEC-51g: an allowed destination that answers 301, 303, 307 or 308 with a {@code Location}
 *       on a private address, the cloud metadata address or another loopback receiver: the test
 *       send is not delivered ({@code error=redirect}, the status), the redirect target gets no
 *       request, and the destination's response body is not shown.
 * </ul>
 */
@Tag("req:SEC-51")
class SsrfVectorsSecurityTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static Path dir;
  private static ArgosTestServer redirectServer;
  private static HttpsServer redirecting;
  private static FakeWebhookReceiver stolen;
  private static final AtomicInteger REDIRECTING_REQUESTS = new AtomicInteger();
  private static volatile int redirectStatus = 307;
  private static volatile String redirectLocation = "https://10.0.0.1/";
  private static volatile String redirectBody = "";

  @BeforeAll
  static void start() throws Exception {
    dir = Files.createTempDirectory("argos-ssrf-vectors");
    TestCertificates.TestPki pki = TestTls.pki(dir);
    SSLContext tls = TestTls.serverContext(pki);
    stolen = FakeWebhookReceiver.https(tls);
    redirecting =
        HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
    redirecting.setHttpsConfigurator(new HttpsConfigurator(tls));
    redirecting.createContext(
        "/",
        exchange -> {
          REDIRECTING_REQUESTS.incrementAndGet();
          try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          byte[] body = redirectBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Location", redirectLocation);
          exchange.sendResponseHeaders(redirectStatus, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
          exchange.close();
        });
    redirecting.start();
    TestMasterKey masterKey = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(masterKey.property(), masterKey.file().toString());
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    redirectServer = SecuritySuiteAccess.start(p);
  }

  @AfterAll
  static void stop() {
    try {
      if (redirectServer != null) {
        redirectServer.close();
      }
    } finally {
      if (redirecting != null) {
        redirecting.stop(0);
      }
      if (stolen != null) {
        stolen.close();
      }
    }
  }

  private static ApiTestClient admin(ArgosTestServer on) {
    SecuritySuiteAccess.resetLimits(on);
    return SecuritySuiteAccess.client(on, "admin");
  }

  private static ApiResponse create(ArgosTestServer on, String type, String target) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("name", "ssrf-" + type);
    body.put("target", target);
    return admin(on).post("/api/v1/alerts/channels", body);
  }

  /** Reasons a blocked vector may carry (16 §8.1 steps 1, 4 and 5). */
  private static final Set<String> BLOCKED_REASONS =
      Set.of("invalid_url", "dns", "loopback", "unspecified", "link_local", "own_address");

  @ParameterizedTest(name = "T-SEC-51f saving {0} answers 422 webhook_destination_blocked")
  @ValueSource(
      strings = {
        "https://localhost/hook",
        "https://localhost./hook",
        "https://LOCALHOST:8443/hook",
        "https://0177.0.0.1/hook",
        "https://0x7f000001/hook",
        "https://0x7f.0.0.1/hook",
        "https://127.1/hook",
        "https://017700000001/hook",
        "https://[::]/hook",
        "https://[0:0:0:0:0:ffff:7f00:1]/hook",
        "https://[::ffff:127.0.0.1]/hook",
        "https://[::127.0.0.1]/hook",
        "https://[64:ff9b::a9fe:a9fe]/latest/meta-data",
        "https://[2002:7f00:1::]/hook"
      })
  void blockedVector(String url) {
    ArgosTestServer strict = SecuritySuiteAccess.server();
    for (String type : List.of("webhook", "slack")) {
      ApiResponse r = create(strict, type, url);
      assertThat(r.status()).as("%s %s: %s", type, url, r).isEqualTo(422);
      assertThat(r.errorCode()).contains("webhook_destination_blocked");
      String reason = r.json().path("error").path("details").get(0).path("reason").asString();
      assertThat(reason).as("%s %s", type, url).isIn(BLOCKED_REASONS);
      assertThat(r.body()).as("the answer does not echo the URL").doesNotContain(url);
    }
  }

  @ParameterizedTest(
      name = "T-SEC-51g a test send answered {0} with Location {1} is not followed; no body shown")
  @CsvSource({
    "301, https://10.0.0.1/hook",
    "303, http://169.254.169.254/latest/meta-data/",
    "307, https://192.168.0.1/hook",
    "308, https://[::1]/hook",
    "307, STOLEN",
    "308, STOLEN"
  })
  void redirectNotFollowed(int status, String location) {
    String target = location.equals("STOLEN") ? stolen.url("/stolen") : location;
    String marker = "PRIVATE-REDIRECT-BODY-" + token();
    redirectStatus = status;
    redirectLocation = target;
    redirectBody = marker;
    ApiResponse created =
        create(
            redirectServer,
            "webhook",
            "https://127.0.0.1:" + redirecting.getAddress().getPort() + "/hook");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String id = created.json().path("data").path("channel").path("channel_id").asString();
    int before = REDIRECTING_REQUESTS.get();
    stolen.clear();

    ApiResponse r = admin(redirectServer).post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().path("data").path("delivered").asBoolean()).isFalse();
    assertThat(r.json().path("data").path("status_code").asInt()).isEqualTo(status);
    assertThat(r.json().path("data").path("error").asString()).isEqualTo("redirect");
    assertThat(r.body()).doesNotContain(marker).doesNotContain(target);
    assertThat(REDIRECTING_REQUESTS.get() - before).as("requests to the destination").isEqualTo(1);
    assertThat(stolen.requests()).as("requests to the redirect target").isEmpty();

    assertThat(admin(redirectServer).delete("/api/v1/alerts/channels/" + id).status())
        .isEqualTo(204);
  }

  private static String token() {
    byte[] b = new byte[12];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
