/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.TransactionRecord;
import io.argos.proto.v1.UserIdMode;
import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.privacy.salt.AppSaltService;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The application user id salt end to end (16 §6.3, ADR-0010 C-125 (2), C-152, C-163; WO-28): the
 * server creates one 32-byte salt per application on first use and sends it in {@code
 * ServerHello.user_id_salt}; a row an agent hashed with it is found by the plain id through the
 * transaction search and the global search; another application has another salt.
 */
@Tag("integration")
@Tag("req:SEC-32")
@Tag("req:C-152")
@Tag("req:C-125")
class UserIdSaltIT {

  private static final String USER = "alice@example.com";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient viewer;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
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

  private static FakeAgent agent(AgentTokens.IssuedToken token, String app, int epoch) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(app)
        .instanceName(app + "-1:8080")
        .records(new Records(AgentClock.system(), epoch, epoch))
        .agentEpoch(epoch)
        .hello(h -> h.setUserIdMode(UserIdMode.USER_ID_HASHED))
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  private static AgentTokens.IssuedToken token(String app) throws Exception {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
  }

  /** What the agent records in hashed mode (16 §6.3), computed independently of the server. */
  private static String agentHash(byte[] salt, String id) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)))
        .substring(0, 16);
  }

  /** A browser signed in as a new viewer (a real session: the search checks app access). */
  private static synchronized ApiTestClient viewer() {
    if (viewer == null) {
      TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
      ApiTestClient client = server.client();
      ApiResponse login = client.login(user.username(), user.password());
      assertThat(login.status()).as(login.toString()).isEqualTo(200);
      viewer = client;
    }
    return viewer;
  }

  private static JsonNode searchUntil(int appId, String userId, Instant from, Instant to, int rows)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", from.toString());
    body.put("to", to.toString());
    body.put("filters", Map.of("user_id", userId));
    JsonNode last = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      ApiResponse r = viewer().post("/api/v1/apps/" + appId + "/transactions/search", body);
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      last = r.json();
      if (last.path("meta").path("total").asLong() >= rows) {
        return last;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    return last;
  }

  @Test
  @DisplayName(
      "T-SEC-32b the Hello carries the app's 32-byte salt (the same on every session, another for"
          + " another app); a hashed row is found by the plain id in the transaction and global"
          + " search, not under another app's salt")
  void saltSentAndHashedSearch() throws Exception {
    String appA = "salt-a-" + Long.toHexString(System.nanoTime());
    String appB = "salt-b-" + Long.toHexString(System.nanoTime());
    AgentTokens.IssuedToken tokenA = token(appA);
    AgentTokens.IssuedToken tokenB = token(appB);
    byte[] saltA;
    byte[] saltB;
    try (FakeAgent b = agent(tokenB, appB, 32)) {
      HelloResult hello = b.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      saltB = hello.hello().getUserIdSalt().toByteArray();
    }
    String hashed;
    Instant from = Instant.now().minus(Duration.ofMinutes(5));
    try (FakeAgent a = agent(tokenA, appA, 31)) {
      HelloResult hello = a.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      saltA = hello.hello().getUserIdSalt().toByteArray();
      assertThat(saltA).hasSize(32);
      assertThat(saltB).hasSize(32).isNotEqualTo(saltA);
      assertThat(server.bean(AppSaltService.class).salt(tokenA.appId()).orElseThrow())
          .isEqualTo(saltA);

      a.sendNewDictionaryEntries();
      Records records = a.records();
      hashed = agentHash(saltA, USER);
      long start = a.clock().nowMs() - 200;
      TransactionRecord tx =
          records.http("GET /api/salt/{id}", start, 150_000).setUserId(hashed).build();
      TransactionRecord other =
          records
              .http("GET /api/salt/{id}", start + 1, 120_000)
              .setUserId(agentHash(saltA, "bob@example.com"))
              .build();
      CallResult sent = a.sendTransactions(a.batches().transactions(List.of(tx, other)));
      assertThat(sent.ackStatus()).as(sent.toString()).isEqualTo(AckStatus.ACK_OK);
      a.sendNewDictionaryEntries();
    }
    try (FakeAgent again = agent(tokenA, appA, 33)) {
      HelloResult hello = again.connect();
      assertThat(hello.accepted()).isTrue();
      assertThat(hello.hello().getUserIdSalt().toByteArray()).as("stable salt").isEqualTo(saltA);
    }

    Instant to = Instant.now().plus(Duration.ofMinutes(1));
    // The plain id, with the noise the normalisation removes (C-163 (2)).
    JsonNode found = searchUntil(tokenA.appId(), "  alice@example.com\u0007 ", from, to, 1);
    assertThat(found.path("meta").path("total").asLong()).isEqualTo(1);
    assertThat(found.path("data").path(0).path("user_id").asString()).isEqualTo(hashed);
    assertThat(found.path("data").path(0).path("user_id_mode").asString()).isEqualTo("hashed");

    ApiResponse global =
        viewer()
            .get(
                "/api/v1/search?type=user&q="
                    + URLEncoder.encode(USER, StandardCharsets.UTF_8)
                    + "&app="
                    + tokenA.appId()
                    + "&from="
                    + from
                    + "&to="
                    + to);
    assertThat(global.status()).as(global.toString()).isEqualTo(200);
    assertThat(global.json().path("data").path("matches")).hasSize(1);

    ApiResponse foreign =
        viewer()
            .post(
                "/api/v1/apps/" + tokenB.appId() + "/transactions/search",
                Map.of(
                    "from",
                    from.toString(),
                    "to",
                    to.toString(),
                    "filters",
                    Map.of("user_id", USER)));
    assertThat(foreign.status()).as(foreign.toString()).isEqualTo(200);
    assertThat(foreign.json().path("meta").path("total").asLong()).isZero();

    String log = Files.readString(IntegrationLogs.logFile(), StandardCharsets.UTF_8);
    for (byte[] salt : List.of(saltA, saltB)) {
      assertThat(log)
          .doesNotContain(HexFormat.of().formatHex(salt))
          .doesNotContain(Base64.getEncoder().encodeToString(salt))
          .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(salt));
    }
  }
}
