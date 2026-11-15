/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-56, the bodies (14 §2.6, 16 §7.14): the exact error body of each status against the
 * snapshot file, with nothing of the server's internals in it; the 500 comes from a real exception
 * whose message holds SQL, a class name, a path and a host.
 */
@Tag("req:SEC-56")
class ErrorBodySnapshotSecurityTest {

  private static final String EDGE = "/api/v1/test-edge";

  private final ArgosTestServer server = SecuritySuite.server();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  private ApiTestClient anonymous() {
    return SecuritySuite.client(server, null);
  }

  private static Map<Integer, String> snapshots() throws IOException {
    Map<Integer, String> bodies = new LinkedHashMap<>();
    try (InputStream in =
        ErrorBodySnapshotSecurityTest.class
            .getClassLoader()
            .getResourceAsStream("error-body-snapshots.txt")) {
      assertThat(in).isNotNull();
      new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(l -> !l.isBlank() && !l.startsWith("#"))
          .forEach(
              line -> {
                String[] parts = line.split("\\s+", 2);
                bodies.put(Integer.valueOf(parts[0]), parts[1]);
              });
    }
    return bodies;
  }

  private Map<Integer, ApiResponse> errors() {
    Map<Integer, ApiResponse> responses = new LinkedHashMap<>();
    responses.put(400, anonymous().get(EDGE + "/error/limit_exceeded"));
    responses.put(401, anonymous().get(EDGE + "/viewer"));
    responses.put(403, SecuritySuite.client(server, "viewer").get(EDGE + "/admin"));
    responses.put(404, anonymous().get("/api/v1/no-such-endpoint"));
    responses.put(409, anonymous().get(EDGE + "/error/invalid_state"));
    responses.put(422, anonymous().get(EDGE + "/error/password_policy"));
    responses.put(423, anonymous().get(EDGE + "/error/account_locked"));
    responses.put(500, anonymous().get(EDGE + "/boom"));
    ApiTestClient user = SecuritySuite.client(server, "viewer");
    ApiResponse last = null;
    for (int i = 0; i < 11; i++) {
      last = user.get(EDGE + "/heavy");
    }
    responses.put(429, last);
    return responses;
  }

  @Test
  @DisplayName(
      "T-SEC-56 the bodies of 400, 401, 403, 404, 409, 422, 423, 429 and 500 equal the snapshot")
  void snapshot() throws IOException {
    Map<Integer, String> snapshots = snapshots();
    Map<Integer, ApiResponse> errors = errors();
    assertThat(errors.keySet()).containsExactlyInAnyOrderElementsOf(snapshots.keySet());
    errors.forEach(
        (status, response) -> {
          assertThat(response.status()).isEqualTo(status);
          String id = response.header("X-Request-Id").orElseThrow();
          assertThat(response.body().replace(id, "<request_id>"))
              .as(status + " body")
              .isEqualTo(snapshots.get(status));
        });
  }

  @Test
  @DisplayName(
      "T-SEC-56 no error body shows a stack, class name, SQL, file path or host, whatever the"
          + " exception said")
  void nothingLeaks() {
    errors()
        .forEach(
            (status, response) ->
                assertThat(response.body())
                    .as(status + " body")
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContain("java.")
                    .doesNotContain("io.argos")
                    .doesNotContainIgnoringCase("select ")
                    .doesNotContain("password_hash")
                    .doesNotContain("/opt/")
                    .doesNotContain("pg-internal")
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("trace")
                    .doesNotContain("\"path\"")
                    .doesNotContain("timestamp"));
  }

  @Test
  @DisplayName("T-SEC-56 a direct request to the error page is 404 not_found in the same body")
  void directRequestToTheErrorPage() {
    ApiResponse response = anonymous().get("/error");
    assertThat(response.status()).isEqualTo(404);
    assertThat(response.errorCode()).contains("not_found");
  }
}
