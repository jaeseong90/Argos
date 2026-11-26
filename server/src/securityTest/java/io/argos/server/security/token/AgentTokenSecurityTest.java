/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.RejectReason;
import io.argos.server.admin.token.TokenExpiryJob;
import io.argos.server.auth.Role;
import io.argos.server.cli.AdminCli;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SEC-01 to T-SEC-05 with tokens issued through the token API (WO-26) on a real server, real
 * PostgreSQL and the real gRPC listener over TLS: a token writes only to its home app; tokens live
 * in PostgreSQL, the audit log and the server log as hash and prefix only and the issuance response
 * is not cacheable; an expired token is refused; a rotated token works during its grace and stops
 * after it; a revocation ends its sessions with {@code UNAUTHENTICATED} within 5 seconds and
 * refuses the reconnect; the 61st new session of a token within a minute and the 21st
 * authentication failure of an address are {@code RESOURCE_EXHAUSTED} (the latter with {@code
 * argos-retry-after-ms}, ADR-0010 C-156). T-SEC-05b (WO-43, ADR-0010 C-238 (14)): a revocation by
 * {@code argos-admin token revoke}, a process without the server's cache and sessions, ends the
 * token's open sessions and refuses it within the same 5 seconds through the server's revocation
 * watcher.
 */
