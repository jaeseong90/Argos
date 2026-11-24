/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CloseReason;
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
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Application and instance administration on a real server, PostgreSQL and ClickHouse (WO-26; 14
 * §4.3, ADR-0010 C-153, C-207 (3), C-220 (1)): create with the duplicate refusal, change (group,
 * registration policy, {@code app_packages} shown by {@code GET /apps/{appId}}, the Apdex T with
 * its event marker), the service Apdex override endpoint, instance archive and delete, and the
 * application delete that revokes its tokens and ends a live agent session with {@code
 * UNAUTHENTICATED}.
 */
@Tag("integration")
@Tag("req:C-153")
class AppsAdminIT {

  private static final Duration CLOSE_BUDGET = Duration.ofSeconds(5);

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient admin;
  private static ApiTestClient operator;
  private static ApiTestClient viewer;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

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
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    admin = login(Role.ADMIN);
    operator = login(Role.OPERATOR);
    viewer = login(Role.VIEWER);
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
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static String unique(String prefix) {
    return prefix + "-" + Long.toHexString(System.nanoTime());
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static int create(String name, String env) {
    ApiResponse r = admin.post("/api/v1/apps", body("name", name, "env", env));
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    return r.json().path("data").path("app_id").asInt();
  }

  private static FakeAgent agent(String token, String app, String env, String instance) {
    FakeAgent a =
        FakeAgent.builder(channel)
            .token(token)
            .appName(app)
            .env(env)
            .instanceName(instance)
            .build();
    AGENTS.add(a);
    return a;
  }

  private static IssuedToken issue(int appId) {
    return server.bean(TokenService.class).issue(Actor.system(), appId, null);
  }

  private static List<String> column(String sql, Object... args) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      List<String> out = new ArrayList<>();
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
      return out;
    }
  }

  private static int insertInstance(int appId, String name, boolean archived) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.instances (app_id, name, last_seen_at, archived_at)"
                    + " VALUES (?, ?, now(), CASE WHEN ? THEN now() END) RETURNING id")) {
      st.setInt(1, appId);
      st.setString(2, name);
      st.setBoolean(3, archived);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private static JsonNode find(JsonNode array, String field, int value) {
    for (JsonNode n : array) {
      if (n.path(field).asInt() == value) {
        return n;
      }
    }
    return null;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-SV-050a C-153 POST /apps: 201 with defaults (Apdex 500, auto), audited; duplicate 409, invalid 400,"
          + " operator 403")
  void createApp() {
    String name = unique("adm-create");
    ApiResponse r =
        admin.post("/api/v1/apps", body("name", name, "env", "prod", "group", "payments"));
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    assertThat(r.header("Cache-Control")).hasValue("no-store");
    JsonNode app = r.json().path("data");
    int id = app.path("app_id").asInt();
    assertThat(app.path("name").asString()).isEqualTo(name);
    assertThat(app.path("env").asString()).isEqualTo("prod");
    assertThat(app.path("group").asString()).isEqualTo("payments");
    assertThat(app.path("apdex_t_ms").asInt()).isEqualTo(500);
    assertThat(app.path("registration_policy").asString()).isEqualTo("auto");
    assertThat(app.path("app_packages").isArray()).isTrue();
    assertThat(app.path("app_packages")).isEmpty();

    ApiResponse duplicate = admin.post("/api/v1/apps", body("name", name, "env", "prod"));
    assertThat(duplicate.status()).isEqualTo(409);
    assertThat(duplicate.errorCode()).hasValue("already_exists");
    assertThat(admin.post("/api/v1/apps", body("name", name, "env", "stage")).status())
        .as("another environment of the same name is another application")
        .isEqualTo(201);

    ApiResponse bad =
        admin.post(
            "/api/v1/apps",
            body("name", name + "-x", "env", "prod", "registration_policy", "manual"));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(admin.post("/api/v1/apps", body("env", "prod")).status()).isEqualTo(400);
    assertThat(
            admin
                .post("/api/v1/apps", body("name", name + "-y", "env", "prod", "apdex_t_ms", 0))
                .status())
        .isEqualTo(400);
    assertThat(operator.post("/api/v1/apps", body("name", name + "-z", "env", "prod")).status())
        .isEqualTo(403);

    assertThat(AuditLog.rows(env, "APP_CREATED"))
        .anySatisfy(
            row -> {
              assertThat(row.targetId()).isEqualTo(Integer.toString(id));
              assertThat(row.targetName()).isEqualTo(name);
              assertThat(row.result()).isEqualTo("success");
            });
  }

  @Test
  @DisplayName(
      "T-SV-050b C-153/C-207 (3) PATCH /apps/{appId}: group, policy and app_packages (shown by GET"
          + " /apps/{appId}), Apdex T with its event marker; empty 400, JDK package 400")
  void patchApp() throws Exception {
    String name = unique("adm-patch");
    int id = create(name, "prod");
    ApiResponse r =
        admin.patch(
            "/api/v1/apps/" + id,
            body(
                "group",
                "core",
                "registration_policy",
                "approval",
                "app_packages",
                List.of("com.example.shop", "org.acme.billing", "com.example.shop"),
                "apdex_t_ms",
                800));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode app = r.json().path("data");
    assertThat(app.path("group").asString()).isEqualTo("core");
    assertThat(app.path("registration_policy").asString()).isEqualTo("approval");
    assertThat(app.path("apdex_t_ms").asInt()).isEqualTo(800);

    JsonNode detail = viewer.get("/api/v1/apps/" + id).json().path("data");
    assertThat(detail.path("app_packages"))
        .extracting(JsonNode::asString)
        .containsExactly("com.example.shop", "org.acme.billing");
    assertThat(column("SELECT auto_register::text FROM argos.apps WHERE id = ?", id))
        .containsExactly("false");

    Instant now = Instant.now();
    ApiResponse events =
        viewer.get(
            "/api/v1/apps/"
                + id
                + "/events?type=apdex&from="
                + enc(now.minus(Duration.ofHours(1)).toString())
                + "&to="
                + enc(now.plus(Duration.ofMinutes(5)).toString()));
    assertThat(events.status()).as(events.toString()).isEqualTo(200);
    assertThat(events.json().path("data")).hasSize(1);
    assertThat(events.json().path("data").get(0).path("detail").asString())
        .contains("\"old_t_ms\": 500")
        .contains("\"new_t_ms\": 800");

    ApiResponse clear =
        admin.patch("/api/v1/apps/" + id, body("group", "", "app_packages", List.of()));
    assertThat(clear.status()).as(clear.toString()).isEqualTo(200);
    assertThat(clear.json().path("data").path("group").isNull()).isTrue();
    assertThat(clear.json().path("data").path("app_packages")).isEmpty();

    assertThat(admin.patch("/api/v1/apps/" + id, body()).status()).isEqualTo(400);
    ApiResponse jdk = admin.patch("/api/v1/apps/" + id, body("app_packages", List.of("java.util")));
    assertThat(jdk.status()).isEqualTo(400);
    assertThat(jdk.body()).contains("forbidden_package");
    assertThat(operator.patch("/api/v1/apps/" + id, body("group", "x")).status()).isEqualTo(403);
    assertThat(admin.patch("/api/v1/apps/999999", body("group", "x")).status()).isEqualTo(404);

    assertThat(AuditLog.rows(env, "APP_UPDATED"))
        .anySatisfy(
            row -> {
              assertThat(row.targetId()).isEqualTo(Integer.toString(id));
              assertThat(row.after()).contains("app_packages").contains("apdex_t_ms");
            });
  }

  @Test
  @DisplayName(
      "T-SV-050c C-220 (1) PUT/DELETE /apps/{appId}/services/{serviceId}/apdex: override stored with an"
          + " event marker, removed again; bad id and range 400, operator 403")
  void serviceApdexOverride() throws Exception {
    int id = create(unique("adm-svc"), "prod");
    String service = "18446744073709551615";
    ApiResponse put =
        admin.put(
            "/api/v1/apps/" + id + "/services/" + service + "/apdex", body("apdex_t_ms", 1200));
    assertThat(put.status()).as(put.toString()).isEqualTo(200);
    JsonNode v = put.json().path("data");
    assertThat(v.path("service_id").asString()).isEqualTo(service);
    assertThat(v.path("apdex_t_ms").asInt()).isEqualTo(1200);
    assertThat(v.path("overridden").asBoolean()).isTrue();
    assertThat(v.path("changed").asBoolean()).isTrue();
    assertThat(v.path("event_id").asString()).isNotBlank();
    assertThat(
            column(
                "SELECT apdex_t_ms::text FROM argos.service_apdex_overrides"
                    + " WHERE app_id = ? AND service_id = ?",
                id,
                -1L))
        .containsExactly("1200");

    ApiResponse same =
        admin.put(
            "/api/v1/apps/" + id + "/services/" + service + "/apdex", body("apdex_t_ms", 1200));
    assertThat(same.json().path("data").path("changed").asBoolean()).isFalse();

    ApiResponse delete = admin.delete("/api/v1/apps/" + id + "/services/" + service + "/apdex");
    assertThat(delete.status()).as(delete.toString()).isEqualTo(200);
    assertThat(delete.json().path("data").path("overridden").asBoolean()).isFalse();
    assertThat(delete.json().path("data").path("apdex_t_ms").asInt()).isEqualTo(500);
    assertThat(
            column("SELECT count(*)::text FROM argos.service_apdex_overrides WHERE app_id = ?", id))
        .containsExactly("0");
    assertThat(
            column(
                "SELECT count(*)::text FROM argos.events WHERE app_id = ?"
                    + " AND type = 'apdex_t_change'",
                id))
        .containsExactly("2");

    assertThat(
            admin
                .put("/api/v1/apps/" + id + "/services/abc/apdex", body("apdex_t_ms", 100))
                .status())
        .isEqualTo(400);
    assertThat(
            admin
                .put("/api/v1/apps/" + id + "/services/1/apdex", body("apdex_t_ms", 70_000))
                .status())
        .isEqualTo(400);
    assertThat(
            operator
                .put("/api/v1/apps/" + id + "/services/1/apdex", body("apdex_t_ms", 100))
                .status())
        .isEqualTo(403);
  }

  @Test
  @DisplayName(
      "T-SV-050d C-153 instances: archive by an operator (409 while connected), delete by an admin only"
          + " when archived (409 otherwise), then 404")
  void instanceArchiveAndDelete() throws Exception {
    String name = unique("adm-inst");
    int id = create(name, "prod");
    int silent = insertInstance(id, "silent:8080", false);

    FakeAgent live = agent(issue(id).token(), name, "prod", "live:8080");
    HelloResult hello = live.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    ApiResponse connected =
        operator.post("/api/v1/instances/" + live.instanceId() + "/archive", null);
    assertThat(connected.status()).isEqualTo(409);
    assertThat(connected.body()).contains("connected");

    assertThat(viewer.post("/api/v1/instances/" + silent + "/archive", null).status())
        .isEqualTo(403);
    assertThat(admin.delete("/api/v1/instances/" + silent).status())
        .as("not archived yet")
        .isEqualTo(409);
    assertThat(operator.post("/api/v1/instances/" + silent + "/archive", null).status())
        .isEqualTo(204);
    assertThat(operator.post("/api/v1/instances/" + silent + "/archive", null).status())
        .as("archiving again changes nothing")
        .isEqualTo(204);
    JsonNode listed = viewer.get("/api/v1/apps/" + id + "/instances").json().path("data");
    assertThat(find(listed, "instance_id", silent)).isNull();
    JsonNode archived =
        viewer.get("/api/v1/apps/" + id + "/instances?archived=true").json().path("data");
    assertThat(find(archived, "instance_id", silent)).isNotNull();

    assertThat(operator.delete("/api/v1/instances/" + silent).status()).isEqualTo(403);
    assertThat(admin.delete("/api/v1/instances/" + silent).status()).isEqualTo(204);
    assertThat(viewer.get("/api/v1/instances/" + silent).status()).isEqualTo(404);
    assertThat(column("SELECT count(*)::text FROM argos.instances WHERE id = ?", silent))
        .containsExactly("0");
    assertThat(AuditLog.rows(env, "INSTANCE_ARCHIVED"))
        .anySatisfy(row -> assertThat(row.targetId()).isEqualTo(Integer.toString(silent)));
    assertThat(AuditLog.rows(env, "INSTANCE_DELETED"))
        .anySatisfy(row -> assertThat(row.targetId()).isEqualTo(Integer.toString(silent)));
  }

  @Test
  @DisplayName(
      "T-SV-050e C-153 DELETE /apps/{appId}?confirm=<name>: 202, tokens revoked, live session ends"
          + " UNAUTHENTICATED within 5 s, reconnect refused, rules deleted, not listed, name"
          + " reusable")
  void deleteApp() throws Exception {
    String name = unique("adm-delete");
    int id = create(name, "prod");
    IssuedToken token = issue(id);
    FakeAgent agent = agent(token.token(), name, "prod", "del:8080");
    assertThat(agent.connect().accepted()).isTrue();
    insertInstance(id, "old:8080", false);
    ApiResponse rule =
        operator.post(
            "/api/v1/alerts/rules",
            body(
                "name",
                "p95 of " + name,
                "target",
                Map.of("app_id", id, "env", "prod", "type", "app", "ids", List.of()),
                "metric",
                "response_time_p95",
                "window",
                "30s",
                "for",
                "0s",
                "condition",
                Map.of("operator", ">=", "warn", 3000),
                "min_samples",
                1,
                "warmup_grace",
                "0s"));
    assertThat(rule.status()).as(rule.toString()).isEqualTo(201);
    String ruleId = rule.json().path("data").path("rule_id").asString();

    ApiResponse mismatch = admin.delete("/api/v1/apps/" + id + "?confirm=" + enc(name + "x"));
    assertThat(mismatch.status()).isEqualTo(400);
    assertThat(mismatch.body()).contains("mismatch");
    assertThat(admin.delete("/api/v1/apps/" + id).status()).isEqualTo(400);
    assertThat(operator.delete("/api/v1/apps/" + id + "?confirm=" + enc(name)).status())
        .isEqualTo(403);

    long start = System.nanoTime();
    ApiResponse deleted = admin.delete("/api/v1/apps/" + id + "?confirm=" + enc(name));
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(202);
    assertThat(deleted.json().path("data").path("tokens_revoked").asInt()).isEqualTo(1);
    assertThat(deleted.json().path("data").path("rules_deleted").asInt()).isEqualTo(1);
    HelloResult end = agent.awaitSessionEnd(CLOSE_BUDGET);
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(end.status()).as("the session ended within " + CLOSE_BUDGET).isNotNull();
    assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(agent.sessionClose().map(c -> c.getReason()))
        .contains(CloseReason.TOKEN_REVOKED_CLOSE);
    assertThat(took).isLessThanOrEqualTo(CLOSE_BUDGET);

    assertThat(column("SELECT status FROM argos.agent_tokens WHERE id = ?", token.tokenId()))
        .containsExactly("REVOKED");
    HelloResult again = agent(token.token(), name, "prod", "del-2:8080").connect();
    assertThat(again.accepted()).isFalse();
    assertThat(again.code()).isEqualTo(Status.Code.UNAUTHENTICATED);

    assertThat(viewer.get("/api/v1/apps/" + id).status()).isEqualTo(404);
    JsonNode list = viewer.get("/api/v1/apps?q=" + enc(name)).json().path("data");
    assertThat(find(list, "app_id", id)).isNull();
    assertThat(viewer.get("/api/v1/alerts/rules/" + ruleId).status())
        .as("its alert rules are deleted")
        .isEqualTo(404);
    assertThat(
            column(
                "SELECT count(*)::text FROM argos.instances WHERE app_id = ? AND archived_at IS"
                    + " NULL",
                id))
        .as("its instances are archived")
        .containsExactly("0");
    assertThat(admin.delete("/api/v1/apps/" + id + "?confirm=" + enc(name)).status())
        .isEqualTo(404);

    int again2 = create(name, "prod");
    assertThat(again2).as("the name can be registered again").isNotEqualTo(id);
    assertThat(AuditLog.rows(env, "APP_DELETED"))
        .anySatisfy(
            row -> {
              assertThat(row.targetId()).isEqualTo(Integer.toString(id));
              assertThat(row.targetName()).isEqualTo(name);
            });
  }

  @Test
  @DisplayName(
      "T-API-013b GET /apps and GET /apps/{appId} tokens_active (WO-39): active and rotating"
          + " tokens count, revoked and expired ones do not; an application without tokens has 0;"
          + " the response carries the count only, no token or prefix")
  void tokensActive() throws Exception {
    String name = unique("tok-count");
    int appId = create(name, "prod");
    int empty = create(unique("tok-none"), "prod");
    IssuedToken revoked = issue(appId);
    IssuedToken rotated = issue(appId);
    IssuedToken expired = issue(appId);
    TokenService tokens = server.bean(TokenService.class);
    tokens.revoke(Actor.system(), revoked.tokenId(), "T-API-013b");
    IssuedToken successor = tokens.rotate(Actor.system(), rotated.tokenId(), 7);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "UPDATE argos.agent_tokens SET expires_at = now() - interval '1 second'"
                    + " WHERE id = ?")) {
      st.setObject(1, expired.tokenId());
      assertThat(st.executeUpdate()).isEqualTo(1);
    }
    assertThat(column("SELECT status FROM argos.agent_tokens WHERE id = ?", rotated.tokenId()))
        .containsExactly("GRACE");

    for (ApiTestClient client : List.of(viewer, admin)) {
      ApiResponse list = client.get("/api/v1/apps?q=" + enc(name));
      assertThat(list.status()).as(list.toString()).isEqualTo(200);
      JsonNode row = find(list.json().path("data"), "app_id", appId);
      assertThat(row).as("app in GET /apps").isNotNull();
      // rotating (old, within its grace) + its successor (active)
      assertThat(row.path("tokens_active").asInt()).as("GET /apps tokens_active").isEqualTo(2);
      ApiResponse one = client.get("/api/v1/apps/" + appId);
      assertThat(one.status()).as(one.toString()).isEqualTo(200);
      assertThat(one.json().path("data").path("tokens_active").asInt()).isEqualTo(2);
      assertThat(one.body())
          .doesNotContain(successor.prefix())
          .doesNotContain(rotated.prefix())
          .doesNotContain(successor.token());
    }
    ApiResponse none = viewer.get("/api/v1/apps/" + empty);
    assertThat(none.json().path("data").path("tokens_active").isInt()).isTrue();
    assertThat(none.json().path("data").path("tokens_active").asInt()).isZero();

    tokens.revoke(Actor.system(), successor.tokenId(), "T-API-013b");
    assertThat(
            viewer.get("/api/v1/apps/" + appId).json().path("data").path("tokens_active").asInt())
        .as("after revoking the successor: the rotating token only")
        .isEqualTo(1);
  }
}
