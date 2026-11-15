/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-45: the security headers of 16 §9.8 with their exact values on every kind of response of
 * the HTTP port, on the real server (real PostgreSQL and ClickHouse, Testcontainers).
 */
@Tag("req:SEC-45")
class SecurityHeadersSecurityTest {

  /** The exact values of 16 §9.8; written out here, not read from the constants of the server. */
  private static final Map<String, String> EVERY_RESPONSE =
      Map.of(
          "X-Content-Type-Options", "nosniff",
          "X-Frame-Options", "DENY",
          "Referrer-Policy", "strict-origin-when-cross-origin",
          "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
          "Cross-Origin-Opener-Policy", "same-origin",
          "Cross-Origin-Resource-Policy", "same-origin");

  private static final String HSTS = "max-age=31536000; includeSubDomains";
  private static final String EDGE = "/api/v1/test-edge";

  private final ArgosTestServer server = SecuritySuite.server();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  private ApiTestClient anonymous() {
    return SecuritySuite.client(server, null);
  }

  private ApiTestClient as(String role) {
    return SecuritySuite.client(server, role);
  }

  private static void assertEveryResponseHeader(String what, ApiResponse response) {
    EVERY_RESPONSE.forEach(
        (name, value) ->
            assertThat(response.headers(name)).as(what + " " + name).containsExactly(value));
    assertThat(response.headers("Set-Cookie")).as(what + " sets no cookie").isEmpty();
  }

  private static void assertApiHeaders(String what, ApiResponse response) {
    assertEveryResponseHeader(what, response);
    assertThat(response.headers("Cache-Control")).as(what).containsExactly("no-store");
    assertThat(
            response.header("Content-Type").orElseThrow().replace(" ", "").toLowerCase(Locale.ROOT))
        .as(what)
        .isEqualTo("application/json;charset=utf-8");
    assertThat(response.header("Strict-Transport-Security"))
        .as(what + ": no HSTS on plain HTTP")
        .isEmpty();
    assertThat(response.header("X-Request-Id")).as(what).isPresent();
  }

  @Test
  @DisplayName("T-SEC-45 API 200, 401, 403, 404, 405, 429 and 500 carry every header exactly")
  void apiResponses() {
    Map<String, ApiResponse> responses = new LinkedHashMap<>();
    responses.put("200", anonymous().get(EDGE + "/ok"));
    responses.put("401", anonymous().get(EDGE + "/viewer"));
    responses.put("403", as("viewer").get(EDGE + "/admin"));
    responses.put("404", anonymous().get("/api/v1/no-such-endpoint"));
    responses.put("405", anonymous().delete(EDGE + "/ok"));
    responses.put("500", anonymous().get(EDGE + "/boom"));
    ApiTestClient viewer = as("viewer");
    ApiResponse last = null;
    for (int i = 0; i < 11; i++) {
      last = viewer.get(EDGE + "/heavy");
    }
    responses.put("429", last);

    responses.forEach(
        (status, response) -> {
          assertThat(response.status()).as(status).isEqualTo(Integer.parseInt(status));
          assertApiHeaders(status, response);
        });
  }

  @Test
  @DisplayName(
      "T-SEC-45 400s written before Spring MVC (URL limit, body limit, firewall) carry them too")
  void earlyRejections() {
    ApiResponse longUrl = anonymous().get(EDGE + "/ok?filter=" + "a".repeat(9_000));
    assertThat(longUrl.status()).isEqualTo(400);
    assertThat(longUrl.errorCode()).contains("limit_exceeded");
    assertApiHeaders("URL limit", longUrl);

    ApiResponse largeBody =
        anonymous().post(EDGE + "/echo", "{\"k\":\"" + "a".repeat(1_100_000) + "\"}");
    assertThat(largeBody.status()).isEqualTo(400);
    assertThat(largeBody.errorCode()).contains("payload_too_large");
    assertApiHeaders("body limit", largeBody);

    ApiResponse firewall = anonymous().get(EDGE + "/ok;x=1");
    assertThat(firewall.status()).isEqualTo(400);
    assertApiHeaders("firewall", firewall);
  }

  @Test
  @DisplayName(
      "T-SEC-45 a CORS preflight gets the headers and the ErrorBody, and grants no cross-origin"
          + " access")
  void corsPreflight() {
    ApiResponse preflight =
        anonymous()
            .send(
                "OPTIONS",
                "/api/v1/auth/login",
                null,
                Map.of(
                    "Origin",
                    ApiTestClient.FOREIGN_ORIGIN,
                    "Access-Control-Request-Method",
                    "POST"));
    assertThat(preflight.status()).isEqualTo(403);
    assertThat(preflight.errorCode()).contains("forbidden");
    assertApiHeaders("preflight", preflight);
    assertThat(preflight.header("Access-Control-Allow-Origin")).isEmpty();
    assertThat(preflight.header("Access-Control-Allow-Credentials")).isEmpty();

    ApiResponse get =
        anonymous().send("GET", EDGE + "/ok", null, Map.of("Origin", ApiTestClient.FOREIGN_ORIGIN));
    assertThat(get.header("Access-Control-Allow-Origin"))
        .as("a cross-origin read is not granted")
        .isEmpty();
  }

