/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The administration API of users (14 §4.14, ADR-0010 C-109, 16 §7.10) on the real server with real
 * PostgreSQL: request and response shapes, validation, audit records, the rules that keep the
 * installation administrable. The session effects are in {@code SessionLifecycleSecurityTest}.
 */
@Tag("req:SEC-49")
@Tag("req:F-S05")
class AdminUsersIT {

  private static final String USERS = "/api/v1/admin/users";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static TestUsers users;
  private static ApiTestClient admin;
  private static TestUser adminUser;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.ratelimit.login-per-ip-per-minute", "10000",
                "argos.ratelimit.api-per-user-per-second", "10000",
                "argos.ratelimit.api-burst-per-user", "10000"));
    users = new TestUsers(env);
    adminUser = users.create(Role.ADMIN);
    admin = server.client();
    assertThat(admin.login(adminUser.username(), adminUser.password()).status()).isEqualTo(200);
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static ApiTestClient login(TestUser user) {
    ApiTestClient client = server.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static void assertInvalid(ApiResponse response, String field, String reason) {
    assertThat(response.status()).as(response.toString()).isEqualTo(400);
    assertThat(response.json().path("error").path("details").toString())
        .contains("\"field\":\"" + field + "\"")
        .contains("\"reason\":\"" + reason + "\"");
  }

  @Test
  @DisplayName(
      "T-SEC-49b administrators only: anonymous 401, viewer and operator 403 on every operation")
  void rolesOnEveryOperation() {
    ApiTestClient viewer = login(users.create(Role.VIEWER));
    ApiTestClient operator = login(users.create(Role.OPERATOR));
    ApiTestClient anonymous = server.client();
    anonymous.fetchCsrf();
    String one = USERS + "/" + UUID.randomUUID();
    for (ApiTestClient client : List.of(viewer, operator, anonymous)) {
      int expected = client == anonymous ? 401 : 403;
      assertThat(client.get(USERS).status()).isEqualTo(expected);
      assertThat(client.post(USERS, Map.of("username", "x", "roles", List.of("viewer"))).status())
          .isEqualTo(expected);
      assertThat(client.patch(one, Map.of("enabled", false)).status()).isEqualTo(expected);
      assertThat(client.post(one + "/sessions/revoke", null).status()).isEqualTo(expected);
      assertThat(client.post(one + "/password/reset", Map.of("password", "x")).status())
          .isEqualTo(expected);
      assertThat(client.post(one + "/unlock", null).status()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName(
      "T-SV-048a the list shows users without any hash, with roles, state and kiosk approval")
  void list() {
    TestUser kiosk = users.create(Role.VIEWER, o -> o.kiosk("10.0.0.0/8"));
    ApiResponse response = admin.get(USERS);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("$2a$").doesNotContain("password_hash");
    JsonNode data = response.json().path("data");
    assertThat(data.isArray()).isTrue();
    JsonNode row = null;
    for (JsonNode candidate : data) {
      if (kiosk.username().equals(candidate.path("username").asString())) {
        row = candidate;
      }
    }
    assertThat(row).isNotNull();
    assertThat(row.path("id").asString()).isEqualTo(kiosk.id().toString());
    assertThat(row.path("roles").get(0).asString()).isEqualTo("viewer");
    assertThat(row.path("enabled").asBoolean()).isTrue();
    assertThat(row.path("kiosk_session").path("allowed").asBoolean()).isTrue();
    assertThat(row.path("kiosk_session").path("ip_cidrs").get(0).asString())
        .isEqualTo("10.0.0.0/8");
    assertThat(response.json().path("meta").path("request_id").isString()).isTrue();
    assertThat(response.json().path("meta").has("truncated")).isFalse();
  }

  @Test
  @DisplayName(
      "T-SV-048a creating without a password: 201, a generated password once, a forced change, only"
          + " the hash stored, audited; an email field, which the API does not offer, is ignored"
          + " and stored nowhere")
  void createGenerated() {
    String name = TestUsers.newUsername();
    ApiResponse response =
        admin.post(
            USERS,
            Map.of(
                "username",
                name,
                // Not part of the request schema (no column holds an address): an unknown field.
                "email",
                "not-stored@example.test",
                "roles",
                List.of("operator"),
                "must_change_password",
                false));
    assertThat(response.status()).isEqualTo(201);
    assertThat(response.body()).doesNotContain("not-stored");
    JsonNode data = response.json().path("data");
    String initial = data.path("initial_password").asString();
    assertThat(initial).hasSize(43);
    assertThat(data.path("user").path("must_change_password").asBoolean())
        .as("a password the administrator saw must change")
        .isTrue();
    assertThat(data.path("user").path("roles").get(0).asString()).isEqualTo("operator");
    UUID id = UUID.fromString(data.path("user").path("id").asString());
    assertThat(id.version()).isEqualTo(4);
    assertThat(SessionTable.passwordHash(env, id)).startsWith("$2a$12$").doesNotContain(initial);
    assertThat(admin.get(USERS).body()).doesNotContain(initial).doesNotContain("not-stored");
    assertThat(AuditLog.rows(env, "USER_CREATED"))
        .anyMatch(
            r ->
                id.toString().equals(r.targetId())
                    && adminUser.id().toString().equals(r.actorId())
                    && r.after().contains("OPERATOR")
                    && !r.after().contains(initial)
                    && !r.after().contains("not-stored"));
    assertThat(
            server
                .client()
                .login(name, initial)
                .json()
                .path("data")
                .path("must_change_password")
                .asBoolean())
        .isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-048a creating with a password: nothing is echoed, the policy applies, the flag is honoured")
  void createWithPassword() {
    String name = TestUsers.newUsername();
    String password = TestUsers.newPassword(name);
    ApiResponse response =
        admin.post(
            USERS,
            Map.of(
                "username",
                name,
                "roles",
                List.of("viewer"),
                "must_change_password",
                false,
                "password",
                password));
    assertThat(response.status()).isEqualTo(201);
    assertThat(response.body()).doesNotContain(password).doesNotContain("initial_password");
    assertThat(response.json().path("data").path("user").path("must_change_password").asBoolean())
        .isFalse();
    assertThat(server.client().login(name, password).status()).isEqualTo(200);

    ApiResponse weak =
        admin.post(
            USERS,
            Map.of(
                "username", TestUsers.newUsername(),
                "roles", List.of("viewer"),
                "password", "unbelievable"));
    assertThat(weak.status()).isEqualTo(422);
    assertThat(weak.errorCode()).contains("password_policy");
  }

  @Test
  @DisplayName("T-SV-048a creating: bad names and roles are 400, a taken name 409 in any case")
  void createValidation() {
    assertInvalid(admin.post(USERS, Map.of("roles", List.of("viewer"))), "username", "required");
    assertInvalid(
        admin.post(USERS, Map.of("username", "with blank", "roles", List.of("viewer"))),
        "username",
        "invalid");
    assertInvalid(
        admin.post(USERS, Map.of("username", "<script>", "roles", List.of("viewer"))),
        "username",
        "invalid");
    assertInvalid(
        admin.post(USERS, Map.of("username", "x".repeat(65), "roles", List.of("viewer"))),
        "username",
        "invalid");
    assertInvalid(admin.post(USERS, Map.of("username", "okname")), "roles", "required");
    assertInvalid(
        admin.post(USERS, Map.of("username", "okname", "roles", List.of("root"))),
        "roles",
        "invalid");
    assertInvalid(
        admin.post(USERS, Map.of("username", "okname", "roles", List.of("public"))),
        "roles",
        "invalid");
    assertInvalid(
        admin.post(USERS, Map.of("username", "okname", "roles", List.of("viewer", "admin"))),
        "roles",
        "exactly_one");
    TestUser taken = users.create(Role.VIEWER);
    ApiResponse conflict =
        admin.post(
            USERS,
            Map.of(
                "username", taken.username().toUpperCase(Locale.ROOT), "roles", List.of("viewer")));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.errorCode()).contains("already_exists");
  }

  @Test
  @DisplayName("T-SV-048a identifiers are UUIDv4: anything else is 400, an unknown one 404")
  void identifiers() {
    for (String bad : List.of("not-a-uuid", "00000000-0000-1000-8000-000000000000")) {
      ApiResponse response = admin.patch(USERS + "/" + bad, Map.of("enabled", true));
      assertThat(response.status()).as(bad).isEqualTo(400);
      assertThat(response.body()).as("the value is not echoed").doesNotContain(bad);
    }
    assertThat(admin.patch(USERS + "/1", Map.of("enabled", true)).status()).isEqualTo(400);
    String unknown = USERS + "/" + UUID.randomUUID();
    assertThat(admin.patch(unknown, Map.of("enabled", true)).status()).isEqualTo(404);
    assertThat(admin.post(unknown + "/sessions/revoke", null).status()).isEqualTo(404);
    assertThat(admin.post(unknown + "/unlock", null).status()).isEqualTo(404);
    String password = TestUsers.newPassword("x");
    assertThat(admin.post(unknown + "/password/reset", Map.of("password", password)).status())
        .isEqualTo(404);
  }

  @Test
  @DisplayName(
      "T-SV-048a a role change is audited with before and after; an empty change is refused; a"
          + " wrong role field does not pass silently")
  void roleChange() {
    TestUser user = users.create(Role.VIEWER);
    String path = USERS + "/" + user.id();
    ApiResponse changed = admin.patch(path, Map.of("roles", List.of("operator")));
    assertThat(changed.status()).isEqualTo(200);
    assertThat(changed.json().path("data").path("roles").get(0).asString()).isEqualTo("operator");
    assertThat(AuditLog.rows(env, "ROLE_CHANGED"))
        .anyMatch(
            r ->
                user.id().toString().equals(r.targetId())
                    && r.before().contains("VIEWER")
                    && r.after().contains("OPERATOR")
                    && adminUser.id().toString().equals(r.actorId()));
    assertThat(admin.patch(path, Map.of()).status()).isEqualTo(400);
    assertThat(admin.patch(path, Map.of("role", "admin")).status()).isEqualTo(400);
    assertInvalid(admin.patch(path, Map.of("roles", List.of())), "roles", "invalid");
  }

  @Test
  @DisplayName(
      "T-SEC-49b pii.view goes to administrators only, is granted explicitly, and ends with the role")
  void piiView() {
    TestUser viewer = users.create(Role.VIEWER);
    assertInvalid(
        admin.patch(USERS + "/" + viewer.id(), Map.of("permissions", List.of("pii.view"))),
        "permissions",
        "admin_only");
    assertInvalid(
        admin.patch(USERS + "/" + viewer.id(), Map.of("permissions", List.of("root"))),
        "permissions",
        "invalid");

    TestUser second = users.create(Role.ADMIN);
    ApiTestClient secondClient = login(second);
    assertThat(
            secondClient.get("/api/v1/auth/me").json().path("data").path("permissions").isEmpty())
        .as("not by default (C-106 (6))")
        .isTrue();
    String path = USERS + "/" + second.id();
    ApiResponse granted = admin.patch(path, Map.of("permissions", List.of("pii.view")));
    assertThat(granted.status()).isEqualTo(200);
    assertThat(granted.json().path("data").path("permissions").get(0).asString())
        .isEqualTo("pii.view");
    assertThat(secondClient.get("/api/v1/auth/me").status())
        .as("a permission change revokes the sessions")
        .isEqualTo(401);
    ApiTestClient again = login(second);
    assertThat(
            again.get("/api/v1/auth/me").json().path("data").path("permissions").get(0).asString())
        .isEqualTo("pii.view");
    assertThat(
            SessionTable.claims(again.cookie("argos_at").orElseThrow())
                .path("perm")
                .get(0)
                .asString())
        .isEqualTo("pii.view");

    ApiResponse demoted = admin.patch(path, Map.of("roles", List.of("operator")));
    assertThat(demoted.status()).isEqualTo(200);
    assertThat(demoted.json().path("data").path("permissions").isEmpty()).isTrue();
    assertThat(AuditLog.rows(env, "USER_UPDATED"))
        .anyMatch(
            r -> second.id().toString().equals(r.targetId()) && r.after().contains("pii.view"));
  }

  @Test
  @DisplayName("T-SEC-42b kiosk approval: viewers only, valid CIDRs required, stored normalised")
  void kioskApproval() {
    TestUser viewer = users.create(Role.VIEWER);
    String path = USERS + "/" + viewer.id();
    assertInvalid(
        admin.patch(path, Map.of("kiosk_session", Map.of("allowed", true))),
        "kiosk_session.ip_cidrs",
        "required");
    assertInvalid(
        admin.patch(
            path,
            Map.of("kiosk_session", Map.of("allowed", true, "ip_cidrs", List.of("example.com")))),
        "kiosk_session.ip_cidrs",
        "invalid");
    assertInvalid(
        admin.patch(path, Map.of("kiosk_session", Map.of("ip_cidrs", List.of("10.0.0.0/8")))),
        "kiosk_session.allowed",
        "required");
    TestUser operator = users.create(Role.OPERATOR);
    assertInvalid(
        admin.patch(
            USERS + "/" + operator.id(),
            Map.of("kiosk_session", Map.of("allowed", true, "ip_cidrs", List.of("10.0.0.0/8")))),
        "kiosk_session",
        "viewer_only");

    ApiResponse ok =
        admin.patch(
            path,
            Map.of(
                "kiosk_session",
                Map.of("allowed", true, "ip_cidrs", List.of("10.1.2.3/8", "192.168.0.7"))));
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    JsonNode kiosk = ok.json().path("data").path("kiosk_session");
    assertThat(kiosk.path("allowed").asBoolean()).isTrue();
    assertThat(kiosk.path("ip_cidrs").toString()).contains("10.0.0.0/8").contains("192.168.0.7/32");
    ApiResponse off = admin.patch(path, Map.of("kiosk_session", Map.of("allowed", false)));
    assertThat(off.status()).isEqualTo(200);
    assertThat(off.json().path("data").path("kiosk_session").path("allowed").asBoolean()).isFalse();
    assertThat(off.json().path("data").path("kiosk_session").path("ip_cidrs").isEmpty()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-048a a disabled user can be enabled again; a password reset needs a valid password")
  void enableAndReset() {
    TestUser user = users.create(Role.VIEWER);
    String path = USERS + "/" + user.id();
    assertThat(admin.patch(path, Map.of("enabled", false)).status()).isEqualTo(200);
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(401);
    ApiResponse enabled = admin.patch(path, Map.of("enabled", true));
    assertThat(enabled.status()).isEqualTo(200);
    assertThat(enabled.json().path("data").path("enabled").asBoolean()).isTrue();
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(200);
    assertThat(AuditLog.rows(env, "USER_UPDATED"))
        .anyMatch(r -> user.id().toString().equals(r.targetId()) && r.after().contains("enabled"));

    assertInvalid(admin.post(path + "/password/reset", null), "password", "required");
    assertThat(admin.post(path + "/password/reset", Map.of("password", "short")).status())
        .isEqualTo(422);
  }

  @Test
  @DisplayName(
      "T-SV-048a the last enabled administrator can be neither demoted nor disabled: 409 invalid_state")
  void lastAdministrator() {
    try (ServerTestEnvironment own = ServerTestEnvironment.start();
        ArgosTestServer ownServer = ArgosTestServer.start(own)) {
      TestUser only = new TestUsers(own).create(Role.ADMIN);
      ApiTestClient client = ownServer.client();
      assertThat(client.login(only.username(), only.password()).status()).isEqualTo(200);
      String path = USERS + "/" + only.id();
      ApiResponse demote = client.patch(path, Map.of("roles", List.of("viewer")));
      assertThat(demote.status()).isEqualTo(409);
      assertThat(demote.errorCode()).contains("invalid_state");
      ApiResponse disable = client.patch(path, Map.of("enabled", false));
      assertThat(disable.status()).isEqualTo(409);
      assertThat(client.get("/api/v1/auth/me").status()).as("nothing changed").isEqualTo(200);

      TestUser second = new TestUsers(own).create(Role.ADMIN);
      assertThat(client.patch(path, Map.of("enabled", false)).status())
          .as("with a second administrator it is allowed")
          .isEqualTo(200);
      ApiTestClient secondClient = ownServer.client();
      assertThat(secondClient.login(second.username(), second.password()).status()).isEqualTo(200);
      assertThat(
              secondClient
                  .patch(USERS + "/" + second.id(), Map.of("roles", List.of("viewer")))
                  .status())
          .as("now this one is the last")
          .isEqualTo(409);
    }
  }
}
