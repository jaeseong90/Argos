/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.RejectReason;
import io.argos.server.auth.Role;
import io.argos.server.cli.AdminCli;
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
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SV-040 on the real server, PostgreSQL and gRPC listener: a token issued through the API opens
 * an agent session, a rotation keeps both tokens working, a revocation ends the old token's
 * sessions with {@code UNAUTHENTICATED} within 5 seconds and refuses its reconnect, the expiry job
 * ends sessions of expired tokens and of rotated tokens past their grace, and {@code argos-admin
 * token create} (the Compose path, ADR-0010 C-173 (4)) issues through the same service with its
 * limit and exit codes.
 */
@Tag("integration")
@Tag("req:SEC-02")
@Tag("req:SEC-03")
@Tag("req:SEC-04")
@Tag("req:C-173")
class TokenLifecycleIT {

  private static final Pattern FORMAT = Pattern.compile("^argos_[A-Za-z0-9_-]{43}$");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration REVOKE_BUDGET = Duration.ofSeconds(5);

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient admin;
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
    TestUsers.TestUser user =
        new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
    admin = server.client();
    assertThat(admin.login(user.username(), user.password()).status()).isEqualTo(200);
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

  private static FakeAgent agent(String token, String app, String instance) {
    FakeAgent a =
        FakeAgent.builder(channel).token(token).appName(app).instanceName(instance).build();
    AGENTS.add(a);
    return a;
  }

