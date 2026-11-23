/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.alert.metric.AlertSampleIntake;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SV-030 (N-23): from the moment an alert condition is met to the delivery request at the channel
 * at most 30 seconds, end to end on a real server, PostgreSQL and ClickHouse: a fake agent sends
 * failed transactions, the engine evaluates on its own 10-second schedule (no forced evaluation),
 * the first notification of the group leaves at once (leading edge, WO-25 plan §3.3), and the
 * notifier posts it to an in-process HTTPS webhook receiver. The condition-met instant is taken
 * just before the transactions are sent (an upper bound of the latency). A shared laptop's time is
 * a reference value; the 30-second assertion stays.
 */
@Tag("integration")
@Tag("req:N-23")
@Tag("req:F-S06")
class AlertDeliveryLatencyIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration N23 = Duration.ofSeconds(30);

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static FakeWebhookReceiver receiver;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Path dir = Files.createTempDirectory("argos-alert-latency-it");
    TestCertificates.TestPki pki = TestTls.pki(dir);
    SSLContext tls = TestTls.serverContext(pki);
    receiver = FakeWebhookReceiver.https(tls);
    TestMasterKey key = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(key.property(), key.file().toString());
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    server = ArgosTestServer.start(env, p);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (receiver != null) {
      receiver.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role);
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  @Test
  @DisplayName(
      "T-SV-030 failed transactions → engine (10 s schedule, leading-edge group) → webhook channel:"
          + " the receiver gets the FIRING notification at most 30 s after the condition is met")
  void conditionToDeliveryWithin30Seconds() throws Exception {
    ApiTestClient admin = login(Role.ADMIN);
    ApiTestClient operator = login(Role.OPERATOR);

    String appName = "latency-" + Long.toHexString(System.nanoTime());
    AgentTokens.IssuedToken token;
    int appId;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      token = AgentTokens.issue(c, appId);
    }

    Map<String, Object> channel = new LinkedHashMap<>();
    channel.put("type", "webhook");
    channel.put("name", "latency-hook");
    channel.put("target", receiver.url("/hook"));
    ApiResponse created = admin.post("/api/v1/alerts/channels", channel);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String channelId = created.json().path("data").path("channel").path("channel_id").asString();
    assertThat(channelId).as(created.body()).isNotEmpty();

    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("name", "T-SV-030 error rate");
    rule.put("target", Map.of("app_id", appId, "env", "prod", "type", "app", "ids", List.of()));
    rule.put("metric", "error_rate");
    rule.put("window", "30s");
    rule.put("for", "0s");
    rule.put("condition", Map.of("operator", ">=", "warn", 0.5));
    rule.put("min_samples", 1);
    rule.put("warmup_grace", "0s");
    rule.put("channels", Map.of("warn", List.of(channelId), "critical", List.of()));
    ApiResponse saved = operator.post("/api/v1/alerts/rules", rule);
    assertThat(saved.status()).as(saved.toString()).isEqualTo(201);
    String ruleId = saved.json().path("data").path("rule_id").asString();

    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-a:8080")
            .clock(AgentClock.skewed(Duration.ZERO))
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();

      long before = server.bean(AlertSampleIntake.class).xlogPointsReceived();
      long now = agent.clock().nowMs();
      List<TransactionRecord> txs = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        txs.add(
            agent
                .records()
                .withError(
                    agent.records().http("GET /orders", now - 200 - i, 120_000),
                    "java.lang.IllegalStateException",
                    "order store down",
                    "com.example.shop.OrderService.load(OrderService.java:42)")
                .build());
      }
      Instant conditionMet = Instant.now();
      assertThat(agent.sendTransactions(agent.batches().transactions(txs)).ok()).isTrue();
      Awaitility.await()
          .atMost(20, TimeUnit.SECONDS)
          .until(() -> server.bean(AlertSampleIntake.class).xlogPointsReceived() >= before + 10);

      List<FakeWebhookReceiver.Received> got = receiver.await(1, Duration.ofSeconds(60));
      FakeWebhookReceiver.Received first = got.get(0);
      Duration latency = Duration.between(conditionMet, first.at());
      System.out.println(
          "[T-SV-030] condition met → webhook receipt: " + latency.toMillis() + " ms");
      assertThat(latency).as("N-23: condition met → delivery request").isLessThanOrEqualTo(N23);

      JsonNode body = JSON.readTree(first.bodyText());
      assertThat(body.path("schema_version").asInt()).isEqualTo(1);
      assertThat(body.path("alert").path("kind").asString()).isEqualTo("firing");
      assertThat(first.bodyText()).contains(ruleId);
      assertThat(first.header("X-Argos-Signature")).as("HMAC signature (SEC-53)").startsWith("t=");

      // The firing is the dashboard's and the overview's active alert.
      ApiResponse overview = operator.get("/api/v1/overview/alerts");
      assertThat(overview.status()).isEqualTo(200);
      boolean listed = false;
      for (JsonNode item : overview.json().path("data")) {
        if (item.path("rule").path("id").asString().equals(ruleId)) {
          listed = true;
          assertThat(item.path("state").asString()).isEqualTo("FIRING");
          assertThat(item.path("severity").asString()).isEqualTo("warn");
          assertThat(item.path("app").path("id").asInt()).isEqualTo(appId);
        }
      }
      assertThat(listed).as(overview.body()).isTrue();
    }
  }
}
