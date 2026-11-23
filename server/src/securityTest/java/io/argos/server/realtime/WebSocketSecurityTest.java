/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.auth.session.SessionStateCache;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.auth.TestUsers.TestUser;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.ws.WsTestClient;
import io.argos.server.testing.ws.WsTestClient.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The security of {@code /ws} against the real server (T-SEC-44, SEC-44, 14 §5.1, 16 §7.7): the
 * handshake refuses a missing or forged session (401) and a foreign or missing {@code Origin} (403)
 * before any upgrade, with the security headers of every response (T-SEC-45 for {@code /ws}); the
 * end of a session closes its connections with {@code reauth} and 4401 within one second, for every
 * way a session ends (logout, an administrator's revocation, a password change, a deactivation, a
 * role change); a role change made in the record without a revocation ends only the subscriptions
 * the new role does not allow; no token or cookie value reaches the server log, also at DEBUG.
 */
@Tag("req:SEC-44")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketSecurityTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final String USERS = "/api/v1/admin/users";

  private ServerTestEnvironment env;
  private ArgosTestServer server;
  private TestUsers users;
  private ApiTestClient admin;
  private int appId;
  private final List<String> secrets = new ArrayList<>();

  @BeforeAll
  void start() throws Exception {
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.ratelimit.login-per-ip-per-minute", "100000",
                "argos.ratelimit.api-per-user-per-second", "100000",
                "argos.ratelimit.api-burst-per-user", "100000",
                "logging.level.io.argos.server.realtime", "DEBUG",
                "logging.level.org.springframework.web.socket", "DEBUG"));
    users = new TestUsers(env);
    admin = login(users.create(Role.ADMIN));
    try (Connection c = env.connect(PgAccount.APP)) {
      appId =
          AgentTokens.ensureApp(c, "ws-security-" + Long.toHexString(System.nanoTime()), "prod");
    }
  }

  @AfterAll
  void stop() {
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private ApiTestClient login(TestUser user) {
    ApiTestClient c = server.client();
    ApiResponse r = c.login(user.username(), user.password());
    assertThat(r.status()).as("login " + r.body()).isEqualTo(200);
    c.cookie(ApiTestClient.ACCESS_COOKIE).ifPresent(secrets::add);
    c.cookie(ApiTestClient.REFRESH_COOKIE).ifPresent(secrets::add);
    return c;
  }

  private static String access(ApiTestClient c) {
    return c.cookie(ApiTestClient.ACCESS_COOKIE).orElseThrow();
  }

  /** An open connection with a counter subscription (acknowledged). */
  private WsTestClient openSubscribed(ApiTestClient client) {
    WsTestClient ws = WsTestClient.open(client);
    ws.subscribe("c", "counter", "{\"app\":" + appId + "}");
    ws.expect("ack", "c", WAIT);
    return ws;
  }

  /**
   * Runs {@code end} and asserts that every connection receives {@code reauth} as its last frame
   * and close 4401 within one second of the start of {@code end}.
   */
  private static void assertEndedWithin1s(Runnable end, WsTestClient... connections) {
    long start = System.nanoTime();
    end.run();
    for (WsTestClient ws : connections) {
      assertThat(ws.awaitClose(Duration.ofSeconds(2))).as("closed").isTrue();
      assertThat(ws.closeCode()).isEqualTo(4401);
      long ms = TimeUnit.NANOSECONDS.toMillis(ws.closedNanos() - start);
      System.out.printf("[wo22-ws-4401] closed after %d ms%n", ms);
      assertThat(ms).as("close within 1 s (ms)").isLessThanOrEqualTo(1_000);
      List<Frame> frames = ws.frames();
      assertThat(frames).isNotEmpty();
      assertThat(frames.get(frames.size() - 1).op())
          .as("reauth before the close")
          .isEqualTo("reauth");
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-44 handshake without a cookie, with a forged or garbage JWT: 401 before the upgrade"
          + " (ErrorBody unauthenticated); no token in the query string is accepted")
  void unauthenticated() {
    WsTestClient.Handshake none =
        WsTestClient.connect(server.baseUrl(), null, server.client().serverOrigin());
    assertThat(none.accepted()).isFalse();
    assertThat(none.status()).isEqualTo(401);

    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"EdDSA\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"sub\":\""
                        + UUID.randomUUID()
                        + "\",\"sid\":\""
                        + UUID.randomUUID()
                        + "\",\"exp\":4102444800}")
                    .getBytes(StandardCharsets.UTF_8));
    String forged =
        header
            + "."
            + claims
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    secrets.add(forged);
    WsTestClient.Handshake forgedResult =
        WsTestClient.connect(server.baseUrl(), forged, server.client().serverOrigin());
    assertThat(forgedResult.accepted()).isFalse();
    assertThat(forgedResult.status()).isEqualTo(401);

    WsTestClient.Handshake garbage =
        WsTestClient.connect(server.baseUrl(), "not-a-jwt", server.client().serverOrigin());
    assertThat(garbage.status()).isEqualTo(401);

    // A token in the query string is not a way in.
    ApiTestClient viewer = login(users.create(Role.VIEWER));
    ApiResponse query =
        server
            .client()
            .header("Origin", server.client().serverOrigin())
            .get("/ws?access_token=" + access(viewer));
    assertThat(query.status()).isEqualTo(401);
    assertThat(query.errorCode()).contains("unauthenticated");
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-44 a valid session with a foreign Origin, without Origin or with a look-alike origin:"
          + " 403 before the upgrade; the same session with the server's origin is upgraded")
  void crossOrigin() {
    ApiTestClient viewer = login(users.create(Role.VIEWER));
    String token = access(viewer);
    for (String origin :
        new String[] {
          ApiTestClient.FOREIGN_ORIGIN,
          null,
          "null",
          viewer.serverOrigin() + ".attacker.example",
          viewer.serverOrigin().replace("127.0.0.1", "localhost")
        }) {
      WsTestClient.Handshake h = WsTestClient.connect(server.baseUrl(), token, origin);
      assertThat(h.accepted()).as("origin " + origin).isFalse();
      assertThat(h.status()).as("origin " + origin).isEqualTo(403);
    }
    try (WsTestClient ok = WsTestClient.open(viewer)) {
      ok.ping(1);
      ok.expect("pong", null, WAIT);
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-45 the refused handshake of /ws carries the security headers and the ErrorBody, no"
          + " Set-Cookie")
  void refusalHeaders() {
    ApiResponse r = server.client().header("Origin", server.client().serverOrigin()).get("/ws");
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.errorCode()).contains("unauthenticated");
    assertThat(r.header("X-Content-Type-Options")).contains("nosniff");
    assertThat(r.header("X-Frame-Options")).contains("DENY");
    assertThat(r.header("Referrer-Policy")).contains("strict-origin-when-cross-origin");
    assertThat(r.header("Cross-Origin-Resource-Policy")).contains("same-origin");
    assertThat(r.header("Set-Cookie")).isEmpty();
    ApiResponse foreign = server.client().header("Origin", ApiTestClient.FOREIGN_ORIGIN).get("/ws");
    assertThat(foreign.status()).isEqualTo(403);
    assertThat(foreign.errorCode()).contains("forbidden");
    assertThat(foreign.header("X-Content-Type-Options")).contains("nosniff");
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-44 logout: every connection of the session receives reauth and close 4401 within 1 s;"
          + " a connection of another session of the same user stays open")
  void logout() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    ApiTestClient otherSession = login(user);
    WsTestClient a = openSubscribed(client);
    WsTestClient b = openSubscribed(client);
    try (WsTestClient other = openSubscribed(otherSession)) {
      assertEndedWithin1s(
          () -> assertThat(client.post("/api/v1/auth/logout", null).status()).isEqualTo(204), a, b);
      other.ping(1);
      other.expect("pong", null, WAIT);
      assertThat(other.isOpen()).isTrue();
    } finally {
      a.close();
      b.close();
    }
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SEC-44 an administrator ends the user's sessions: reauth and close 4401 within 1 s; the"
          + " old cookie cannot open a new connection (401)")
  void adminRevoke() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    try (WsTestClient ws = openSubscribed(client)) {
      assertEndedWithin1s(
          () ->
              assertThat(admin.post(USERS + "/" + user.id() + "/sessions/revoke", null).status())
                  .isEqualTo(204),
          ws);
    }
    WsTestClient.Handshake again = WsTestClient.connect(client);
    assertThat(again.accepted()).isFalse();
    assertThat(again.status()).isEqualTo(401);
  }

  @Test
  @Order(6)
  @DisplayName(
      "T-SEC-44 a password change ends the connections of every session of the user (the caller's"
          + " old session too) with reauth and 4401 within 1 s")
  void passwordChange() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient other = login(user);
    ApiTestClient client = login(user);
    WsTestClient wsOther = openSubscribed(other);
    WsTestClient wsOwn = openSubscribed(client);
    try {
      String next = TestUsers.newPassword(user.username());
      assertEndedWithin1s(
          () ->
              assertThat(
                      client
                          .put(
                              "/api/v1/auth/password",
                              Map.of("current", user.password(), "new", next))
                          .status())
                  .isEqualTo(204),
          wsOther,
          wsOwn);
      client.cookie(ApiTestClient.ACCESS_COOKIE).ifPresent(secrets::add);
    } finally {
      wsOther.close();
      wsOwn.close();
    }
  }

  @Test
  @Order(7)
  @DisplayName("T-SEC-44 disabling the account: reauth and close 4401 within 1 s")
  void disable() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    try (WsTestClient ws = openSubscribed(client)) {
      assertEndedWithin1s(
          () ->
              assertThat(admin.patch(USERS + "/" + user.id(), Map.of("enabled", false)).status())
                  .isEqualTo(200),
          ws);
    }
  }

  @Test
  @Order(8)
  @DisplayName(
      "T-SEC-44 a role change by an administrator revokes the session (16 §7.4): reauth and close"
          + " 4401 within 1 s")
  void roleChangeRevokes() {
    TestUser user = users.create(Role.ADMIN);
    ApiTestClient client = login(user);
    try (WsTestClient ws = openSubscribed(client)) {
      assertEndedWithin1s(
          () ->
              assertThat(
                      admin
                          .patch(USERS + "/" + user.id(), Map.of("roles", List.of("viewer")))
                          .status())
                  .isEqualTo(200),
          ws);
    }
  }

  @Test
  @Order(9)
  @DisplayName(
      "T-SEC-44 a demotion in the record without a revocation: the admin subscription gets error"
          + " forbidden and ends, the counter subscription and the connection stay")
  void demotionWithoutRevocation() throws Exception {
    TestUser user = users.create(Role.ADMIN);
    ApiTestClient client = login(user);
    try (WsTestClient ws = openSubscribed(client)) {
      ws.subscribe("adm", "admin", "{\"change_id\":\"c-1\"}");
      ws.expect("ack", "adm", WAIT);
      try (Connection c = env.connect(PgAccount.APP);
          PreparedStatement update =
              c.prepareStatement("UPDATE argos.users SET role = 'VIEWER' WHERE id = ?")) {
        update.setObject(1, user.id());
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      server.bean(SessionStateCache.class).invalidate(SessionTable.sid(access(client)));
      Frame forbidden = ws.expect("error", "adm", Duration.ofSeconds(5));
      assertThat(forbidden.json().path("code").asString()).isEqualTo("forbidden");
      ws.subscribe("adm2", "admin", "{}");
      assertThat(ws.expect("error", "adm2", WAIT).json().path("code").asString())
          .isEqualTo("forbidden");
      ws.ping(1);
      ws.expect("pong", null, WAIT);
      assertThat(ws.isOpen()).isTrue();
      assertThat(ws.frames())
          .noneMatch(f -> f.op().equals("error") && "c".equals(f.json().path("id").asString()));
    }
  }

  @Test
  @Order(10)
  @DisplayName(
      "T-SEC-44 the server log (DEBUG for the WebSocket code) holds no access or refresh token and"
          + " no cookie value of these tests")
  void noSecretsInLog() throws IOException {
    Path file = Paths.get(System.getProperty("LOG_PATH"), "argos-server.log");
    String log = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(log).as("the WebSocket code logged at DEBUG").contains("WebSocket");
    assertThat(secrets).isNotEmpty();
    for (String secret : secrets) {
      assertThat(log).as("a token of the tests").doesNotContain(secret);
      // The signature part alone: a token cut into pieces is still a token.
      int dot = secret.lastIndexOf('.');
      if (dot > 0 && secret.length() - dot > 20) {
        assertThat(log).doesNotContain(secret.substring(dot + 1));
      }
    }
    Consumer<String> none = s -> assertThat(log).doesNotContain(s);
    none.accept("argos_at=");
    none.accept("argos_rt=");
  }
}