  private static int app(String name) throws Exception {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, "prod");
    }
  }

  private static JsonNode issue(int appId, String body) {
    ApiResponse r = admin.post("/api/v1/admin/apps/" + appId + "/tokens", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    assertThat(r.header("Cache-Control")).hasValue("no-store");
    return r.json().path("data");
  }

  private static JsonNode tokens(int appId) {
    ApiResponse r = admin.get("/api/v1/admin/apps/" + appId + "/tokens");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json().path("data");
  }

  private static JsonNode byId(JsonNode list, String id) {
    for (JsonNode n : list) {
      if (n.path("token_id").asString().equals(id)) {
        return n;
      }
    }
    throw new AssertionError("token " + id + " not listed");
  }

  private static void sql(String statement, Object... args) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(statement)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      st.executeUpdate();
    }
  }

  private static String status(String tokenId) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement("SELECT status FROM argos.agent_tokens WHERE id = ?")) {
      st.setObject(1, UUID.fromString(tokenId));
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private static void assertRevokedClose(FakeAgent a, Duration budget) {
    HelloResult end = a.awaitSessionEnd(budget);
    assertThat(end.status()).as("the session ended within " + budget).isNotNull();
    assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(a.sessionClose()).isPresent();
    assertThat(a.sessionClose().get().getReason()).isEqualTo(CloseReason.TOKEN_REVOKED_CLOSE);
  }

  @Test
  @DisplayName(
      "T-SV-040 API issue (token once, no-store) -> session -> rotate (both tokens hold sessions)"
          + " -> revoke -> UNAUTHENTICATED <= 5 s on the old token's sessions only -> reconnect"
          + " refused TOKEN_REVOKED; audit TOKEN_ISSUED/ROTATED/REVOKED by prefix")
  void issueRotateRevoke() throws Exception {
    String appName = "life-" + UUID.randomUUID().toString().substring(0, 8);
    int appId = app(appName);
    JsonNode issued = issue(appId, "{}");
    String first = issued.path("token").asString();
    String firstId = issued.path("token_id").asString();
    assertThat(first).matches(FORMAT);
    assertThat(issued.path("prefix").asString()).isEqualTo(first.substring(0, 14));
    assertThat(issued.path("expires_at").isString()).as("default expiry 365 days").isTrue();
    assertThat(UUID.fromString(firstId).version()).isEqualTo(4);

    FakeAgent a1 = agent(first, appName, "host-1");
    assertThat(a1.connect().accepted()).isTrue();
    JsonNode listed = byId(tokens(appId), firstId);
    assertThat(listed.path("state").asString()).isEqualTo("active");
    assertThat(listed.path("instances_using").asInt()).isEqualTo(1);
    assertThat(listed.has("token")).isFalse();
    assertThat(tokens(appId).toString()).doesNotContain(first);

    ApiResponse rotate =
        admin.post("/api/v1/admin/tokens/" + firstId + "/rotate", "{\"grace_days\": 7}");
    assertThat(rotate.status()).as(rotate.toString()).isEqualTo(201);
    assertThat(rotate.header("Cache-Control")).hasValue("no-store");
    String second = rotate.json().path("data").path("token").asString();
    String secondId = rotate.json().path("data").path("token_id").asString();
    assertThat(second).matches(FORMAT).isNotEqualTo(first);
    assertThat(rotate.json().path("data").path("rotated_token_id").asString()).isEqualTo(firstId);

    FakeAgent a2 = agent(second, appName, "host-2");
    assertThat(a2.connect().accepted()).as("the new token").isTrue();
    FakeAgent a3 = agent(first, appName, "host-3");
    assertThat(a3.connect().accepted()).as("the rotated token during its grace").isTrue();
    assertThat(a1.sessionOpen()).isTrue();
    JsonNode afterRotate = tokens(appId);
    assertThat(byId(afterRotate, firstId).path("state").asString()).isEqualTo("rotating");
    assertThat(byId(afterRotate, firstId).path("grace_until").isString()).isTrue();
    assertThat(byId(afterRotate, firstId).path("instances_using").asInt()).isEqualTo(2);
    assertThat(byId(afterRotate, secondId).path("state").asString()).isEqualTo("active");

    long t0 = System.nanoTime();
    ApiResponse revoke =
        admin.post(
            "/api/v1/admin/tokens/" + firstId + "/revoke", "{\"reason\": \"rotation finished\"}");
    assertThat(revoke.status()).as(revoke.toString()).isEqualTo(204);
    assertRevokedClose(a1, REVOKE_BUDGET);
    assertRevokedClose(a3, REVOKE_BUDGET);
    Duration took = Duration.ofNanos(System.nanoTime() - t0);
    assertThat(took).as("revocation to both session ends").isLessThanOrEqualTo(REVOKE_BUDGET);
    assertThat(a2.sessionOpen()).as("the new token's session stays").isTrue();
    assertThat(status(firstId)).isEqualTo("REVOKED");

    HelloResult again = agent(first, appName, "host-1").connect();
    assertThat(again.accepted()).isFalse();
    assertThat(again.rejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED);
    assertThat(again.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(byId(tokens(appId), firstId).path("state").asString()).isEqualTo("revoked");

    ApiResponse twice =
        admin.post("/api/v1/admin/tokens/" + firstId + "/revoke", "{\"reason\": \"again\"}");
    assertThat(twice.status()).isEqualTo(409);

    String prefix = first.substring(0, 14);
    assertThat(AuditLog.rows(env, "TOKEN_ISSUED"))
        .anyMatch(r -> prefix.equals(r.targetName()) && "user".equals(r.actorType()));
    assertThat(AuditLog.rows(env, "TOKEN_ROTATED")).anyMatch(r -> firstId.equals(r.targetId()));
    assertThat(AuditLog.rows(env, "TOKEN_REVOKED"))
        .anyMatch(
            r ->
                firstId.equals(r.targetId())
                    && r.after().contains("\"sessions_closed\": 2")
                    && !r.after().contains("rotation finished"));
  }

  @Test
  @DisplayName(
      "T-SV-040 at most 5 active tokens per app through the API: the 6th is 409 token_limit;"
          + " expires_in_days out of range is 400; an unknown token id is 404, a non-UUIDv4 400")
  void limits() throws Exception {
    int appId = app("limit-" + UUID.randomUUID().toString().substring(0, 8));
    for (int i = 0; i < 5; i++) {
      issue(appId, "{\"expires_in_days\": 30}");
    }
    ApiResponse sixth = admin.post("/api/v1/admin/apps/" + appId + "/tokens", "{}");
    assertThat(sixth.status()).isEqualTo(409);
    assertThat(sixth.json().path("error").path("details").toString()).contains("token_limit");
    ApiResponse range =
        admin.post("/api/v1/admin/apps/" + appId + "/tokens", "{\"expires_in_days\": 3651}");
    assertThat(range.status()).isEqualTo(400);
    assertThat(
            admin
                .post("/api/v1/admin/tokens/" + UUID.randomUUID() + "/revoke", "{\"reason\":\"x\"}")
                .status())
        .isEqualTo(404);
    assertThat(admin.post("/api/v1/admin/tokens/not-a-uuid/revoke", "{\"reason\":\"x\"}").status())
        .isEqualTo(400);
    assertThat(admin.get("/api/v1/admin/apps/999999/tokens").status()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "T-SV-040 expiry job: a token past expires_at and a rotated token past its grace become"
          + " EXPIRED, their live sessions end with UNAUTHENTICATED (TOKEN_REVOKED_CLOSE), the"
          + " reconnect is refused TOKEN_EXPIRED, TOKEN_EXPIRED is audited; the replacement"
          + " stays")
  void expiryJob() throws Exception {
    String appName = "expiry-" + UUID.randomUUID().toString().substring(0, 8);
    int appId = app(appName);
    JsonNode shortLived = issue(appId, "{\"expires_in_days\": 1}");
    JsonNode rotated = issue(appId, "{}");
    FakeAgent onShort = agent(shortLived.path("token").asString(), appName, "short-1");
    FakeAgent onRotated = agent(rotated.path("token").asString(), appName, "rot-1");
    assertThat(onShort.connect().accepted()).isTrue();
    assertThat(onRotated.connect().accepted()).isTrue();
    ApiResponse rotate =
        admin.post(
            "/api/v1/admin/tokens/" + rotated.path("token_id").asString() + "/rotate",
            "{\"grace_days\": 1}");
    assertThat(rotate.status()).isEqualTo(201);
    String replacement = rotate.json().path("data").path("token").asString();
    FakeAgent onReplacement = agent(replacement, appName, "new-1");
    assertThat(onReplacement.connect().accepted()).isTrue();

    // The day passes: expires_at and grace_until move into the past in the database (the clock
    // arithmetic of 1 day and the grace is TokenServiceTest's; here the sweep acts on stored rows).
    sql(
        "UPDATE argos.agent_tokens SET expires_at = now() - interval '1 second' WHERE id = ?",
        UUID.fromString(shortLived.path("token_id").asString()));
    sql(
        "UPDATE argos.agent_tokens SET grace_until = now() - interval '1 second' WHERE id = ?",
        UUID.fromString(rotated.path("token_id").asString()));
    server.bean(TokenExpiryJob.class).run();

    assertRevokedClose(onShort, REVOKE_BUDGET);
    assertRevokedClose(onRotated, REVOKE_BUDGET);
    assertThat(onReplacement.sessionOpen()).isTrue();
    assertThat(status(shortLived.path("token_id").asString())).isEqualTo("EXPIRED");
    assertThat(status(rotated.path("token_id").asString())).isEqualTo("EXPIRED");
    HelloResult refused = agent(shortLived.path("token").asString(), appName, "short-1").connect();
    assertThat(refused.rejectReason()).isEqualTo(RejectReason.TOKEN_EXPIRED);
    JsonNode list = tokens(appId);
    assertThat(byId(list, shortLived.path("token_id").asString()).path("state").asString())
        .isEqualTo("expired");
    assertThat(AuditLog.rows(env, "TOKEN_EXPIRED"))
        .anyMatch(r -> shortLived.path("token_id").asString().equals(r.targetId()))
        .anyMatch(
            r ->
                rotated.path("token_id").asString().equals(r.targetId())
                    && "grace_ended".equals(r.reason())
                    && "system".equals(r.actorType()));
  }

  @Test
  @DisplayName(
      "T-SV-040j C-173 (4) argos-admin token create: creates the app, issues through TokenService (JSON"
          + " once, audit actor argos-compose), the agent connects with it; the 6th token exits 4,"
          + " invalid arguments exit 2")
  void adminCommand() throws Exception {
    String appName = "cli-" + UUID.randomUUID().toString().substring(0, 8);
    Run first =
        admin(
            "token",
            "create",
            "--app",
            appName,
            "--env",
            "prod",
            "--actor",
            "argos-compose",
            "--json");
    assertThat(first.exitCode()).as(first.err()).isEqualTo(AdminCli.EXIT_OK);
    JsonNode out = JSON.readTree(first.out());
    String token = out.path("token").asString();
    assertThat(token).matches(FORMAT);
    assertThat(out.path("app_created").asBoolean()).isTrue();
    assertThat(out.path("prefix").asString()).isEqualTo(token.substring(0, 14));
    assertThat(first.err()).doesNotContain(token);
    assertThat(agent(token, appName, "cli-host").connect().accepted()).isTrue();
    assertThat(AuditLog.rows(env, "TOKEN_ISSUED"))
        .anyMatch(
            r ->
                "argos-compose".equals(r.actorId())
                    && "system".equals(r.actorType())
                    && out.path("prefix").asString().equals(r.targetName()));

    for (int i = 0; i < 4; i++) {
      Run more =
          admin("token", "create", "--app", appName, "--env", "prod", "--expires-days", "30");
      assertThat(more.exitCode()).as(more.err()).isEqualTo(AdminCli.EXIT_OK);
      assertThat(more.out()).contains("app id " + out.path("app_id").asInt());
    }
    Run sixth = admin("token", "create", "--app", appName, "--env", "prod", "--json");
    assertThat(sixth.exitCode()).isEqualTo(AdminCli.EXIT_LIMIT);
    assertThat(JSON.readTree(sixth.out()).path("code").asString()).isEqualTo("token_limit");
    assertThat(
            Pattern.compile("argos_[A-Za-z0-9_-]{40,}").matcher(sixth.out() + sixth.err()).find())
        .isFalse();
    assertThat(admin("token", "create", "--app", "bad name", "--env", "prod").exitCode())
        .isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(
            admin("token", "create", "--app", appName, "--env", "prod", "--expires-days", "0")
                .exitCode())
        .isEqualTo(AdminCli.EXIT_USAGE);
  }

  /** Output of one command run. */
  private record Run(int exitCode, String out, String err) {}

  private static Run admin(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    int code =
        AdminCli.run(
            args,
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    // The command line switched this JVM's Log4j to log4j2-admin.xml (C-226 (11)).
    IntegrationLogs.restoreServerLogging();
    Run run =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    for (PgAccount a : PgAccount.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    return run;
  }
}
