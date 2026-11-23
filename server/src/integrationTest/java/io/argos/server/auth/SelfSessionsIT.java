/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A user's own sessions and the user delete on a real server and PostgreSQL (WO-26; 16 §7.4, §7.10,
 * §11.1, ADR-0010 C-136 (3)): {@code GET /auth/sessions} lists the caller's live sessions with the
 * current one flagged and no token, {@code DELETE /auth/sessions/{sessionId}} ends one of them at
 * once (another user's session is not found), and {@code DELETE /admin/users/{id}} anonymises and
 * disables the account, ends its sessions and frees its name, never for the caller's own account.
 */
@Tag("integration")
@Tag("req:SEC-42")
@Tag("req:C-136")
class SelfSessionsIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.ratelimit.login-per-ip-per-minute", "100000",
                "argos.ratelimit.api-per-user-per-second", "100000",
                "argos.ratelimit.api-burst-per-user", "100000"));
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static TestUsers.TestUser user(Role role) {
    return new TestUsers(env).create(role, o -> o.mustChangePassword(false));
  }

  private static ApiTestClient browser(TestUsers.TestUser user) {
    ApiTestClient client = server.client().userAgent("SelfSessionsIT/" + UUID.randomUUID());
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static JsonNode sessions(ApiTestClient client) {
    ApiResponse r = client.get("/api/v1/auth/sessions");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json().path("data");
  }

  private static String current(JsonNode sessions) {
    List<String> ids = new ArrayList<>();
    for (JsonNode s : sessions) {
      if (s.path("current").asBoolean()) {
        ids.add(s.path("session_id").asString());
      }
    }
    assertThat(ids).hasSize(1);
    return ids.get(0);
  }

  @Test
  @DisplayName(
      "T-SV-052a C-136 (3) GET /auth/sessions lists the caller's live sessions, one current, UUIDv4 ids, no"
          + " token; DELETE of another own session ends it at once, the caller's stays")
  void listAndRevokeOwn() {
    TestUsers.TestUser u = user(Role.VIEWER);
    ApiTestClient a = browser(u);
    ApiTestClient b = browser(u);
    JsonNode fromA = sessions(a);
    assertThat(fromA).hasSize(2);
    String idA = current(fromA);
    String idB = current(sessions(b));
    assertThat(idA).isNotEqualTo(idB);
    for (JsonNode s : fromA) {
      assertThat(UUID.fromString(s.path("session_id").asString()).version()).isEqualTo(4);
      assertThat(s.path("created_at").asString()).isNotBlank();
      assertThat(s.path("expires_at").asString()).isNotBlank();
      assertThat(s.path("user_agent").asString()).startsWith("SelfSessionsIT/");
      assertThat(s.has("refresh_hash")).isFalse();
      assertThat(s.has("token")).isFalse();
    }
    String refresh = b.cookie(ApiTestClient.REFRESH_COOKIE).orElseThrow();
    assertThat(sessionsBody(a)).doesNotContain(refresh);

    ApiResponse revoked = a.delete("/api/v1/auth/sessions/" + idB);
    assertThat(revoked.status()).as(revoked.toString()).isEqualTo(204);
    ApiResponse afterB = b.get("/api/v1/auth/me");
    assertThat(afterB.status()).isEqualTo(401);
    assertThat(afterB.errorCode()).hasValue("session_revoked");
    assertThat(a.get("/api/v1/auth/me").status()).isEqualTo(200);
    JsonNode left = sessions(a);
    assertThat(left).hasSize(1);
    assertThat(left.get(0).path("session_id").asString()).isEqualTo(idA);
    assertThat(AuditLog.rows(env, "SESSION_REVOKED"))
        .anySatisfy(r -> assertThat(r.reason()).isEqualTo("logout"));
  }

  private static String sessionsBody(ApiTestClient client) {
    return client.get("/api/v1/auth/sessions").body();
  }

  @Test
  @DisplayName(
      "T-SV-052b C-136 (3) another user's session is 404 and stays alive; a malformed id is 400; ending"
          + " the current session works like a logout")
  void othersAndCurrent() {
    ApiTestClient mine = browser(user(Role.OPERATOR));
    ApiTestClient theirs = browser(user(Role.VIEWER));
    String theirId = current(sessions(theirs));
    ApiResponse other = mine.delete("/api/v1/auth/sessions/" + theirId);
    assertThat(other.status()).isEqualTo(404);
    assertThat(theirs.get("/api/v1/auth/me").status()).isEqualTo(200);
    assertThat(mine.delete("/api/v1/auth/sessions/not-a-uuid").status()).isEqualTo(400);
    assertThat(server.client().get("/api/v1/auth/sessions").status()).isEqualTo(401);

    String myId = current(sessions(mine));
    assertThat(mine.delete("/api/v1/auth/sessions/" + myId).status()).isEqualTo(204);
    ApiResponse after = mine.get("/api/v1/auth/me");
    assertThat(after.status()).isEqualTo(401);
  }

  @Test
  @DisplayName(
      "T-SV-052c C-136 (3) DELETE /admin/users/{id}: 204, the account's sessions end, it is no longer"
          + " listed or able to log in, its name is free; own account 409, operator 403")
  void deleteUser() throws Exception {
    ApiTestClient admin = browser(user(Role.ADMIN));
    TestUsers.TestUser victim = user(Role.OPERATOR);
    ApiTestClient victimBrowser = browser(victim);
    ApiTestClient operator = browser(user(Role.OPERATOR));
    assertThat(operator.delete("/api/v1/admin/users/" + victim.id()).status()).isEqualTo(403);

    ApiResponse deleted = admin.delete("/api/v1/admin/users/" + victim.id());
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    ApiResponse afterVictim = victimBrowser.get("/api/v1/auth/me");
    assertThat(afterVictim.status()).isEqualTo(401);
    assertThat(afterVictim.errorCode()).hasValue("session_revoked");

    JsonNode users = admin.get("/api/v1/admin/users").json().path("data");
    for (JsonNode u : users) {
      assertThat(u.path("id").asString()).isNotEqualTo(victim.id().toString());
    }
    assertThat(server.client().login(victim.username(), victim.password()).status()).isEqualTo(401);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT username, enabled, deleted_at IS NOT NULL FROM argos.users WHERE id = ?")) {
      st.setObject(1, victim.id());
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("deleted-" + victim.id());
        assertThat(rs.getBoolean(2)).isFalse();
        assertThat(rs.getBoolean(3)).isTrue();
      }
    }
    assertThat(admin.delete("/api/v1/admin/users/" + victim.id()).status()).isEqualTo(404);
    assertThat(admin.patch("/api/v1/admin/users/" + victim.id(), Map.of("enabled", true)).status())
        .isEqualTo(404);

    ApiResponse reused =
        admin.post(
            "/api/v1/admin/users",
            Map.of("username", victim.username(), "roles", List.of("viewer")));
    assertThat(reused.status()).as("the name is free again: " + reused).isEqualTo(201);

    String selfId =
        admin.get("/api/v1/auth/me").json().path("data").path("user").path("id").asString();
    ApiResponse self = admin.delete("/api/v1/admin/users/" + selfId);
    assertThat(self.status()).isEqualTo(409);
    assertThat(self.body()).contains("self");
    assertThat(admin.delete("/api/v1/admin/users/not-a-uuid").status()).isEqualTo(400);

    assertThat(AuditLog.rows(env, "USER_DELETED"))
        .anySatisfy(
            r -> {
              assertThat(r.targetId()).isEqualTo(victim.id().toString());
              assertThat(r.targetName()).isEqualTo(victim.username());
            });
  }
}
