/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Demo mode and the application bootstrap on the real server, PostgreSQL, ClickHouse and gRPC
 * listener (WO-41; 17 §3.2, §3.8, §3.9):
 *
 * <ul>
 *   <li>demo mode with the demo profile's bootstrap file: {@code order-api} and {@code payment-api}
 *       are created by the system actor, their generated tokens are stored as SHA-256 and prefix
 *       only (audit {@code APP_CREATED}, {@code TOKEN_ISSUED}), the alert engine creates their
 *       recommended rules, a plaintext Hello with each token opens a session, {@code setup/status}
 *       says {@code demo_mode=true}, the start log has the demo warning and no token; a restart
 *       registers nothing again;
 *   <li>without demo mode a plaintext listener still refuses every Hello with {@code
 *       PLAINTEXT_NOT_ALLOWED} and {@code demo_mode=false}.
 * </ul>
 */
@Tag("integration")
@Tag("req:N-16")
@Tag("req:O-A01")
@Tag("req:SEC-02")
@Tag("req:SEC-74")
class DemoBootstrapIT {

  private static final String ENV = "demo";
  private static final Duration RULES_BUDGET = Duration.ofSeconds(150);

  private static ServerTestEnvironment env;
  private static Path conf;
  private static String orderToken;
  private static String paymentToken;
  private static final List<AutoCloseable> CLOSE = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    conf = Files.createDirectories(env.secretsDirectory().resolve("demo-conf"));
    orderToken = AgentTokens.generate();
    paymentToken = AgentTokens.generate();
    Files.writeString(
        conf.resolve("demo_order_api_token"), orderToken + "\n", StandardCharsets.UTF_8);
    Files.writeString(
        conf.resolve("demo_payment_api_token"), paymentToken + "\n", StandardCharsets.UTF_8);
    String dir = conf.toAbsolutePath().toString().replace('\\', '/');
    Files.writeString(
        conf.resolve("bootstrap-apps.yaml"),
        "apps:\n"
            + "  - name: order-api\n"
            + "    env: demo\n"
            + "    token-file: "
            + dir
            + "/demo_order_api_token\n"
            + "  - name: payment-api\n"
            + "    env: demo\n"
            + "    token-file: "
            + dir
            + "/demo_payment_api_token\n",
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void stop() throws Exception {
    for (int i = CLOSE.size() - 1; i >= 0; i--) {
      CLOSE.get(i).close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static <T extends AutoCloseable> T closeLater(T resource) {
    CLOSE.add(resource);
    return resource;
  }

  private static Map<String, Object> demoProperties() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.demo.enabled", "true");
    p.put(
        "argos.bootstrap.apps-file",
        conf.resolve("bootstrap-apps.yaml").toAbsolutePath().toString());
    return p;
  }

  private static boolean demoMode(ArgosTestServer server) {
    ApiResponse status = server.client().get("/api/v1/setup/status");
    assertThat(status.status()).as(status.toString()).isEqualTo(200);
    assertThat(status.json().path("data").has("demo_mode")).isTrue();
    return status.json().path("data").path("demo_mode").asBoolean();
  }

  private static HelloResult plaintextHello(
      ArgosTestServer server, String token, String app, String instance) {
    ManagedChannel channel = AgentChannels.plaintext("127.0.0.1", server.collectorPort());
    try (FakeAgent agent =
        FakeAgent.builder(channel)
            .ownsChannel(true)
            .token(token)
            .appName(app)
            .env(ENV)
            .instanceName(instance)
            .plaintextFlag(true)
            .build()) {
      return agent.connect();
    }
  }

  private static int appId(String name) throws Exception {
    List<Integer> ids =
        ints(
            "SELECT id FROM argos.apps WHERE name = ? AND env = ? AND deleted_at IS NULL",
            name,
            ENV);
    assertThat(ids).as("app " + name + "/" + ENV).hasSize(1);
    return ids.get(0);
  }

  private static List<Integer> ints(String sql, Object... args) throws Exception {
    List<Integer> out = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getInt(1));
        }
      }
    }
    return out;
  }

  private static List<String> strings(String sql, Object... args) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static int activeRules(int appId) throws Exception {
    return ints(
            "SELECT count(*) FROM argos.alert_rules WHERE app_id = ? AND deleted_at IS NULL", appId)
        .get(0);
  }

  @Test
  @DisplayName(
      "T-NFR-16a demo mode: bootstrap apps and tokens (hash + prefix only, audited), recommended"
          + " rules, plaintext Hello accepted with each token, setup/status demo_mode=true, demo"
          + " WARN and no token in the log; a restart registers nothing again")
  void demoMode() throws Exception {
    long logOffset = IntegrationLogs.offset();
    ArgosTestServer server = closeLater(ArgosTestServer.start(env, demoProperties()));

    assertThat(demoMode(server)).isTrue();

    int order = appId("order-api");
    int payment = appId("payment-api");
    for (Map.Entry<Integer, String> e :
        Map.of(order, orderToken, payment, paymentToken).entrySet()) {
      assertThat(
              strings(
                  "SELECT token_hash || ' ' || token_prefix || ' ' || status FROM"
                      + " argos.agent_tokens WHERE app_id = ?",
                  e.getKey()))
          .containsExactly(
              AgentTokens.sha256Hex(e.getValue())
                  + " "
                  + AgentTokens.prefix(e.getValue())
                  + " ACTIVE");
    }
    // No column of any token row holds the token itself (SEC-02).
    assertThat(String.join("\n", strings("SELECT t::text FROM argos.agent_tokens t")))
        .doesNotContain(orderToken)
        .doesNotContain(paymentToken);

    List<AuditLog.Row> created = AuditLog.rows(env, "APP_CREATED");
    assertThat(created)
        .filteredOn(r -> List.of("order-api", "payment-api").contains(r.targetName()))
        .hasSize(2)
        .allSatisfy(r -> assertThat(r.actorType()).isEqualToIgnoringCase("system"));
    List<AuditLog.Row> issued = AuditLog.rows(env, "TOKEN_ISSUED");
    assertThat(issued)
        .filteredOn(
            r ->
                r.targetName() != null
                    && (r.targetName().equals(AgentTokens.prefix(orderToken))
                        || r.targetName().equals(AgentTokens.prefix(paymentToken))))
        .hasSize(2)
        .allSatisfy(
            r -> {
              assertThat(r.actorType()).isEqualToIgnoringCase("system");
              assertThat(r.reason()).isEqualTo("registered");
            });
    assertThat(String.valueOf(AuditLog.all(env)))
        .doesNotContain(orderToken)
        .doesNotContain(paymentToken);

    HelloResult orderHello = plaintextHello(server, orderToken, "order-api", "order-api-1");
    assertThat(orderHello.accepted()).as(orderHello.toString()).isTrue();
    HelloResult paymentHello = plaintextHello(server, paymentToken, "payment-api", "payment-api-1");
    assertThat(paymentHello.accepted()).as(paymentHello.toString()).isTrue();

    // The alert engine's recommended rules job (start and every minute) covers both apps.
    Instant deadline = Instant.now().plus(RULES_BUDGET);
    while ((activeRules(order) == 0 || activeRules(payment) == 0)
        && Instant.now().isBefore(deadline)) {
      Thread.sleep(1000);
    }
    assertThat(activeRules(order)).as("recommended rules of order-api").isPositive();
    assertThat(activeRules(payment)).as("recommended rules of payment-api").isPositive();

    String log = IntegrationLogs.since(logOffset);
    assertThat(log)
        .contains("DEMO MODE (argos.demo.enabled=true): plaintext gRPC is enabled")
        .contains("Bootstrap application order-api/demo")
        .contains("Bootstrap application payment-api/demo")
        .doesNotContain(orderToken)
        .doesNotContain(paymentToken);

    // Restart: the same file changes nothing.
    int appCreatedBefore = AuditLog.rows(env, "APP_CREATED").size();
    int tokenIssuedBefore = AuditLog.rows(env, "TOKEN_ISSUED").size();
    server.close();
    ArgosTestServer again = closeLater(ArgosTestServer.start(env, demoProperties()));
    assertThat(appId("order-api")).isEqualTo(order);
    assertThat(appId("payment-api")).isEqualTo(payment);
    assertThat(
            ints("SELECT count(*) FROM argos.agent_tokens WHERE app_id IN (?, ?)", order, payment))
        .containsExactly(2);
    assertThat(AuditLog.rows(env, "APP_CREATED")).hasSize(appCreatedBefore);
    assertThat(AuditLog.rows(env, "TOKEN_ISSUED")).hasSize(tokenIssuedBefore);
    assertThat(plaintextHello(again, orderToken, "order-api", "order-api-2").accepted()).isTrue();
    assertThat(IntegrationLogs.since(logOffset)).contains("already registered");
    again.close();
  }

  @Test
  @DisplayName(
      "T-NFR-16d without demo mode a plaintext listener refuses a valid token's Hello"
          + " (PLAINTEXT_NOT_ALLOWED, FAILED_PRECONDITION) and setup/status demo_mode=false")
  void demoOff() throws Exception {
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, "plain-off", ENV));
    }
    ArgosTestServer server =
        closeLater(ArgosTestServer.start(env, Map.of("argos.collector.tls.enabled", "false")));
    assertThat(demoMode(server)).isFalse();
    HelloResult hello = plaintextHello(server, token.token(), "plain-off", "plain-off-1");
    assertThat(hello.accepted()).as(hello.toString()).isFalse();
    assertThat(hello.rejectReason()).isEqualTo(RejectReason.PLAINTEXT_NOT_ALLOWED);
    assertThat(hello.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    server.close();
  }
}
