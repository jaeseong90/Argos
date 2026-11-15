/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.session.SessionCookies;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.SetCookie;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * T-SEC-40, the server part (16 §7.1, ADR-0010 C-106 (3), C-107, SEC-40): the exact attributes of
 * the three cookies on login, refresh and logout, no {@code JSESSIONID}, and no access or refresh
 * token in any response body, log line, audit row or database column. The browser part ({@code
 * document.cookie}, localStorage) is WO-30's.
 */
@Tag("req:SEC-40")
@ExtendWith(OutputCaptureExtension.class)
class SessionCookieSecurityTest {

  private static void assertAccessCookie(SetCookie c) {
    assertThat(c.path()).as("Path (C-107)").contains("/");
    assertThat(c.maxAge()).as("Max-Age").contains(900L);
    assertThat(c.httpOnly()).as("HttpOnly").isTrue();
    assertThat(c.secure()).as("Secure").isTrue();
    assertThat(c.sameSite()).as("SameSite").contains("Strict");
  }

  private static void assertRefreshCookie(SetCookie c) {
    assertThat(c.path()).as("Path").contains("/api/v1/auth/refresh");
    assertThat(c.maxAge()).as("Max-Age").contains(86400L);
    assertThat(c.httpOnly()).as("HttpOnly").isTrue();
    assertThat(c.secure()).as("Secure").isTrue();
    assertThat(c.sameSite()).as("SameSite").contains("Strict");
  }

  private static void assertCsrfCookie(SetCookie c) {
    assertThat(c.path()).as("Path").contains("/");
    assertThat(c.maxAge()).as("Max-Age").isEmpty();
    assertThat(c.httpOnly()).as("not HttpOnly: the UI script reads it").isFalse();
    assertThat(c.secure()).as("Secure").isTrue();
    assertThat(c.sameSite()).as("SameSite").contains("Strict");
  }

  private static void assertNoContainerSession(ApiResponse response) {
    assertThat(response.headers("Set-Cookie")).noneMatch(h -> h.contains("JSESSIONID"));
  }

