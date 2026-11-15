/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ArgosTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-55 again now that the web layer of WO-24 is in place (Spring Security, the UI page, the
 * security headers): nothing of Actuator on the HTTP port, and the management port keeps its own
 * rules (16 §7.12, WO-20).
 */
@Tag("req:SEC-55")
class ActuatorSecurityTest {

  private static final List<String> DISABLED =
      List.of(
          "env",
          "heapdump",
          "threaddump",
          "loggers",
          "mappings",
          "shutdown",
          "beans",
          "configprops",
          "metrics");

  private final ArgosTestServer server = SecuritySuite.server();

  private HttpResponse<String> management(String path, String authorization) throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.managementPort() + path))
            .timeout(Duration.ofSeconds(10));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("T-SEC-55 8080: every Actuator path is a 404 of the API, not the UI page")
  void httpPort() {
    for (String path :
        List.of("/actuator", "/actuator/health", "/actuator/info", "/actuator/prometheus")) {
      ApiResponse response = SecuritySuite.client(server, "admin").get(path);
      assertThat(response.status()).as(path).isEqualTo(404);
      assertThat(response.body()).as(path).doesNotContain("Argos test page");
    }
    for (String endpoint : DISABLED) {
      ApiResponse response = SecuritySuite.client(server, "admin").get("/actuator/" + endpoint);
      assertThat(response.status()).as(endpoint).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("T-SEC-55 8081: health is open without details, the other endpoints are 404")
  void managementPortHealth() throws Exception {
    HttpResponse<String> health = management("/actuator/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body())
        .contains("\"status\":\"UP\"")
        .doesNotContain("components")
        .doesNotContain("details");
    String bearer = "Bearer " + SecuritySuite.metricsToken();
    for (String endpoint : DISABLED) {
      assertThat(management("/actuator/" + endpoint, null).statusCode())
          .as(endpoint)
          .isEqualTo(404);
      assertThat(management("/actuator/" + endpoint, bearer).statusCode())
          .as(endpoint + " with the token")
          .isEqualTo(404);
    }
  }

  @Test
  @DisplayName("T-SEC-55 8081: prometheus without a token 401, with a wrong one 401, with it 200")
  void managementPortMetrics() throws Exception {
    assertThat(management("/actuator/prometheus", null).statusCode()).isEqualTo(401);
    assertThat(
            management("/actuator/prometheus", "Bearer " + SecuritySuite.newToken()).statusCode())
        .isEqualTo(401);
    assertThat(
            management("/actuator/prometheus", "Bearer " + SecuritySuite.metricsToken())
                .statusCode())
        .isEqualTo(200);
  }
}
