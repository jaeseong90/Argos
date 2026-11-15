/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.servertest.security.HeaderAuthentication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-56, the limits (14 §2.7, 16 §7.13, §9.9): each bucket with its default limit on the real
 * server: when the first 429 comes, with what headers, for whom, and that a request cannot walk
 * around the limit. The first refused request of each bucket goes to {@code
 * server/build/reports/rate-limits.md}.
 */
@Tag("req:SEC-56")
class RateLimitSecurityTest {

  private static final String EDGE = "/api/v1/test-edge";
  private static final List<String> MEASURED = new ArrayList<>();

  private final ArgosTestServer server = SecuritySuite.server();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  private ApiTestClient anonymous() {
    return SecuritySuite.client(server, null);
  }

  /**
   * Sends requests until the first 429 and asserts that it comes at {@code limit + 1}, or a few
   * requests later when the bucket refilled meanwhile. Returns that response.
   */
  private static ApiResponse firstRejected(
      String bucket, int limit, int refillAllowance, Supplier<ApiResponse> request) {
    long start = System.nanoTime();
    for (int i = 1; i <= limit + refillAllowance + 1; i++) {
      ApiResponse response = request.get();
      assertThat(response.status()).as("request " + i).isNotIn(500, 502, 503, 504);
      if (response.status() == 429) {
        assertThat(i).as(bucket + ": the first refused request").isGreaterThan(limit);
        MEASURED.add(
            "| "
                + bucket
                + " | "
                + limit
                + " | "
                + i
                + " | "
                + Duration.ofNanos(System.nanoTime() - start).toMillis()
                + " ms |");
        assertRejection(response);
        return response;
      }
      assertThat(response.header("X-RateLimit-Limit")).as("request " + i).isPresent();
    }
    throw new AssertionError(bucket + ": no 429 within " + (limit + refillAllowance + 1));
  }

  private static void assertRejection(ApiResponse response) {
    assertThat(response.errorCode()).contains("rate_limited");
    assertThat(Long.parseLong(response.header("Retry-After").orElseThrow())).isPositive();
    assertThat(response.header("X-RateLimit-Limit")).isPresent();
    assertThat(response.header("X-RateLimit-Remaining")).contains("0");
    assertThat(response.json().path("error").path("request_id").asString())
        .isEqualTo(response.header("X-Request-Id").orElseThrow());
  }