@Tag("req:SEC-01")
@Tag("req:SEC-02")
@Tag("req:SEC-03")
@Tag("req:SEC-04")
@Tag("req:SEC-05")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentTokenSecurityTest {

  private static final Pattern ANY_TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{40,}");
  private static final Duration REVOKE_BUDGET = Duration.ofSeconds(5);
  private static final Map<String, Object> LIMITS =
      Map.of(
          "argos.ratelimit.login-per-ip-per-minute", "100000",
          "argos.ratelimit.api-per-user-per-second", "100000",
          "argos.ratelimit.api-burst-per-user", "100000");

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient admin;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

  /** Every token this class saw, for the log and table scans. */
  private static final List<String> TOKENS = new ArrayList<>();

  @BeforeAll
  static void start() {
    env = SecuritySuiteAccess.environment();
    server = SecuritySuiteAccess.start(LIMITS);
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    admin = login(server);
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
  }

  private static ApiTestClient login(ArgosTestServer on) {
    TestUsers.TestUser user =
        new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
    ApiTestClient client = on.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static FakeAgent agent(String token, String app, String instance) {
    FakeAgent a =
        FakeAgent.builder(channel).token(token).appName(app).instanceName(instance).build();
    AGENTS.add(a);
    return a;
  }

  private static int app(String name, String envName) throws Exception {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, envName);
    }
  }

  private static String newApp(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Issues through the API; returns {@code data} ({@code token_id}, {@code token}, ...). */
  private static JsonNode issue(int appId, String body) {
    ApiResponse r = admin.post("/api/v1/admin/apps/" + appId + "/tokens", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    JsonNode data = r.json().path("data");
    TOKENS.add(data.path("token").asString());
    return data;
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

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-01 an API-issued token writes to its home app only: a Hello naming another existing"
          + " app is PERMISSION_DENIED with APP_MISMATCH")
  void homeAppOnly() throws Exception {
    String home = newApp("sec01-home");
    String other = newApp("sec01-other");
    int homeId = app(home, "prod");
    app(other, "prod");
    String token = issue(homeId, "{}").path("token").asString();
    assertThat(agent(token, home, "h1").connect().accepted()).isTrue();
    HelloResult foreign = agent(token, other, "h1").connect();
    assertThat(foreign.accepted()).isFalse();
    assertThat(foreign.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(foreign.rejectReason()).isEqualTo(RejectReason.APP_MISMATCH);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-03 a token past expires_at is refused with UNAUTHENTICATED and TOKEN_EXPIRED; the"
          + " expiry sweep ends a live session of an expired token")
  void expiredRefused() throws Exception {
    String name = newApp("sec03");
    int appId = app(name, "prod");
    JsonNode live = issue(appId, "{\"expires_in_days\": 1}");
    JsonNode unused = issue(appId, "{\"expires_in_days\": 1}");
    FakeAgent connected = agent(live.path("token").asString(), name, "e1");
    assertThat(connected.connect().accepted()).isTrue();
    for (JsonNode t : List.of(live, unused)) {
      sql(
          "UPDATE argos.agent_tokens SET expires_at = now() - interval '1 second' WHERE id = ?",
          UUID.fromString(t.path("token_id").asString()));
    }
    HelloResult refused = agent(unused.path("token").asString(), name, "e2").connect();
    assertThat(refused.accepted()).isFalse();
    assertThat(refused.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(refused.rejectReason()).isEqualTo(RejectReason.TOKEN_EXPIRED);

    server.bean(TokenExpiryJob.class).run();
    HelloResult end = connected.awaitSessionEnd(REVOKE_BUDGET);
    assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    HelloResult again = agent(live.path("token").asString(), name, "e1").connect();
    assertThat(again.rejectReason()).isEqualTo(RejectReason.TOKEN_EXPIRED);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-04 rotation: both tokens authenticate during the grace, the old one is refused"
          + " after it; revocation ends the token's sessions with UNAUTHENTICATED within 5 s"
          + " (SessionClose TOKEN_REVOKED_CLOSE) and the reconnect is refused TOKEN_REVOKED")
  void rotationAndRevocation() throws Exception {
    String name = newApp("sec04");
    int appId = app(name, "prod");
    JsonNode old = issue(appId, "{}");
    String oldId = old.path("token_id").asString();
    ApiResponse rotate = admin.post("/api/v1/admin/tokens/" + oldId + "/rotate", "{}");
    assertThat(rotate.status()).as(rotate.toString()).isEqualTo(201);
    JsonNode fresh = rotate.json().path("data");
    TOKENS.add(fresh.path("token").asString());
    FakeAgent onOld = agent(old.path("token").asString(), name, "r-old");
    FakeAgent onNew = agent(fresh.path("token").asString(), name, "r-new");
    assertThat(onOld.connect().accepted()).as("old token in its grace").isTrue();
    assertThat(onNew.connect().accepted()).as("new token").isTrue();

    // The grace ends: the sweep expires the old token and ends its session; the new one stays.
    sql(
        "UPDATE argos.agent_tokens SET grace_until = now() - interval '1 second' WHERE id = ?",
        UUID.fromString(oldId));
    server.bean(TokenExpiryJob.class).run();
    assertThat(onOld.awaitSessionEnd(REVOKE_BUDGET).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(agent(old.path("token").asString(), name, "r-old").connect().rejectReason())
        .isEqualTo(RejectReason.TOKEN_EXPIRED);
    assertThat(onNew.sessionOpen()).isTrue();

    long t0 = System.nanoTime();
    ApiResponse revoke =
        admin.post(
            "/api/v1/admin/tokens/" + fresh.path("token_id").asString() + "/revoke",
            "{\"reason\": \"compromised\"}");
    assertThat(revoke.status()).isEqualTo(204);
    HelloResult end = onNew.awaitSessionEnd(REVOKE_BUDGET);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThanOrEqualTo(REVOKE_BUDGET);
    assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(onNew.sessionClose().map(c -> c.getReason()))
        .hasValue(CloseReason.TOKEN_REVOKED_CLOSE);
    HelloResult again = agent(fresh.path("token").asString(), name, "r-new").connect();
    assertThat(again.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(again.rejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED);
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-05 the 61st new session of one token within a minute is RESOURCE_EXHAUSTED"
          + " (RATE_LIMITED, 16 §2.5 session_rate)")
  void newSessionRate() throws Exception {
    String name = newApp("sec05");
    String token = issue(app(name, "prod"), "{}").path("token").asString();
    for (int i = 0; i < 60; i++) {
      FakeAgent a = FakeAgent.builder(channel).token(token).appName(name).instanceName("s").build();
      try {
        assertThat(a.connect().accepted()).as("session %d", i + 1).isTrue();
      } finally {
        a.close();
      }
    }
    FakeAgent last = agent(token, name, "s");
    HelloResult limited = last.connect();
    assertThat(limited.accepted()).isFalse();
    assertThat(limited.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(limited.rejectReason()).isEqualTo(RejectReason.RATE_LIMITED);
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SEC-02 no token in agent_tokens or audit_logs (regex argos_[A-Za-z0-9_-]{40,}: 0 rows),"
          + " none in the server log (which names the prefix), issuance and list responses are"
          + " Cache-Control: no-store and the list holds no token")
  void hashOnly() throws Exception {
    int appId = app(newApp("sec02"), "prod");
    ApiResponse r = admin.post("/api/v1/admin/apps/" + appId + "/tokens", "{}");
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.header("Cache-Control")).hasValue("no-store");
    String token = r.json().path("data").path("token").asString();
    TOKENS.add(token);
    ApiResponse list = admin.get("/api/v1/admin/apps/" + appId + "/tokens");
    assertThat(list.header("Cache-Control")).hasValue("no-store");
    assertThat(ANY_TOKEN.matcher(list.body()).find()).isFalse();

    for (String table : List.of("argos.agent_tokens", "argos.audit_logs")) {
      int rows = 0;
      int hits = 0;
      try (Connection c = env.connect(PgAccount.APP);
          Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
          rows++;
          for (int i = 1; i <= md.getColumnCount(); i++) {
            String value = rs.getString(i);
            if (value != null && ANY_TOKEN.matcher(value).find()) {
              hits++;
            }
          }
        }
      }
      assertThat(rows).as(table).isPositive();
      assertThat(hits).as("%s values that look like a token", table).isZero();
    }

    String prefix = token.substring(0, 14);
    var log = SecuritySuiteAccess.logFile();
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                Files.exists(log)
                    && Files.readString(log, StandardCharsets.UTF_8).contains("prefix=" + prefix));
    String text = Files.readString(log, StandardCharsets.UTF_8);
    assertThat(ANY_TOKEN.matcher(text).find()).as("no token in the server log").isFalse();
    for (String t : TOKENS) {
      assertThat(text).doesNotContain(t);
    }
    assertThat(text).contains("Agent token issued: app=" + appId + " prefix=" + prefix);
  }

  @Test
  @Order(6)
  @Tag("req:C-156")
  @DisplayName(
      "T-SEC-05 20 authentication failures from one address in a minute, then the 21st call is"
          + " RESOURCE_EXHAUSTED with argos-retry-after-ms (C-156), also for a valid API-issued"
          + " token")
  void addressBlock() throws Exception {
    String name = newApp("sec05-ip");
    String valid = issue(app(name, "prod"), "{}").path("token").asString();
    // A server of its own: the block holds 127.0.0.1 for 10 minutes.
    ArgosTestServer blocked = SecuritySuiteAccess.start(LIMITS);
    ManagedChannel ch =
        AgentChannels.tls("localhost", blocked.collectorPort(), env.collectorCaCertificate());
    try {
      for (int i = 0; i < 20; i++) {
        try (FakeAgent bad =
            FakeAgent.builder(ch).token(AgentTokens.generate()).appName(name).build()) {
          HelloResult r = bad.connect();
          assertThat(r.rejectReason())
              .as("failure %d", i + 1)
              .isEqualTo(RejectReason.TOKEN_INVALID);
          assertThat(r.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
        }
      }
      for (String token : List.of(AgentTokens.generate(), valid)) {
        try (FakeAgent a = FakeAgent.builder(ch).token(token).appName(name).build()) {
          HelloResult r = a.connect();
          assertThat(r.hello()).as("refused before the session handler").isNull();
          assertThat(r.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          long retry = Long.parseLong(r.trailers().get(CallResult.RETRY_AFTER_MS));
          assertThat(retry)
              .isBetween(Duration.ofMinutes(9).toMillis(), Duration.ofMinutes(10).toMillis());
          assertThat(r.trailerRejectReason()).isEqualTo("auth_blocked");
        }
      }
    } finally {
      ch.shutdownNow();
      blocked.close();
    }
  }

  record CliRun(int code, String out, String err) {}

  /** {@code argos-admin} in this JVM against the suite's PostgreSQL, as the server host runs it. */
  private static CliRun cli(String... args) {
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
    // The command line switched Log4j to log4j2-admin.xml; the server's log goes on (C-226 (11)).
    LoggingSystem system = LoggingSystem.get(AgentTokenSecurityTest.class.getClassLoader());
    system.cleanUp();
    system.beforeInitialize();
    system.initialize(
        new LoggingInitializationContext(new StandardEnvironment()),
        "classpath:log4j2-spring.xml",
        null);
    return new CliRun(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static long auditRows(String action, String targetId) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM argos.audit_logs WHERE action = ? AND target_id = ?")) {
      ps.setString(1, action);
      ps.setString(2, targetId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getLong(1);
      }
    }
  }

  @Test
  @Order(7)
  @Tag("req:C-238")
  @DisplayName(
      "T-SEC-05b argos-admin token revoke (no access to the server's cache or sessions): the"
          + " running server ends both open sessions of the token with UNAUTHENTICATED and"
          + " SessionClose TOKEN_REVOKED_CLOSE within 5 s and refuses the reconnect"
          + " TOKEN_REVOKED; exactly one TOKEN_REVOKED audit row (the command's); argos-admin token"
          + " rotate keeps the old token working during its grace")
  void commandLineRevocation() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    String name = newApp("sec05a");
    int appId = app(name, "prod");
    JsonNode revoked = issue(appId, "{}");
    JsonNode rotated = issue(appId, "{}");
    String revokedId = revoked.path("token_id").asString();
    FakeAgent first = agent(revoked.path("token").asString(), name, "cli-1");
    FakeAgent second = agent(revoked.path("token").asString(), name, "cli-2");
    FakeAgent onRotated = agent(rotated.path("token").asString(), name, "cli-rot");
    assertThat(first.connect().accepted()).isTrue();
    assertThat(second.connect().accepted()).isTrue();
    assertThat(onRotated.connect().accepted()).isTrue();

    CliRun rotate = cli("token", "rotate", "--id", rotated.path("token_id").asString(), "--json");
    assertThat(rotate.code()).as(rotate.err()).isZero();
    String successor = json.readTree(rotate.out()).path("token").asString();
    TOKENS.add(successor);

    CliRun revoke = cli("token", "revoke", "--id", revokedId, "--reason", "leaked", "--json");
    long committed = System.nanoTime();
    assertThat(revoke.code()).as(revoke.err()).isZero();
    assertThat(revoke.out()).doesNotContain(revoked.path("token").asString());
    for (FakeAgent a : List.of(first, second)) {
      HelloResult end = a.awaitSessionEnd(REVOKE_BUDGET);
      assertThat(end.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(a.sessionClose().map(c -> c.getReason()))
          .hasValue(CloseReason.TOKEN_REVOKED_CLOSE);
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - committed);
    assertThat(elapsed).as("sessions ended after the command").isLessThanOrEqualTo(REVOKE_BUDGET);
    HelloResult again = agent(revoked.path("token").asString(), name, "cli-1").connect();
    assertThat(again.accepted()).isFalse();
    assertThat(again.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(again.rejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED);
    assertThat(auditRows("TOKEN_REVOKED", revokedId))
        .as("the command's audit row only; the watcher adds none")
        .isEqualTo(1);

    // The rotated token is in its grace: the watcher (several runs by now) leaves it alone.
    assertThat(onRotated.sessionOpen()).as("rotated token's session").isTrue();
    assertThat(agent(rotated.path("token").asString(), name, "cli-rot-2").connect().accepted())
        .as("old token during its grace")
        .isTrue();
    assertThat(agent(successor, name, "cli-new").connect().accepted()).as("successor").isTrue();
  }
}
