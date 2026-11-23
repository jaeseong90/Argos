/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.argos.server.testing.fakeagent.AgentTokens;
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
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Deploy markers and custom events written through the API on a real server and PostgreSQL (WO-26;
 * 14 §2.2, §4.12, ADR-0010 C-153, 02 C-12, C-155): an operator's session (CSRF required), an
 * application's agent token as {@code Authorization: Bearer} (no CSRF, home application only,
 * unknown or revoked tokens 401, the token prefix as audit actor and never the token), 60 events
 * per application a minute, and the delete rules.
 */
@Tag("integration")
@Tag("req:F-S18")
@Tag("req:C-153")
class EventsWriteIT {

  private static final Pattern TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{40,}");

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient admin;
  private static ApiTestClient operator;
  private static ApiTestClient viewer;
  private static String operatorName;

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
    admin = login(new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false)));
    TestUsers.TestUser op =
        new TestUsers(env).create(Role.OPERATOR, o -> o.mustChangePassword(false));
    operatorName = op.username();
    operator = login(op);
    viewer = login(new TestUsers(env).create(Role.VIEWER, o -> o.mustChangePassword(false)));
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

  private static ApiTestClient login(TestUsers.TestUser user) {
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  /** A deploy pipeline: no session, no CSRF header, no Origin, the token as Bearer. */
  private static ApiTestClient pipeline(String token) {
    return server
        .client()
        .csrf(false)
        .origin(null)
        .secFetchSite(null)
        .header("Authorization", "Bearer " + token);
  }

  private static int app(String name, String env) throws Exception {
    try (Connection c = EventsWriteIT.env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, env);
    }
  }

  private static IssuedToken issue(int appId) {
    return server.bean(TokenService.class).issue(Actor.system(), appId, null);
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

  private static List<String> row(String sql, Object... args) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = st.executeQuery()) {
        List<String> out = new ArrayList<>();
        if (rs.next()) {
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            out.add(rs.getString(i));
          }
        }
        return out;
      }
    }
  }

  private static JsonNode events(int appId) {
    Instant now = Instant.now();
    ApiResponse r =
        viewer.get(
            "/api/v1/apps/"
                + appId
                + "/events?from="
                + enc(now.minus(Duration.ofDays(2)).toString())
                + "&to="
                + enc(now.plus(Duration.ofHours(2)).toString()));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json().path("data");
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-SV-051a C-153 an operator session writes deploy markers by app_id or by app and env (CSRF"
          + " required); listed with its actor; viewer 403")
  void operatorSession() throws Exception {
    String name = unique("ev-session");
    int id = app(name, "prod");
    ApiResponse created =
        operator.post(
            "/api/v1/events",
            body(
                "app_id",
                id,
                "type",
                "deploy",
                "title",
                "Release 2.0",
                "version",
                "2.0.0",
                "detail",
                "blue/green switch"));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String eventId = created.json().path("data").path("event_id").asString();
    ApiResponse byName =
        operator.post(
            "/api/v1/events",
            body("app", name, "env", "prod", "type", "custom", "title", "Feature flag on"));
    assertThat(byName.status()).as(byName.toString()).isEqualTo(201);

    JsonNode listed = events(id);
    assertThat(listed).hasSize(2);
    JsonNode deploy = null;
    for (JsonNode e : listed) {
      if (e.path("event_id").asString().equals(eventId)) {
        deploy = e;
      }
    }
    assertThat(deploy).isNotNull();
    assertThat(deploy.path("type").asString()).isEqualTo("deploy");
    assertThat(deploy.path("version").asString()).isEqualTo("2.0.0");
    assertThat(deploy.path("actor").asString()).isEqualTo(operatorName);
    assertThat(deploy.path("detail").asString()).contains("blue/green switch");

    assertThat(
            viewer
                .post("/api/v1/events", body("app_id", id, "type", "deploy", "title", "x"))
                .status())
        .isEqualTo(403);
    ApiResponse noCsrf =
        operator.newBrowser().csrf(false).post("/api/v1/events", body("app_id", id));
    assertThat(noCsrf.status()).isEqualTo(403);
    ApiTestClient noToken = operator.newBrowser();
    assertThat(
            noToken
                .post("/api/v1/events", body("app_id", id, "type", "deploy", "title", "x"))
                .status())
        .as("another browser without the session cookies")
        .isIn(401, 403);
    ApiResponse missingApp =
        operator.post("/api/v1/events", body("type", "deploy", "title", "no app"));
    assertThat(missingApp.status()).isEqualTo(400);
    ApiResponse unknown =
        operator.post(
            "/api/v1/events",
            body("app", "nope-" + name, "env", "prod", "type", "deploy", "title", "t"));
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(
            operator
                .post("/api/v1/events", body("app_id", id, "type", "state", "title", "t"))
                .status())
        .isEqualTo(400);
  }

  @Test
  @DisplayName(
      "T-SV-051b C-153/02 C-12 an agent token as Bearer writes its home application without CSRF; audit"
          + " actor is the token prefix, never the token")
  void agentTokenBearer() throws Exception {
    String name = unique("ev-token");
    int id = app(name, "prod");
    IssuedToken token = issue(id);
    ApiResponse created =
        pipeline(token.token())
            .post(
                "/api/v1/events", body("type", "deploy", "title", "Pipeline 42", "version", "42"));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    assertThat(created.header("Cache-Control")).hasValue("no-store");
    String eventId = created.json().path("data").path("event_id").asString();
    assertThat(
            row(
                "SELECT app_id::text, type, created_by::text FROM argos.events WHERE id = ?",
                UUID.fromString(eventId)))
        .containsExactly(Integer.toString(id), "deploy", null);
    ApiResponse explicit =
        pipeline(token.token())
            .post(
                "/api/v1/events",
                body("app_id", id, "app", name, "env", "prod", "type", "custom", "title", "ok"));
    assertThat(explicit.status()).as(explicit.toString()).isEqualTo(201);

    assertThat(AuditLog.rows(env, "EVENT_CREATED"))
        .anySatisfy(
            r -> {
              assertThat(r.actorType()).isEqualTo("agent");
              assertThat(r.actorId()).isEqualTo(token.prefix());
              assertThat(r.targetId()).isEqualTo(Integer.toString(id));
            });
    for (AuditLog.Row r : AuditLog.all(env)) {
      String text =
          String.join(
              " ",
              String.valueOf(r.actorId()),
              String.valueOf(r.actorName()),
              String.valueOf(r.before()),
              String.valueOf(r.after()),
              String.valueOf(r.targetName()));
      assertThat(TOKEN.matcher(text).find()).as("no token text in audit row " + r.id()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "T-SV-051c C-155 a token writes only its home application (403); malformed, unknown and revoked"
          + " tokens are 401")
  void agentTokenRefusals() throws Exception {
    String name = unique("ev-scope");
    int id = app(name, "prod");
    int other = app(name + "-other", "prod");
    IssuedToken token = issue(id);
    ApiResponse otherId =
        pipeline(token.token())
            .post("/api/v1/events", body("app_id", other, "type", "deploy", "title", "t"));
    assertThat(otherId.status()).as(otherId.toString()).isEqualTo(403);
    ApiResponse otherName =
        pipeline(token.token())
            .post(
                "/api/v1/events",
                body("app", name + "-other", "env", "prod", "type", "deploy", "title", "t"));
    assertThat(otherName.status()).isEqualTo(403);
    ApiResponse otherEnv =
        pipeline(token.token())
            .post(
                "/api/v1/events",
                body("app", name, "env", "stage", "type", "deploy", "title", "t"));
    assertThat(otherEnv.status()).isEqualTo(403);

    assertThat(
            pipeline("not-a-token")
                .post("/api/v1/events", body("type", "deploy", "title", "t"))
                .status())
        .isEqualTo(401);
    assertThat(
            pipeline(AgentTokens.generate())
                .post("/api/v1/events", body("type", "deploy", "title", "t"))
                .status())
        .as("well-formed but unknown")
        .isEqualTo(401);
    ApiResponse basic =
        server
            .client()
            .header("Authorization", "Basic eDp5")
            .post("/api/v1/events", body("type", "deploy", "title", "t"));
    assertThat(basic.status())
        .as("another scheme gets the CSRF check, then no token")
        .isIn(401, 403);

    server.bean(TokenService.class).revoke(Actor.system(), token.tokenId(), "test");
    ApiResponse revoked =
        pipeline(token.token()).post("/api/v1/events", body("type", "deploy", "title", "t"));
    assertThat(revoked.status()).as(revoked.toString()).isEqualTo(401);
    assertThat(revoked.errorCode()).hasValue("unauthenticated");
    assertThat(row("SELECT count(*)::text FROM argos.events WHERE app_id IN (?, ?)", id, other))
        .containsExactly("0");
  }

  @Test
  @DisplayName(
      "T-SV-051d C-153 60 events per application a minute: the 61st is 429 with Retry-After")
  void perAppLimit() throws Exception {
    String name = unique("ev-limit");
    int id = app(name, "prod");
    IssuedToken token = issue(id);
    ApiTestClient pipeline = pipeline(token.token());
    for (int i = 0; i < 60; i++) {
      ApiResponse r =
          pipeline.post("/api/v1/events", body("type", "custom", "title", "event " + i));
      assertThat(r.status()).as("event " + i + ": " + r).isEqualTo(201);
    }
    ApiResponse limited =
        operator.post("/api/v1/events", body("app_id", id, "type", "custom", "title", "61st"));
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.errorCode()).hasValue("rate_limited");
    assertThat(limited.header("Retry-After")).isPresent();
    int other = app(name + "-b", "prod");
    assertThat(
            operator
                .post("/api/v1/events", body("app_id", other, "type", "custom", "title", "other"))
                .status())
        .as("another application has its own budget")
        .isEqualTo(201);
  }

  @Test
  @DisplayName(
      "T-SV-051e C-153 DELETE /events/{eventId}: admin only, deploy and custom only (409 for automatic"
          + " events), 404 for unknown, 400 for a malformed id")
  void deleteRules() throws Exception {
    String name = unique("ev-delete");
    int id = app(name, "prod");
    String eventId =
        operator
            .post("/api/v1/events", body("app_id", id, "type", "deploy", "title", "to delete"))
            .json()
            .path("data")
            .path("event_id")
            .asString();
    assertThat(operator.delete("/api/v1/events/" + eventId).status()).isEqualTo(403);
    assertThat(admin.delete("/api/v1/events/" + eventId).status()).isEqualTo(204);
    assertThat(admin.delete("/api/v1/events/" + eventId).status()).isEqualTo(404);

    UUID automatic = UUID.randomUUID();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.events (id, app_id, env, type, title, ts)"
                    + " VALUES (?, ?, 'prod', 'state', 'Instance DOWN', now())")) {
      st.setObject(1, automatic);
      st.setInt(2, id);
      st.executeUpdate();
    }
    ApiResponse refused = admin.delete("/api/v1/events/" + automatic);
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.errorCode()).hasValue("invalid_state");
    assertThat(admin.delete("/api/v1/events/not-a-uuid").status()).isEqualTo(400);
    assertThat(AuditLog.rows(env, "EVENT_DELETED"))
        .anySatisfy(r -> assertThat(r.targetId()).isEqualTo(eventId));
  }
}