  @Test
  @DisplayName("T-SEC-45 an OPTIONS request without a preflight carries them too and shows nothing")
  void plainOptions() {
    ApiResponse options = anonymous().send("OPTIONS", EDGE + "/ok", null, Map.of());
    assertThat(options.status()).isIn(200, 403);
    EVERY_RESPONSE.forEach(
        (name, value) -> assertThat(options.headers(name)).as(name).containsExactly(value));
    assertThat(options.headers("Cache-Control")).containsExactly("no-store");
  }

  @Test
  @DisplayName(
      "T-SEC-56 a request the connector refuses before any filter (header over 16 KB) is 400 and"
          + " shows no version, stack or path")
  void connectorRefusal() {
    ApiResponse response =
        anonymous().send("GET", EDGE + "/ok", null, Map.of("X-Large", "a".repeat(20_000)));
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body())
        .doesNotContainIgnoringCase("tomcat")
        .doesNotContainIgnoringCase("exception")
        .doesNotContain("java.")
        .doesNotContain("org.apache");
  }

  @Test
  @DisplayName("T-SEC-45 index.html, client routes, static files and a missing file carry them too")
  void uiResponses() {
    for (String path : List.of("/", "/index.html", "/apps/3/dashboard")) {
      ApiResponse page = anonymous().get(path);
      assertThat(page.status()).as(path).isEqualTo(200);
      assertEveryResponseHeader(path, page);
      assertThat(page.headers("Cache-Control")).as(path).containsExactly("no-store");
      assertThat(page.header("Content-Type").orElseThrow().toLowerCase(Locale.ROOT))
          .as(path)
          .startsWith("text/html");
    }
    for (String path : List.of("/assets/fixture.js", "/fixture.txt")) {
      ApiResponse file = anonymous().get(path);
      assertThat(file.status()).as(path).isEqualTo(200);
      assertEveryResponseHeader(path, file);
      assertThat(file.headers("Cache-Control")).as(path).doesNotContain("no-store");
    }
    ApiResponse missing = anonymous().get("/assets/missing.js");
    assertThat(missing.status()).isEqualTo(404);
    assertEveryResponseHeader("missing file", missing);
  }

  @Test
  @DisplayName(
      "T-SEC-45 X-Forwarded-Proto is ignored unless the operator trusts the proxy: no HSTS on plain"
          + " HTTP, and a client cannot ask for it")
  void forwardedProtoIgnoredByDefault() {
    ApiResponse response = anonymous().header("X-Forwarded-Proto", "https").get(EDGE + "/ok");
    assertThat(response.header("Strict-Transport-Security")).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-45 behind a trusted proxy that terminated TLS every response carries HSTS, on the API,"
          + " the page, a static file and an error")
  void hstsBehindTrustedProxy() {
    ArgosTestServer proxied =
        SecuritySuite.start(
            Map.of(
                "server.forward-headers-strategy", "native",
                "server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1"));
    try {
      ApiTestClient behindProxy =
          SecuritySuite.client(proxied, null).header("X-Forwarded-Proto", "https");
      for (String path :
          List.of(EDGE + "/ok", EDGE + "/viewer", "/", "/assets/fixture.js", EDGE + "/boom")) {
        ApiResponse response = behindProxy.get(path);
        assertThat(response.headers("Strict-Transport-Security")).as(path).containsExactly(HSTS);
        assertEveryResponseHeader(path, response);
      }
      assertThat(
              SecuritySuite.client(proxied, null)
                  .header("X-Forwarded-Proto", "http")
                  .get(EDGE + "/ok")
                  .header("Strict-Transport-Security"))
          .as("the proxy says plain HTTP")
          .isEmpty();
    } finally {
      proxied.close();
    }
  }

  @Test
  @DisplayName("T-SEC-45 a proxy the operator did not name cannot claim TLS")
  void untrustedProxy() {
    ArgosTestServer proxied =
        SecuritySuite.start(
            Map.of(
                "server.forward-headers-strategy", "native",
                "server.tomcat.remoteip.internal-proxies", "10\\.9\\.8\\.7"));
    try {
      assertThat(
              SecuritySuite.client(proxied, null)
                  .header("X-Forwarded-Proto", "https")
                  .get(EDGE + "/ok")
                  .header("Strict-Transport-Security"))
          .isEmpty();
    } finally {
      proxied.close();
    }
  }
}
