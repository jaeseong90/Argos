/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-46a, the server part of T-SEC-46 (16 §9.9): the policy of the UI page, its per-request
 * nonce, which paths get the page, and the report endpoint. No violation in the browser is WO-30's
 * E2E part.
 */
@Tag("req:SEC-46")
class CspSecurityTest {

  private static final String REPORT = "/api/v1/csp-report";
  private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9_-]+)'");

  private final ArgosTestServer server = SecuritySuite.server();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  private ApiTestClient browser() {
    return SecuritySuite.client(server, null);
  }

  /** How a browser sends a report: no CSRF header, no cookie needed. */
  private ApiTestClient reporter() {
    return server.client().csrf(false).origin(null).secFetchSite(null);
  }

  private static String nonceOf(ApiResponse page) {
    Matcher matcher = NONCE.matcher(page.header("Content-Security-Policy").orElseThrow());
    assertThat(matcher.find()).as("a nonce in the policy").isTrue();
    return matcher.group(1);
  }

  private static String policy(String nonce, String connect) {
    return "default-src 'self'; script-src 'self' 'nonce-"
        + nonce
        + "' 'strict-dynamic'; style-src 'self' 'nonce-"
        + nonce
        + "'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self' "
        + connect
        + "; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self';"
        + " frame-ancestors 'none'; report-uri /api/v1/csp-report; report-to csp";
  }

  private static String report(String directive, String blocked) {
    return "{\"csp-report\":{\"effective-directive\":\""
        + directive
        + "\",\"blocked-uri\":\""
        + blocked
        + "\",\"disposition\":\"enforce\",\"document-uri\":\"https://argos.test/apps?token=x\"}}";
  }

  private double counter(String line) throws Exception {
    return Double.parseDouble(
        scrape()
            .lines()
            .filter(l -> l.startsWith(line))
            .findFirst()
            .map(l -> l.substring(l.lastIndexOf(' ') + 1))
            .orElse("0"));
  }

  private String scrape() throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.managementPort() + "/actuator/prometheus"))
                .header("Authorization", "Bearer " + SecuritySuite.metricsToken())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  @Test
  @DisplayName("T-SEC-46a the policy of the page is exactly 16 §9.9, with a 16-byte nonce")
  void exactPolicy() {
    ApiResponse page = browser().get("/");
    assertThat(page.status()).isEqualTo(200);
    String nonce = nonceOf(page);
    assertThat(page.headers("Content-Security-Policy"))
        .containsExactly(policy(nonce, "ws://127.0.0.1:" + server.port()));
    assertThat(Base64.getUrlDecoder().decode(nonce)).hasSize(16);
    assertThat(page.headers("Reporting-Endpoints")).containsExactly("csp=\"/api/v1/csp-report\"");
  }

  @Test
  @DisplayName(
      "T-SEC-46a every script, style and module preload tag and the csp-nonce meta tag carry the"
          + " nonce of the header, and the nonce differs per request")
  void nonceInTags() {
    List<String> nonces = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ApiResponse page = browser().get("/");
      String nonce = nonceOf(page);
      nonces.add(nonce);
      String body = page.body();
      String attribute = "nonce=\"" + nonce + "\"";

      assertThat(tags(body, "script"))
          .hasSize(3)
          .allSatisfy(t -> assertThat(t).contains(attribute));
      assertThat(tags(body, "style")).hasSize(1).allSatisfy(t -> assertThat(t).contains(attribute));
      assertThat(tags(body, "link"))
          .filteredOn(t -> t.contains("modulepreload"))
          .hasSize(1)
          .allSatisfy(t -> assertThat(t).contains(attribute));
      assertThat(body).contains("<meta name=\"csp-nonce\" content=\"" + nonce + "\">");
      assertThat(body).as("the nonce of the file is replaced").doesNotContain("stale\"");
    }
    assertThat(nonces).doesNotHaveDuplicates();
  }

  private static List<String> tags(String html, String name) {
    Matcher matcher = Pattern.compile("<" + name + "\\b[^>]*>").matcher(html);
    List<String> found = new ArrayList<>();
    while (matcher.find()) {
      found.add(matcher.group());
    }
    return found;
  }

  @Test
  @DisplayName(
      "T-SEC-46a the page is served for / and client routes, never for the API, Actuator, /ws or"
          + " files")
  void routes() {
    for (String path :
        List.of(
            "/",
            "/index.html",
            "/login",
            "/overview",
            "/apps/3/dashboard",
            "/apps/3/transactions/7312000000000001",
            "/admin/users")) {
      ApiResponse page = browser().get(path);
      assertThat(page.status()).as(path).isEqualTo(200);
      assertThat(page.body()).as(path).contains("Argos test page");
      assertThat(page.header("Content-Security-Policy")).as(path).isPresent();
    }
    for (String path :
        List.of(
            "/api/v1/no-such-endpoint",
            "/api",
            "/actuator",
            "/actuator/health",
            "/actuator/env",
            "/ws/anything",
            "/assets/missing.js",
            "/apps/3/missing.js",
            "/missing.css",
            "/v3/api-docs",
            "/swagger-ui/index.html")) {
      ApiResponse response = browser().get(path);
      assertThat(response.status()).as(path).isEqualTo(404);
      assertThat(response.body()).as(path).doesNotContain("Argos test page");
      assertThat(response.header("Content-Security-Policy")).as(path).isEmpty();
    }
    // /ws is the WebSocket endpoint since WO-22: a plain GET without the page's Origin is refused
    // by the handshake before any upgrade (14 §5.1, T-SEC-44), and it is never the page.
    ApiResponse ws = browser().get("/ws");
    assertThat(ws.status()).as("/ws").isEqualTo(403);
    assertThat(ws.body()).as("/ws").doesNotContain("Argos test page");
    assertThat(ws.header("Content-Security-Policy")).as("/ws").isEmpty();
    ApiResponse file = browser().get("/assets/fixture.js");
    assertThat(file.status()).isEqualTo(200);
    assertThat(file.body()).contains("static file of the security suite");
    assertThat(file.header("Content-Security-Policy")).as("the API and files have none").isEmpty();
    assertThat(browser().get("/api/v1/test-edge/ok").header("Content-Security-Policy")).isEmpty();
    assertThat(browser().post("/", "{}").status()).as("the page is GET only").isEqualTo(405);
  }

  @Test
  @DisplayName(
      "T-SEC-46a an https public URL gives wss:// in connect-src; the style switch drops the nonce"
          + " from style-src only")
  void publicUrlAndStyleSwitch() {
    ArgosTestServer other =
        SecuritySuite.start(
            Map.of(
                "argos.web.public-url",
                "https://Argos.Example.test:8443/ui",
                "argos.web.csp.style-unsafe-inline",
                "true"));
    try {
      ApiResponse page = SecuritySuite.client(other, null).get("/");
      String nonce = nonceOf(page);
      assertThat(page.headers("Content-Security-Policy"))
          .containsExactly(
              policy(nonce, "wss://argos.example.test:8443")
                  .replace(
                      "style-src 'self' 'nonce-" + nonce + "'",
                      "style-src 'self' 'unsafe-inline'"));
    } finally {
      other.close();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-46a a report is public, answers 204, is counted and logged as a masked summary, and"
          + " nothing of its URLs reaches the log")
  void reportIsCountedAndMasked() throws Exception {
    double before = counter("argos_csp_violations_total");
    String secret = SecuritySuite.newToken();
    ApiResponse response =
        reporter()
            .send(
                "POST",
                REPORT,
                report(
                    "script-src\\nforged log line", "https://evil.example:444/p?token=" + secret),
                Map.of("Content-Type", "application/csp-report"));
    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertThat(response.setCookies()).isEmpty();
    assertThat(counter("argos_csp_violations_total")).isEqualTo(before + 1);

    ApiResponse modern =
        reporter()
            .send(
                "POST",
                REPORT,
                "[{\"type\":\"csp-violation\",\"url\":\"https://x\",\"body\":"
                    + "{\"effectiveDirective\":\"style-src\",\"blockedURL\":\"inline\","
                    + "\"disposition\":\"report\"}}]",
                Map.of("Content-Type", "application/reports+json"));
    assertThat(modern.status()).isEqualTo(204);
    assertThat(counter("argos_csp_violations_total")).isEqualTo(before + 2);

    String log = Files.readString(SecuritySuite.logFile());
    assertThat(log)
        .contains("CSP violation: directive=script-src?forged log line,")
        .contains("blocked=https://evil.example:444")
        .contains("disposition=enforce")
        .contains("CSP violation: directive=style-src, blocked=inline, disposition=report")
        .doesNotContain(secret);
    assertThat(log.lines().filter(l -> l.startsWith("forged log line"))).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-46a a report of exactly 8 KiB is taken, one byte more is 400 payload_too_large, also"
          + " chunked without a length")
  void reportSizeLimit() throws Exception {
    String start = "{\"csp-report\":{\"effective-directive\":\"img-src\",\"padding\":\"";
    String end = "\"}}";
    String exactly = start + "a".repeat(8 * 1024 - start.length() - end.length()) + end;
    assertThat(exactly.getBytes(StandardCharsets.UTF_8)).hasSize(8 * 1024);
    assertThat(reporter().post(REPORT, exactly).status()).isEqualTo(204);

    String over = start + "a".repeat(8 * 1024 - start.length() - end.length() + 1) + end;
    ApiResponse tooLarge = reporter().post(REPORT, over);
    assertThat(tooLarge.status()).isEqualTo(400);
    assertThat(tooLarge.errorCode()).contains("payload_too_large");

    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    HttpResponse<String> chunked =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + REPORT))
                .timeout(Duration.ofSeconds(15))
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(over.getBytes(StandardCharsets.UTF_8))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(chunked.request().headers().firstValue("Content-Length")).isEmpty();
    assertThat(chunked.statusCode()).isEqualTo(400);
    assertThat(chunked.body()).contains("payload_too_large");
  }

  @Test
  @DisplayName(
      "T-SEC-46a a report endpoint takes POST only, rejects what is not a report, and 60 reports a"
          + " minute per address, then 429 with Retry-After")
  void reportMethodsAndRate() {
    assertThat(browser().get(REPORT).status()).isEqualTo(405);
    for (String notReport : List.of("{}", "[]", "not json", "{\"csp-report\":1}", "")) {
      ApiResponse response = reporter().post(REPORT, notReport);
      assertThat(response.status()).as(notReport).isEqualTo(400);
      assertThat(response.errorCode()).as(notReport).contains("invalid_request");
    }
    SecuritySuite.resetLimits(server);

    String body = report("img-src", "https://x.example/a");
    int firstRejected = -1;
    ApiResponse rejected = null;
    for (int i = 1; i <= 80 && firstRejected < 0; i++) {
      ApiResponse response = reporter().post(REPORT, body);
      if (response.status() == 429) {
        firstRejected = i;
        rejected = response;
      } else {
        assertThat(response.status()).as("report " + i).isEqualTo(204);
      }
    }
    // One token a second comes back while the loop runs.
    assertThat(firstRejected).as("the first refused report").isBetween(61, 75);
    assertThat(rejected.errorCode()).contains("rate_limited");
    assertThat(Integer.parseInt(rejected.header("Retry-After").orElseThrow())).isBetween(1, 2);
    assertThat(rejected.header("X-RateLimit-Limit")).contains("60");
    assertThat(rejected.header("X-RateLimit-Remaining")).contains("0");
    assertThat(rejected.header("Content-Type").orElseThrow().toLowerCase(Locale.ROOT))
        .startsWith("application/json");
  }
}
