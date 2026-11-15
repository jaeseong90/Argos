/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-048, the whole way, as the documented manual scenario goes (WO-24 acceptance): setup status,
 * first administrator with the token of the log, CSRF token, login, who am I, logout, 401; on the
 * real server with real PostgreSQL and ClickHouse.
 */
@Tag("req:SEC-47")
@Tag("req:SEC-48")
@Tag("req:F-S05")
class AuthScenarioIT {

  @Test
  @DisplayName(
      "T-SV-048 setup status, first administrator, CSRF, login, me, logout, 401; every step audited")
  void firstUse() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env)) {
      ApiTestClient browser = server.client();

      ApiResponse status = browser.get("/api/v1/setup/status");
      assertThat(status.status()).isEqualTo(200);
      assertThat(status.json().path("data").path("needs_setup").asBoolean()).isTrue();

      String token =
          Files.readString(
                  Paths.get(env.springProperties().get("argos.auth.setup-token-file")),
                  StandardCharsets.UTF_8)
              .strip();
      String password = TestUsers.newPassword("admin");
      ApiResponse created =
          browser.post(
              "/api/v1/setup/admin",
              Map.of(
                  "setup_token",
                  token,
                  "username",
                  "admin",
                  "password",
                  password,
                  "ip_mode",
                  "masked"));
      assertThat(created.status()).as(created.toString()).isEqualTo(201);

      ApiTestClient other = server.client();
      assertThat(other.fetchCsrf().status()).isEqualTo(200);
      ApiResponse login = other.login("admin", password);
      assertThat(login.status()).isEqualTo(200);
      assertThat(login.json().path("data").path("must_change_password").asBoolean()).isFalse();

      ApiResponse me = other.get("/api/v1/auth/me");
      assertThat(me.status()).isEqualTo(200);
      assertThat(me.json().path("data").path("user").path("username").asString())
          .isEqualTo("admin");
      assertThat(me.json().path("data").path("roles").get(0).asString()).isEqualTo("admin");
      assertThat(me.json().path("data").path("kiosk_allowed").asBoolean()).isFalse();
      assertThat(me.json().path("data").path("session_expires_at").isString()).isTrue();

      assertThat(other.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
      ApiResponse after = other.get("/api/v1/auth/me");
      assertThat(after.status()).isEqualTo(401);
      assertThat(after.errorCode()).contains("unauthenticated");

      assertThat(
              browser
                  .get("/api/v1/setup/status")
                  .json()
                  .path("data")
                  .path("needs_setup")
                  .asBoolean())
          .isFalse();
      for (String event :
          List.of(
              "SETUP_COMPLETED", "USER_CREATED", "LOGIN_SUCCESS", "LOGOUT", "SESSION_REVOKED")) {
        assertThat(AuditLog.rows(env, event)).as(event).isNotEmpty();
      }
      assertThat(AuditLog.rows(env, "LOGIN_SUCCESS").get(0).ip()).isEqualTo("127.0.0.1");
      assertThat(AuditLog.rows(env, "LOGIN_SUCCESS").get(0).userAgent())
          .isEqualTo("argos-api-test-client");
    }
  }
}