  private double rejections(String scope) throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpResponse<String> scrape =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.managementPort() + "/actuator/prometheus"))
                .header("Authorization", "Bearer " + SecuritySuite.metricsToken())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(scrape.statusCode()).isEqualTo(200);
    return scrape
        .body()
        .lines()
        .filter(
            l ->
                l.startsWith("argos_ratelimit_rejections_total{")
                    && l.contains("scope=\"" + scope + "\""))
        .findFirst()
        .map(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1)))
        .orElse(0.0);
  }

  @Test
  @DisplayName(
      "T-SEC-56 login: 20 a minute per address, the 21st is 429 with Retry-After; the metric counts"
          + " it under scope auth")
  void login() throws Exception {
    double before = rejections("auth");
    ApiTestClient client = anonymous();
    ApiResponse rejected =
        firstRejected("login", 20, 1, () -> client.post("/api/v1/auth/login", "{}"));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("20");
    assertThat(Long.parseLong(rejected.header("Retry-After").orElseThrow())).isBetween(1L, 3L);
    assertThat(rejections("auth")).isEqualTo(before + 1);
  }

  @Test
  @DisplayName("T-SEC-56 refresh shares the 20 a minute per address")
  void refresh() {
    ApiTestClient client = anonymous();
    ApiResponse rejected =
        firstRejected("refresh", 20, 1, () -> client.post("/api/v1/auth/refresh", null));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("20");
  }

  @Test
  @DisplayName("T-SEC-56 the setup token endpoint shares the 20 a minute per address")
  void setup() {
    ApiTestClient client = anonymous();
    ApiResponse rejected =
        firstRejected("setup", 20, 1, () -> client.post("/api/v1/setup/admin", "{}"));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("20");
  }

  @Test
  @DisplayName(
      "T-SEC-56 a client cannot walk around the address limit: another spelling of the path counts,"
          + " X-Forwarded-For is not believed, and the limit holds before the handler runs")
  void noWayAround() {
    ApiTestClient client = anonymous();
    firstRejected("login (encoded path)", 20, 1, () -> client.post("/api/v1/auth/logi%6E", "{}"));
    SecuritySuite.resetLimits(server);

    ApiTestClient spoofing = anonymous();
    int[] sent = {0};
    ApiResponse rejected =
        firstRejected(
            "login (X-Forwarded-For)",
            20,
            1,
            () -> {
              sent[0]++;
              return spoofing
                  .header("X-Forwarded-For", "10.1.1." + sent[0])
                  .header("X-Real-IP", "10.1.2." + sent[0])
                  .post("/api/v1/auth/login", "{}");
            });
    assertRejection(rejected);
  }

  @Test
  @DisplayName(
      "T-SEC-56 behind a trusted proxy the address is the client's: one client's limit leaves the"
          + " others alone")
  void perClientBehindTrustedProxy() {
    ArgosTestServer proxied =
        SecuritySuite.start(
            Map.of(
                "server.forward-headers-strategy", "native",
                "server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1"));
    try {
      ApiTestClient first =
          SecuritySuite.client(proxied, null).header("X-Forwarded-For", "203.0.113.10");
      firstRejected("login (client 1)", 20, 1, () -> first.post("/api/v1/auth/login", "{}"));
      ApiTestClient second =
          SecuritySuite.client(proxied, null).header("X-Forwarded-For", "203.0.113.11");
      assertThat(second.post("/api/v1/auth/login", "{}").status()).isNotEqualTo(429);
    } finally {
      proxied.close();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-56 general API: burst of 100 per session, then 429; another session and the anonymous"
          + " bucket are not affected")
  void generalApi() {
    ApiTestClient session = SecuritySuite.client(server, "viewer");
    String sid = UUID.randomUUID().toString();
    session.header(HeaderAuthentication.SID_HEADER, sid);
    // 50 tokens a second come back while the loop runs: a machine at 1 ms a request gets a few, a
    // loaded one more. What must hold: nothing below the burst is refused, and the loop ends.
    ApiResponse rejected =
        firstRejected("general API", 100, 20_000, () -> session.get(EDGE + "/viewer"));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("100");
    assertThat(Long.parseLong(rejected.header("Retry-After").orElseThrow())).isEqualTo(1);

    ApiTestClient otherSession = SecuritySuite.client(server, "viewer");
    otherSession.header(HeaderAuthentication.SID_HEADER, UUID.randomUUID().toString());
    assertThat(otherSession.get(EDGE + "/viewer").status()).isEqualTo(200);
    assertThat(anonymous().get(EDGE + "/ok").status()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "T-SEC-56 @RateLimited(HEAVY): 10 a minute per user over the general limit; another user is"
          + " not affected, a refused role costs no token")
  void heavy() {
    for (int i = 0; i < 15; i++) {
      assertThat(anonymous().get(EDGE + "/heavy").status()).as("no session").isEqualTo(401);
    }
    ApiTestClient user = SecuritySuite.client(server, "viewer");
    user.header(HeaderAuthentication.USER_HEADER, UUID.randomUUID().toString());
    ApiResponse rejected = firstRejected("heavy", 10, 1, () -> user.get(EDGE + "/heavy"));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("10");
    assertThat(Long.parseLong(rejected.header("Retry-After").orElseThrow())).isBetween(1L, 6L);
    assertThat(user.get(EDGE + "/viewer").status())
        .as("the general limit is separate")
        .isEqualTo(200);

    ApiTestClient other = SecuritySuite.client(server, "viewer");
    other.header(HeaderAuthentication.USER_HEADER, UUID.randomUUID().toString());
    assertThat(other.get(EDGE + "/heavy").status()).isEqualTo(200);
  }

  @Test
  @DisplayName("T-SEC-56 CSP reports: 60 a minute per address, the 61st is 429")
  void cspReports() {
    ApiTestClient browser = server.client().csrf(false).origin(null).secFetchSite(null);
    String report = "{\"csp-report\":{\"effective-directive\":\"img-src\"}}";
    ApiResponse rejected =
        firstRejected("CSP report", 60, 10, () -> browser.post("/api/v1/csp-report", report));
    assertThat(rejected.header("X-RateLimit-Limit")).contains("60");
  }

  @AfterAll
  static void writeMeasured() throws IOException {
    Path report =
        Path.of(
            System.getProperty("argos.repositoryRoot", ".."),
            "server",
            "build",
            "reports",
            "rate-limits.md");
    Files.createDirectories(report.getParent());
    StringBuilder out =
        new StringBuilder(
            """
            # Rate limits measured (T-SEC-56, shared machine, reference only)

            | Bucket | Limit | First refused request | Elapsed |
            |---|---|---|---|
            """);
    MEASURED.forEach(line -> out.append(line).append('\n'));
    Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
  }
}
