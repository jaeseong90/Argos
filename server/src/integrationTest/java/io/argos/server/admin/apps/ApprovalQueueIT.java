/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.apps;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.server.admin.token.IssuedToken;
import io.argos.server.admin.token.TokenService;
import io.argos.server.audit.Actor;
import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The approval queue through the administration API on a real server, PostgreSQL and gRPC listener
 * (WO-26; 14 §4.14, F-S30, 12 §4.4, ADR-0010 C-155 (3)): a Hello of an environment that does not
 * register automatically is queued and listed; an approval returns the new application and its
 * token once, and that token opens an agent session; a rejection deletes the request; a name that
 * belongs to another application is refused.
 */
@Tag("integration")
@Tag("req:F-S30")
@Tag("req:C-155")
class ApprovalQueueIT {

  private static final Pattern FORMAT = Pattern.compile("^argos_[A-Za-z0-9_-]{43}$");

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient admin;
  private static ApiTestClient operator;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

  @BeforeAll
  static void start() {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.agent.auto-register.qa", "false",
                "argos.agent.auto-register.uat", "false",
                "argos.ratelimit.login-per-ip-per-minute", "100000",
                "argos.ratelimit.api-per-user-per-second", "100000",
                "argos.ratelimit.api-burst-per-user", "100000"));
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    admin = login(Role.ADMIN);
    operator = login(Role.OPERATOR);
  }

  @AfterAll
  static void stop() {
    AGENTS.forEach(FakeAgent::close);
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
    ApiTestClient client = server.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static String unique(String prefix) {
    return prefix + "-" + Long.toHexString(System.nanoTime());
  }

  private static int homeApp(String name) throws Exception {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, "prod");
    }
  }

  private static FakeAgent agent(String token, String app, String appEnv, String instance) {
    FakeAgent a =
        FakeAgent.builder(channel)
            .token(token)
            .appName(app)
            .env(appEnv)
            .instanceName(instance)
            .build();
    AGENTS.add(a);
    return a;
  }

  /** Queues (name, env) through a refused Hello of the home application's token. */
  private static JsonNode queue(String name, String appEnv) throws Exception {
    IssuedToken home = server.bean(TokenService.class).issue(Actor.system(), homeApp(name), null);
    HelloResult r = agent(home.token(), name, appEnv, appEnv + "-host:8080").connect();
    assertThat(r.accepted()).as(r.toString()).isFalse();
    assertThat(r.rejectReason()).isEqualTo(RejectReason.APP_NOT_REGISTERED);
    ApiResponse list = admin.get("/api/v1/admin/pending-registrations?env=" + appEnv);
    assertThat(list.status()).as(list.toString()).isEqualTo(200);
    for (JsonNode n : list.json().path("data")) {
      if (n.path("name").asString().equals(name)) {
        return n;
      }
    }
    throw new AssertionError("(" + name + ", " + appEnv + ") is not queued");
  }

  @Test
  @DisplayName(
      "T-SV-043a GET pending-registrations lists the refused Hello; approve returns the app and"
          + " its token once (no-store), and that token opens an agent session")
  void approve() throws Exception {
    String name = unique("appr");
    JsonNode request = queue(name, "qa");
    assertThat(request.path("env").asString()).isEqualTo("qa");
    assertThat(request.path("attempts").asInt()).isEqualTo(1);
    assertThat(request.path("home_app_id").asInt()).isPositive();
    int id = request.path("id").asInt();

    assertThat(operator.get("/api/v1/admin/pending-registrations").status()).isEqualTo(403);
    assertThat(
            operator.post("/api/v1/admin/pending-registrations/" + id + "/approve", null).status())
        .isEqualTo(403);

    ApiResponse approved =
        admin.post(
            "/api/v1/admin/pending-registrations/" + id + "/approve",
            Map.of("group", "batch", "apdex_t_ms", 700));
    assertThat(approved.status()).as(approved.toString()).isEqualTo(201);
    assertThat(approved.header("Cache-Control")).hasValue("no-store");
    JsonNode app = approved.json().path("data").path("app");
    assertThat(app.path("name").asString()).isEqualTo(name);
    assertThat(app.path("env").asString()).isEqualTo("qa");
    assertThat(app.path("group").asString()).isEqualTo("batch");
    assertThat(app.path("apdex_t_ms").asInt()).isEqualTo(700);
    assertThat(app.path("registered_from_app_id").asInt())
        .isEqualTo(request.path("home_app_id").asInt());
    JsonNode token = approved.json().path("data").path("token");
    String text = token.path("token").asString();
    assertThat(text).matches(FORMAT);
    assertThat(token.path("prefix").asString()).isEqualTo(text.substring(0, 14));

    HelloResult hello = agent(text, name, "qa", "qa-new:8080").connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    assertThat(hello.hello().getAppId()).isEqualTo(app.path("app_id").asInt());

    ApiResponse list = admin.get("/api/v1/admin/pending-registrations?env=qa");
    for (JsonNode n : list.json().path("data")) {
      assertThat(n.path("name").asString()).isNotEqualTo(name);
    }
    assertThat(admin.post("/api/v1/admin/pending-registrations/" + id + "/approve", null).status())
        .as("the request is gone")
        .isEqualTo(404);
    assertThat(AuditLog.rows(env, "APP_APPROVED"))
        .anySatisfy(r -> assertThat(r.targetName()).isEqualTo(name));
    assertThat(AuditLog.rows(env, "TOKEN_ISSUED"))
        .anySatisfy(
            r ->
                assertThat(r.targetId() + " " + r.targetName() + " " + r.after())
                    .containsAnyOf(
                        token.path("prefix").asString(),
                        "\"app_id\": " + app.path("app_id").asInt()));
  }

  @Test
  @DisplayName(
      "T-SV-043a reject deletes the request (204, then 404); approving a name that belongs to"
          + " another application is 409 already_exists")
  void rejectAndNameTaken() throws Exception {
    String name = unique("rej");
    int rejected = queue(name, "uat").path("id").asInt();
    ApiResponse r = admin.post("/api/v1/admin/pending-registrations/" + rejected + "/reject", null);
    assertThat(r.status()).as(r.toString()).isEqualTo(204);
    assertThat(
            admin
                .post("/api/v1/admin/pending-registrations/" + rejected + "/reject", null)
                .status())
        .isEqualTo(404);
    assertThat(AuditLog.rows(env, "APP_REJECTED"))
        .anySatisfy(row -> assertThat(row.targetName()).isEqualTo(name));

    String taken = unique("taken");
    int request = queue(taken, "qa").path("id").asInt();
    ApiResponse created = admin.post("/api/v1/apps", Map.of("name", taken, "env", "qa"));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    ApiResponse conflict =
        admin.post("/api/v1/admin/pending-registrations/" + request + "/approve", null);
    assertThat(conflict.status()).as(conflict.toString()).isEqualTo(409);
    assertThat(conflict.errorCode()).hasValue("already_exists");
    assertThat(admin.post("/api/v1/admin/pending-registrations/999999/approve", null).status())
        .isEqualTo(404);
  }

  @Test
  @DisplayName(
      "T-SV-043b approve when the application already has 5 active tokens: 409 invalid_state"
          + " whose details say the approval was applied (approval: applied) and no token was"
          + " issued (token: not_issued, app_id: token_limit); the request is gone, the group is"
          + " set, APP_APPROVED is audited and TOKEN_ISSUED is audited rejected token_limit")
  void approveAtTokenLimit() throws Exception {
    String name = unique("limit");
    JsonNode request = queue(name, "qa");
    int id = request.path("id").asInt();
    int home = request.path("home_app_id").asInt();
    int variant;
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement insert =
            c.prepareStatement(
                "INSERT INTO argos.apps (name, env, auto_register, registered_from_app_id)"
                    + " VALUES (?, 'qa', FALSE, ?) RETURNING id")) {
      insert.setString(1, name);
      insert.setInt(2, home);
      try (ResultSet rs = insert.executeQuery()) {
        assertThat(rs.next()).isTrue();
        variant = rs.getInt(1);
      }
    }
    for (int i = 0; i < 5; i++) {
      server.bean(TokenService.class).issue(Actor.system(), variant, null);
    }

    ApiResponse refused =
        admin.post(
            "/api/v1/admin/pending-registrations/" + id + "/approve", Map.of("group", "capped"));
    assertThat(refused.status()).as(refused.toString()).isEqualTo(409);
    assertThat(refused.errorCode()).hasValue("invalid_state");
    JsonNode error = refused.json().path("error");
    assertThat(error.path("message").asString())
        .contains("approved")
        .contains("no token was issued");
    List<String> details = new ArrayList<>();
    for (JsonNode d : error.path("details")) {
      details.add(d.path("field").asString() + "=" + d.path("reason").asString());
    }
    assertThat(details)
        .containsExactly("approval=applied", "token=not_issued", "app_id=token_limit");
    assertThat(refused.body()).doesNotContain("argos_");

    assertThat(admin.post("/api/v1/admin/pending-registrations/" + id + "/approve", null).status())
        .as("the approval stays applied: the request is gone")
        .isEqualTo(404);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select =
            c.prepareStatement("SELECT app_group, deleted_at FROM argos.apps WHERE id = ?")) {
      select.setInt(1, variant);
      try (ResultSet rs = select.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("capped");
        assertThat(rs.getObject(2)).isNull();
      }
    }
    assertThat(AuditLog.rows(env, "APP_APPROVED"))
        .anySatisfy(r -> assertThat(r.targetId()).isEqualTo(Integer.toString(variant)));
    assertThat(AuditLog.rows(env, "TOKEN_ISSUED"))
        .anySatisfy(
            r -> {
              assertThat(r.targetId()).isEqualTo(Integer.toString(variant));
              assertThat(r.reason()).isEqualTo("token_limit");
            });
  }
}