  @Test
  @DisplayName(
      "T-SEC-40 login sets argos_at, argos_rt and argos_csrf with the exact attributes, no"
          + " JSESSIONID, no session token in the body")
  void login() {
    ArgosTestServer server = AuthSuite.server();
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = server.client();
    ApiResponse response = client.login(user.username(), user.password());
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.setCookies())
        .extracting(SetCookie::name)
        .containsExactlyInAnyOrder("argos_at", "argos_rt", "argos_csrf");
    assertAccessCookie(response.setCookie("argos_at").orElseThrow());
    assertRefreshCookie(response.setCookie("argos_rt").orElseThrow());
    assertCsrfCookie(response.setCookie("argos_csrf").orElseThrow());
    assertNoContainerSession(response);
    assertThat(response.body())
        .doesNotContain(response.setCookie("argos_at").orElseThrow().value())
        .doesNotContain(response.setCookie("argos_rt").orElseThrow().value())
        .doesNotContain(user.password());
    JsonNode access = SessionTable.claims(response.setCookie("argos_at").orElseThrow().value());
    assertThat(access.path("sub").asString()).isEqualTo(user.id().toString());
    assertThat(access.path("role").asString()).isEqualTo("viewer");
    assertThat(access.path("exp").asLong() - access.path("iat").asLong()).isEqualTo(900);
    assertThat(client.cookies().matching("/api/v1/auth/me", false))
        .as("the refresh cookie reaches the refresh endpoint only")
        .extracting(c -> c.name())
        .containsExactlyInAnyOrder("argos_at", "argos_csrf");
    assertThat(client.cookies().matching("/api/v1/auth/refresh", false))
        .extracting(c -> c.name())
        .contains("argos_rt");
  }

  @Test
  @DisplayName("T-SEC-40 the response of the login has the shape of 14 §4.1 and §8 example 1")
  void loginBody() {
    TestUser user = AuthSuite.users().create(Role.OPERATOR);
    ApiResponse response = AuthSuite.server().client().login(user.username(), user.password());
    JsonNode data = response.json().path("data");
    assertThat(data.path("user").path("id").asString()).isEqualTo(user.id().toString());
    assertThat(data.path("user").path("username").asString()).isEqualTo(user.username());
    assertThat(data.path("user").path("roles").get(0).asString()).isEqualTo("operator");
    assertThat(data.path("user").path("permissions").isArray()).isTrue();
    assertThat(data.path("roles").get(0).asString()).isEqualTo("operator");
    assertThat(data.path("must_change_password").isBoolean()).isTrue();
    assertThat(data.path("must_change_password").asBoolean()).isFalse();
    assertThat(java.time.Instant.parse(data.path("session_expires_at").asString()))
        .isBetween(
            AuthSuite.clock().instant().plus(Duration.ofHours(8)).minusSeconds(1),
            AuthSuite.clock().instant().plus(Duration.ofHours(8)).plusSeconds(1));
    assertThat(response.json().path("meta").path("request_id").asString())
        .isEqualTo(response.header("X-Request-Id").orElseThrow());
  }

  @Test
  @DisplayName(
      "T-SEC-40 refresh sets argos_at and argos_rt with the same attributes; the body holds no token")
  void refresh() {
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = AuthSuite.server().client();
    client.login(user.username(), user.password());
    ApiResponse response = client.post(SessionCookies.REFRESH_PATH, null);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.setCookies())
        .extracting(SetCookie::name)
        .containsExactlyInAnyOrder("argos_at", "argos_rt");
    assertAccessCookie(response.setCookie("argos_at").orElseThrow());
    assertRefreshCookie(response.setCookie("argos_rt").orElseThrow());
    assertNoContainerSession(response);
    assertThat(response.body())
        .doesNotContain(response.setCookie("argos_at").orElseThrow().value())
        .doesNotContain(response.setCookie("argos_rt").orElseThrow().value());
    assertThat(response.json().path("data").path("session_expires_at").isString()).isTrue();
  }

  @Test
  @DisplayName("T-SEC-40 logout deletes the three cookies, each on its own path; the body is empty")
  void logout() {
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = AuthSuite.server().client();
    client.login(user.username(), user.password());
    ApiResponse response = client.post("/api/v1/auth/logout", null);
    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertNoContainerSession(response);
    assertThat(response.setCookies())
        .extracting(SetCookie::name)
        .containsExactlyInAnyOrder("argos_at", "argos_rt", "argos_csrf");
    SetCookie at = response.setCookie("argos_at").orElseThrow();
    SetCookie rt = response.setCookie("argos_rt").orElseThrow();
    SetCookie csrf = response.setCookie("argos_csrf").orElseThrow();
    assertThat(List.of(at, rt, csrf)).allSatisfy(c -> assertThat(c.deletes()).isTrue());
    assertThat(at.path()).contains("/");
    assertThat(rt.path()).contains("/api/v1/auth/refresh");
    assertThat(csrf.path()).contains("/");
    assertThat(List.of(at, rt, csrf)).allSatisfy(c -> assertThat(c.value()).isEmpty());
    assertThat(client.cookies().all()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-40 GET /auth/csrf sets the cookie and returns its value; a second call keeps it")
  void csrfEndpoint() {
    ApiTestClient client = AuthSuite.server().client();
    ApiResponse first = client.fetchCsrf();
    assertThat(first.status()).isEqualTo(200);
    SetCookie cookie = first.setCookie("argos_csrf").orElseThrow();
    assertCsrfCookie(cookie);
    assertNoContainerSession(first);
    assertThat(first.json().path("data").path("csrf_token").asString()).isEqualTo(cookie.value());
    assertThat(cookie.value()).hasSize(43);
    ApiResponse second = client.fetchCsrf();
    assertThat(second.json().path("data").path("csrf_token").asString()).isEqualTo(cookie.value());
    assertThat(
            AuthSuite.server()
                .client()
                .fetchCsrf()
                .json()
                .path("data")
                .path("csrf_token")
                .asString())
        .isNotEqualTo(cookie.value());
  }

  @Test
  @DisplayName(
      "T-SEC-40 tokens stay out of the database, the audit rows and the log: only the SHA-256 of the"
          + " refresh token, a bcrypt hash of the password")
  void noSecretsAtRest() {
    ServerTestEnvironment env = AuthSuite.environment();
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = AuthSuite.server().client();
    ApiResponse login = client.login(user.username(), user.password());
    String access = login.setCookie("argos_at").orElseThrow().value();
    String refresh = login.setCookie("argos_rt").orElseThrow().value();
    String csrf = login.setCookie("argos_csrf").orElseThrow().value();
    List<SessionTable.Row> rows = SessionTable.ofUser(env, user.id());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).refreshHash())
        .isEqualTo(SessionTable.sha256Hex(refresh))
        .isNotEqualTo(refresh);
    assertThat(SessionTable.passwordHash(env, user.id())).startsWith("$2a$12$");
    assertThat(AuditLog.all(env))
        .allSatisfy(
            row ->
                assertThat(row.toString())
                    .doesNotContain(access)
                    .doesNotContain(refresh)
                    .doesNotContain(csrf)
                    .doesNotContain(user.password()));
    assertThat(AuthSuite.serverLog())
        .doesNotContain(access)
        .doesNotContain(refresh)
        .doesNotContain(csrf)
        .doesNotContain(user.password());
  }

  @Test
  @DisplayName("T-SEC-40 argos.security.cookie.secure=false drops Secure and warns at the start")
  void notSecureWarns(CapturedOutput output) {
    Map<String, Object> settings = Map.of("argos.security.cookie.secure", "false");
    try (ArgosTestServer plain = ArgosTestServer.start(AuthSuite.environment(), settings)) {
      assertThat(output).contains("argos.security.cookie.secure=false");
      TestUser user = AuthSuite.users().create(Role.VIEWER);
      ApiResponse login = plain.client().login(user.username(), user.password());
      assertThat(login.status()).isEqualTo(200);
      assertThat(login.setCookies()).allSatisfy(c -> assertThat(c.secure()).isFalse());
      assertThat(login.setCookie("argos_at").orElseThrow().httpOnly()).isTrue();
      assertThat(login.setCookie("argos_at").orElseThrow().sameSite()).contains("Strict");
    }
  }
}
