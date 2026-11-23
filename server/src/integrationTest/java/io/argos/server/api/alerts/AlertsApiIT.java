/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.metric.AlertSampleIntake;
import io.argos.server.auth.Role;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The alert REST API of 14 §4.13 and the firing fields of the dashboard and the overview (14 §4.2,
 * §4.4, ADR-0010 C-212 (2)) on a real server, PostgreSQL and ClickHouse (WO-25): rule CRUD with
 * history, template diff and audit, {@code 422 template_variable_forbidden} (SEC-39), templates and
 * their apply, mutes (30-day limit), a real FIRING alert from fake agent transactions shown by the
 * dashboard, {@code /overview}, {@code /overview/alerts} and the event history, its acknowledgement
 * ({@code 409} when not FIRING), the statistics, {@code server.channel_failures_24h} against the
 * delivery rows and the preview over the rollups.
 */
@Tag("integration")
@Tag("req:F-S06")
@Tag("req:F-S17")
@Tag("req:F-S26")
@Tag("req:C-212")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlertsApiIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient viewer;
  private static ApiTestClient operator;
  private static String operatorName;
  private static int appId;
  private static int otherAppId;
  private static String appName;
  private static AgentTokens.IssuedToken token;

  /** The firing of {@link #firingRuleId} (set by the firing test). */
  private static String firingEventId;

  private static String firingRuleId;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
    viewer = login(Role.VIEWER);
    TestUsers.TestUser op = new TestUsers(env).create(Role.OPERATOR);
    operatorName = op.username();
    operator = login(op);
    appName = "alerts-api-" + Long.toHexString(System.nanoTime());
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      otherAppId = AgentTokens.ensureApp(c, appName + "-b", "stage");
      token = AgentTokens.issue(c, appId);
    }
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

  private static ApiTestClient login(Role role) {
    return login(new TestUsers(env).create(role));
  }

  private static ApiTestClient login(TestUsers.TestUser user) {
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static Map<String, Object> rule(String name, int app, String metric, double warn) {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("name", name);
    rule.put("target", Map.of("app_id", app, "env", "prod", "type", "app", "ids", List.of()));
    rule.put("metric", metric);
    rule.put("window", "30s");
    rule.put("for", "0s");
    rule.put("condition", Map.of("operator", ">=", "warn", warn));
    rule.put("min_samples", 1);
    rule.put("warmup_grace", "0s");
    return rule;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static JsonNode find(JsonNode array, String field, String value) {
    for (JsonNode n : array) {
      if (n.path(field).asString().equals(value)) {
        return n;
      }
    }
    return null;
  }

  @Test
  @Order(1)
  @DisplayName(
      "14 §4.13 rule CRUD: create 201, list, detail with history, update → version 2, disable and"
          + " enable 204, clone 201, delete 204 then 404; each change audited")
  void ruleCrud() {
    ApiResponse created =
        operator.post("/api/v1/alerts/rules", rule("crud p95", appId, "response_time_p95", 3000));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode rule = created.json().path("data");
    String id = rule.path("rule_id").asString();
    assertThat(rule.path("version").asInt()).isEqualTo(1);
    assertThat(rule.path("metric").asString()).isEqualTo("response_time_p95");
    assertThat(rule.path("target").path("app_id").asInt()).isEqualTo(appId);
    assertThat(rule.path("condition").path("warn").asDouble()).isEqualTo(3000.0);

    ApiResponse list = viewer.get("/api/v1/alerts/rules?app=" + appId);
    assertThat(list.status()).isEqualTo(200);
    assertThat(find(list.json().path("data"), "rule_id", id)).as(list.body()).isNotNull();

    // The response is a valid request body (read-only fields are ignored).
    Map<String, Object> changed = rule("crud p95", appId, "response_time_p95", 2500);
    changed.put("rule_id", id);
    changed.put("version", 1);
    ApiResponse updated = operator.put("/api/v1/alerts/rules/" + id, changed);
    assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
    assertThat(updated.json().path("data").path("version").asInt()).isEqualTo(2);

    ApiResponse detail = viewer.get("/api/v1/alerts/rules/" + id);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.json().path("data").path("rule").path("condition").path("warn").asDouble())
        .isEqualTo(2500.0);
    JsonNode history = detail.json().path("data").path("history");
    assertThat(history).hasSize(2);
    assertThat(history.get(0).path("version").asInt()).isEqualTo(2);
    assertThat(detail.json().path("data").path("template_diff").isNull()).isTrue();

    assertThat(operator.post("/api/v1/alerts/rules/" + id + "/disable", null).status())
        .isEqualTo(204);
    ApiResponse disabled = viewer.get("/api/v1/alerts/rules?app=" + appId + "&enabled=false");
    assertThat(find(disabled.json().path("data"), "rule_id", id)).isNotNull();
    assertThat(operator.post("/api/v1/alerts/rules/" + id + "/enable", null).status())
        .isEqualTo(204);
    ApiResponse enabled = viewer.get("/api/v1/alerts/rules?app=" + appId + "&enabled=true");
    assertThat(find(enabled.json().path("data"), "rule_id", id)).isNotNull();

    ApiResponse clone =
        operator.post(
            "/api/v1/alerts/rules/" + id + "/clone", Map.of("app", otherAppId, "env", "stage"));
    assertThat(clone.status()).as(clone.toString()).isEqualTo(201);
    assertThat(clone.json().path("data").path("target").path("app_id").asInt())
        .isEqualTo(otherAppId);
    assertThat(clone.json().path("data").path("target").path("env").asString()).isEqualTo("stage");

    assertThat(operator.delete("/api/v1/alerts/rules/" + id).status()).isEqualTo(204);
    assertThat(viewer.get("/api/v1/alerts/rules/" + id).status()).isEqualTo(404);
    ApiResponse after = viewer.get("/api/v1/alerts/rules?app=" + appId);
    assertThat(find(after.json().path("data"), "rule_id", id)).isNull();

    List<String> actions =
        AuditLog.all(env).stream()
            .filter(r -> id.equals(r.targetId()))
            .map(AuditLog.Row::action)
            .toList();
    assertThat(actions).contains("RULE_CREATED", "RULE_UPDATED", "RULE_DISABLED", "RULE_DELETED");

    // Roles (14 §7.1): a viewer reads but never writes.
    assertThat(
            viewer.post("/api/v1/alerts/rules", rule("viewer", appId, "error_rate", 0.1)).status())
        .isEqualTo(403);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-39 a message template with a variable outside the allowed set → 422"
          + " template_variable_forbidden; an unknown field → 400 invalid_request")
  void forbiddenTemplateVariable() {
    Map<String, Object> bad = rule("bad template", appId, "error_rate", 0.1);
    bad.put("message_template", "{{app}} {{db_password}}");
    ApiResponse r = operator.post("/api/v1/alerts/rules", bad);
    assertThat(r.status()).as(r.toString()).isEqualTo(422);
    assertThat(r.errorCode()).contains("template_variable_forbidden");

    Map<String, Object> unknown = rule("unknown field", appId, "error_rate", 0.1);
    unknown.put("webhook_url", "https://example.invalid/");
    ApiResponse u = operator.post("/api/v1/alerts/rules", unknown);
    assertThat(u.status()).as(u.toString()).isEqualTo(400);
    assertThat(u.errorCode()).contains("invalid_request");

    ApiResponse noApp = operator.post("/api/v1/alerts/rules", rule("ghost", 999_999, "tps", 1));
    assertThat(noApp.status()).isEqualTo(400);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-033 GET /alerts/templates lists the 14 templates; apply creates the application"
          + " rules once and skips them the second time")
  void templates() throws Exception {
    ApiResponse list = viewer.get("/api/v1/alerts/templates");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().path("data")).hasSize(14);
    JsonNode errorRate = find(list.json().path("data"), "key", "error_rate");
    assertThat(errorRate).isNotNull();
    assertThat(errorRate.path("defaults").get(0).path("rule").path("metric").asString())
        .isEqualTo("error_rate");
    // C-228 (6): every part's rule names its template and version, as a rule made from it does.
    for (JsonNode template : list.json().path("data")) {
      for (JsonNode part : template.path("defaults")) {
        assertThat(part.path("rule").path("template_key").asString())
            .as(template.path("key").asString())
            .isEqualTo(template.path("key").asString());
        assertThat(part.path("rule").path("template_version").asInt())
            .as(template.path("key").asString())
            .isEqualTo(template.path("version").asInt());
      }
    }

    int fresh;
    try (Connection c = env.connect(PgAccount.APP)) {
      fresh = AgentTokens.ensureApp(c, appName + "-tpl", "prod");
      // Keep the engine's recommended-rule job away from this application.
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE argos.apps SET recommended_rules_applied = true WHERE id = ?")) {
        ps.setInt(1, fresh);
        ps.executeUpdate();
      }
    }
    ApiResponse applied =
        operator.post(
            "/api/v1/alerts/templates/apply",
            Map.of("app", fresh, "keys", List.of("error_rate", "p95_latency", "ingest_lag")));
    assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
    assertThat(applied.json().path("data").path("created")).hasSize(2);
    JsonNode serverScope = find(applied.json().path("data").path("skipped"), "key", "ingest_lag");
    assertThat(serverScope).isNotNull();
    assertThat(serverScope.path("reason").asString()).isEqualTo("server_scope");

    ApiResponse again =
        operator.post(
            "/api/v1/alerts/templates/apply",
            Map.of("app", fresh, "keys", List.of("error_rate", "p95_latency")));
    assertThat(again.json().path("data").path("created")).isEmpty();
    for (JsonNode s : again.json().path("data").path("skipped")) {
      assertThat(s.path("reason").asString()).isEqualTo("exists");
    }
    ApiResponse rules = viewer.get("/api/v1/alerts/rules?app=" + fresh);
    assertThat(rules.json().path("data")).hasSize(2);
    for (JsonNode r : rules.json().path("data")) {
      assertThat(r.path("target").path("env").asString())
          .as("the application's env")
          .isEqualTo("prod");
    }
  }

  @Test
  @Order(4)
  @DisplayName(
      "14 §4.13 mutes: longer than 30 days → 400, 30 days → 201 and listed, delete → 204; viewers"
          + " may not read them")
  void mutes() {
    Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> tooLong = new LinkedHashMap<>();
    tooLong.put("target", Map.of("app", appId));
    tooLong.put("from", from.toString());
    tooLong.put("to", from.plus(Duration.ofDays(30)).plusSeconds(1).toString());
    tooLong.put("reason", "maintenance");
    ApiResponse refused = operator.post("/api/v1/alerts/mutes", tooLong);
    assertThat(refused.status()).as(refused.toString()).isEqualTo(400);
    assertThat(refused.body()).contains("longer_than_30_days");

    Map<String, Object> ok = new LinkedHashMap<>(tooLong);
    ok.put("to", from.plus(Duration.ofDays(30)).toString());
    ApiResponse created = operator.post("/api/v1/alerts/mutes", ok);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String muteId = created.json().path("data").path("mute_id").asString();
    assertThat(created.json().path("data").path("target").path("app").asInt()).isEqualTo(appId);

    ApiResponse list = operator.get("/api/v1/alerts/mutes");
    assertThat(find(list.json().path("data"), "mute_id", muteId)).isNotNull();
    assertThat(viewer.get("/api/v1/alerts/mutes").status()).isEqualTo(403);

    assertThat(operator.delete("/api/v1/alerts/mutes/" + muteId).status()).isEqualTo(204);
    ApiResponse after = operator.get("/api/v1/alerts/mutes");
    assertThat(find(after.json().path("data"), "mute_id", muteId)).isNull();
    assertThat(operator.delete("/api/v1/alerts/mutes/" + muteId).status()).isEqualTo(404);
    assertThat(AuditLog.rows(env, "MUTE_CREATED")).isNotEmpty();
    assertThat(AuditLog.rows(env, "MUTE_DELETED")).isNotEmpty();
  }

  @Test
  @Order(5)
  @DisplayName(
      "C-212 (2) a real FIRING alert from fake agent errors: dashboard firing[], overview firing"
          + " and alerts_firing_total, /overview/alerts and the event history show it; ACK → 200,"
          + " again → 409 invalid_state, unknown event → 404; the statistics count it")
  void firingAndAck() throws Exception {
    ApiResponse saved =
        operator.post("/api/v1/alerts/rules", rule("api error rate", appId, "error_rate", 0.5));
    assertThat(saved.status()).as(saved.toString()).isEqualTo(201);
    firingRuleId = saved.json().path("data").path("rule_id").asString();

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
      AlertSampleIntake intake = server.bean(AlertSampleIntake.class);
      long before = intake.xlogPointsReceived();
      long now = agent.clock().nowMs();
      List<TransactionRecord> txs = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        txs.add(
            agent
                .records()
                .withError(
                    agent.records().http("GET /cart", now - 300 - i, 80_000),
                    "java.lang.IllegalStateException",
                    "cart store down",
                    "com.example.shop.CartService.load(CartService.java:7)")
                .build());
      }
      assertThat(agent.sendTransactions(agent.batches().transactions(txs)).ok()).isTrue();
      Awaitility.await()
          .atMost(20, TimeUnit.SECONDS)
          .until(() -> intake.xlogPointsReceived() >= before + 10);
      AlertEngine engine = server.bean(AlertEngine.class);
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .until(
              () -> {
                engine.evaluateNow();
                return engine.activeAlerts().stream()
                    .anyMatch(a -> a.ruleId().toString().equals(firingRuleId));
              });
    }

    Instant to = Instant.now().plusSeconds(60);
    Instant from = to.minus(Duration.ofHours(1));
    ApiResponse dashboard =
        viewer.get(
            "/api/v1/apps/"
                + appId
                + "/dashboard?from="
                + enc(from.toString())
                + "&to="
                + enc(to.truncatedTo(ChronoUnit.SECONDS).toString()));
    assertThat(dashboard.status()).as(dashboard.toString()).isEqualTo(200);
    JsonNode firing = dashboard.json().path("data").path("firing");
    assertThat(firing.isArray()).isTrue();
    JsonNode item = null;
    for (JsonNode f : firing) {
      if (f.path("rule").path("id").asString().equals(firingRuleId)) {
        item = f;
      }
    }
    assertThat(item).as(firing.toString()).isNotNull();
    firingEventId = item.path("event_id").asString();
    assertThat(item.path("state").asString()).isEqualTo("FIRING");
    assertThat(item.path("severity").asString()).isEqualTo("warn");
    assertThat(item.path("app").path("id").asInt()).isEqualTo(appId);
    assertThat(item.path("app").path("name").asString()).isEqualTo(appName);
    assertThat(item.path("target").path("type").asString()).isEqualTo("app");
    assertThat(item.path("value").asDouble()).isEqualTo(1.0);
    assertThat(item.path("threshold").asDouble()).isEqualTo(0.5);
    assertThat(item.path("deeplink").asString())
        .contains("/apps/" + appId + "/dashboard?")
        .contains("alert=" + firingEventId)
        .doesNotContain("live");

    ApiResponse alerts = viewer.get("/api/v1/overview/alerts");
    assertThat(alerts.status()).isEqualTo(200);
    JsonNode rows = alerts.json().path("data");
    assertThat(find(rows, "event_id", firingEventId)).isNotNull();
    int warnOfApp = 0;
    int criticalOfApp = 0;
    for (JsonNode r : rows) {
      if (r.path("app").path("id").asInt(-1) == appId) {
        if (r.path("severity").asString().equals("critical")) {
          criticalOfApp++;
        } else {
          warnOfApp++;
        }
      }
    }
    // The overview is cached for 5 s per filter: wait for a fresh one.
    int expectedWarn = warnOfApp;
    int expectedCritical = criticalOfApp;
    int expectedTotal = rows.size();
    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              ApiResponse overview = viewer.get("/api/v1/overview");
              assertThat(overview.status()).isEqualTo(200);
              JsonNode app = find(overview.json().path("data").path("apps"), "app_id", "" + appId);
              assertThat(app).isNotNull();
              assertThat(app.path("firing").path("warn").asInt()).isEqualTo(expectedWarn);
              assertThat(app.path("firing").path("critical").asInt()).isEqualTo(expectedCritical);
              assertThat(overview.json().path("data").path("alerts_firing_total").asInt())
                  .isEqualTo(expectedTotal);
            });
    assertThat(viewer.get("/api/v1/overview/alerts?severity=critical").json().path("data"))
        .allSatisfy(r -> assertThat(r.path("severity").asString()).isEqualTo("critical"));
    assertThat(viewer.get("/api/v1/overview/alerts?state=bogus").status()).isEqualTo(400);

    // Acknowledgement (F-S26): FIRING only.
    ApiResponse ack =
        operator.post(
            "/api/v1/alerts/events/" + firingEventId + "/ack", Map.of("note", "looking into it"));
    assertThat(ack.status()).as(ack.toString()).isEqualTo(200);
    assertThat(ack.json().path("data").path("state").asString()).isEqualTo("ACKED");
    ApiResponse twice = operator.post("/api/v1/alerts/events/" + firingEventId + "/ack", Map.of());
    assertThat(twice.status()).as(twice.toString()).isEqualTo(409);
    assertThat(twice.errorCode()).contains("invalid_state");
    assertThat(
            operator.post("/api/v1/alerts/events/" + UUID.randomUUID() + "/ack", Map.of()).status())
        .isEqualTo(404);
    assertThat(viewer.post("/api/v1/alerts/events/" + firingEventId + "/ack", Map.of()).status())
        .isEqualTo(403);
    assertThat(
            operator
                .post(
                    "/api/v1/alerts/events/" + firingEventId + "/ack",
                    Map.of("note", "x".repeat(600)))
                .status())
        .isEqualTo(400);
    assertThat(AuditLog.rows(env, "RULE_ACKED")).isNotEmpty();

    JsonNode acked =
        find(
            viewer.get("/api/v1/overview/alerts?state=ACKED").json().path("data"),
            "event_id",
            firingEventId);
    assertThat(acked).isNotNull();
    assertThat(acked.path("note").asString()).isEqualTo("looking into it");
    assertThat(acked.path("acked_by").asString()).isNotEmpty();

    // The history (14 §4.13 /alerts/events).
    Instant hFrom = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
    Instant hTo = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    ApiResponse events =
        viewer.get(
            "/api/v1/alerts/events?from="
                + enc(hFrom.toString())
                + "&to="
                + enc(hTo.toString())
                + "&rule="
                + firingRuleId);
    assertThat(events.status()).as(events.toString()).isEqualTo(200);
    List<String> states = new ArrayList<>();
    for (JsonNode e : events.json().path("data")) {
      states.add(e.path("state").asString());
      assertThat(e.path("rule").path("id").asString()).isEqualTo(firingRuleId);
    }
    assertThat(states).contains("FIRING", "ACKED");
    JsonNode fired = find(events.json().path("data"), "event_id", firingEventId);
    assertThat(fired).isNotNull();
    assertThat(fired.path("state").asString()).isEqualTo("FIRING");
    assertThat(fired.path("deliveries").isArray()).isTrue();

    // Paging: one row per page continues with the cursor and never repeats a row.
    ApiResponse page1 =
        viewer.get(
            "/api/v1/alerts/events?limit=1&from="
                + enc(hFrom.toString())
                + "&to="
                + enc(hTo.toString())
                + "&rule="
                + firingRuleId);
    String cursor = page1.json().path("meta").path("next_cursor").asString();
    assertThat(cursor).isNotEmpty();
    ApiResponse page2 =
        viewer.get(
            "/api/v1/alerts/events?limit=1&from="
                + enc(hFrom.toString())
                + "&to="
                + enc(hTo.toString())
                + "&rule="
                + firingRuleId
                + "&cursor="
                + enc(cursor));
    assertThat(page2.status()).isEqualTo(200);
    assertThat(page2.json().path("data").get(0).path("event_id").asString())
        .isNotEqualTo(page1.json().path("data").get(0).path("event_id").asString());
    assertThat(
            viewer
                .get(
                    "/api/v1/alerts/events?from="
                        + enc(hFrom.toString())
                        + "&to="
                        + enc(hTo.toString())
                        + "&limit=501")
                .status())
        .isEqualTo(400);

    ApiResponse stats = viewer.get("/api/v1/alerts/stats?days=7&app=" + appId);
    assertThat(stats.status()).isEqualTo(200);
    JsonNode s = find(stats.json().path("data"), "rule_id", firingRuleId);
    assertThat(s).as(stats.body()).isNotNull();
    assertThat(s.path("firings").asLong()).isEqualTo(1);
    assertThat(s.path("avg_time_to_ack_s").isNumber()).isTrue();
    assertThat(viewer.get("/api/v1/alerts/stats?days=3").status()).isEqualTo(400);
  }

  @Test
  @Order(6)
  @DisplayName(
      "14 §4.2 server.channel_failures_24h = failed deliveries of the last 24 h (overview and"
          + " /server/health); /alerts/events?delivery=failed returns the event with its"
          + " deliveries")
  void channelFailures() throws Exception {
    assertThat(firingEventId).as("the firing test ran first").isNotNull();
    UUID channel = UUID.randomUUID();
    Instant now = Instant.now();
    try (Connection c = env.connect(PgAccount.APP)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.notification_channels (id, name, type, config_enc,"
                  + " config_masked, enabled, created_at, updated_at) VALUES (?, 'it-failing',"
                  + " 'webhook', '\\x00'::bytea, '{}'::jsonb, false, now(), now())")) {
        ps.setObject(1, channel);
        ps.executeUpdate();
      }
      insertDelivery(c, UUID.fromString(firingEventId), channel, "failed", 4, "http_status", now);
      insertDelivery(c, UUID.randomUUID(), channel, "sent", 1, null, now);
      insertDelivery(
          c, UUID.randomUUID(), channel, "failed", 4, "timeout", now.minus(Duration.ofHours(25)));
    }
    long expected;
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM argos.notification_deliveries WHERE status = 'failed'"
                    + " AND sent_at >= now() - interval '24 hours'");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      expected = rs.getLong(1);
    }
    assertThat(expected).isGreaterThanOrEqualTo(1);

    ApiResponse health = viewer.get("/api/v1/server/health");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.json().path("data").path("channel_failures_24h").asLong())
        .isEqualTo(expected);
    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        viewer
                            .get("/api/v1/overview")
                            .json()
                            .path("data")
                            .path("server")
                            .path("channel_failures_24h")
                            .asLong())
                    .isEqualTo(expected));

    Instant hFrom = now.minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
    Instant hTo = now.plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    ApiResponse failed =
        viewer.get(
            "/api/v1/alerts/events?delivery=failed&from="
                + enc(hFrom.toString())
                + "&to="
                + enc(hTo.toString()));
    assertThat(failed.status()).as(failed.toString()).isEqualTo(200);
    JsonNode e = find(failed.json().path("data"), "event_id", firingEventId);
    assertThat(e).as(failed.body()).isNotNull();
    JsonNode d = e.path("deliveries").get(0);
    assertThat(d.path("channel_id").asString()).isEqualTo(channel.toString());
    assertThat(d.path("status").asString()).isEqualTo("failed");
    assertThat(d.path("attempts").asInt()).isEqualTo(4);
    assertThat(d.path("last_error_code").asString()).isEqualTo("http_status");
    for (JsonNode row : failed.json().path("data")) {
      assertThat(row.path("deliveries").toString()).contains("\"failed\"");
    }
  }

  @Test
  @Order(7)
  @DisplayName(
      "14 §4.13 preview: the error-rate rule replayed over the minute rollups of the last day"
          + " finds the firing; a metric outside the rollups → 400 preview_unsupported")
  void preview() {
    assertThat(firingRuleId).as("the firing test ran first").isNotNull();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rule", rule("preview", appId, "error_rate", 0.5));
    body.put("days", 1);
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = operator.post("/api/v1/alerts/rules/preview", body);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              JsonNode data = r.json().path("data");
              assertThat(data.path("step_s").asLong()).isEqualTo(300);
              assertThat(data.path("series").path("ts")).hasSize(288);
              assertThat(data.path("series").path("value")).hasSize(288);
              assertThat(data.path("count").asInt()).isGreaterThanOrEqualTo(1);
              assertThat(data.path("firings").get(0).path("peak_value").asDouble()).isEqualTo(1.0);
            });

    Map<String, Object> unsupported = new LinkedHashMap<>();
    unsupported.put("rule", rule("preview active", appId, "active_transactions", 10));
    ApiResponse u = operator.post("/api/v1/alerts/rules/preview", unsupported);
    assertThat(u.status()).as(u.toString()).isEqualTo(400);
    assertThat(u.body()).contains("preview_unsupported");
    assertThat(
            operator
                .post(
                    "/api/v1/alerts/rules/preview",
                    Map.of("days", 8, "rule", rule("p", appId, "error_rate", 0.5)))
                .status())
        .isEqualTo(400);
    assertThat(viewer.post("/api/v1/alerts/rules/preview", body).status()).isEqualTo(403);
  }

  /** The events of {@code ruleId} in the last hour, oldest first (ties of one ms in any order). */
  private static List<JsonNode> events(String ruleId) {
    Instant hFrom = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
    Instant hTo = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    ApiResponse r =
        viewer.get(
            "/api/v1/alerts/events?from="
                + enc(hFrom.toString())
                + "&to="
                + enc(hTo.toString())
                + "&rule="
                + ruleId);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    List<JsonNode> out = new ArrayList<>();
    r.json().path("data").forEach(out::add);
    return out.reversed();
  }

  /** The {@code state/reason} of every event of {@code ruleId}, oldest first. */
  private static List<String> transitions(String ruleId) {
    return events(ruleId).stream()
        .map(
            e ->
                e.path("state").asString()
                    + "/"
                    + (e.path("reason").isNull() ? "null" : e.path("reason").asString()))
        .toList();
  }

  @Test
  @Order(8)
  @Tag("req:C-227")
  @DisplayName(
      "T-API-012a WO-38 response additions on the real engine: every event has its reason"
          + " (condition, acked, rule_disabled; an instance unit of a DOWN instance target_gone,"
          + " a deleted rule rule_deleted), rule.metric and rule.unit in the events, the dashboard"
          + " firing[] and /overview/alerts; the rule detail history names the changing user")
  void reasonsMetricUnitAndHistoryName() throws Exception {
    assertThat(firingRuleId).as("the firing test ran first").isNotNull();
    AlertEngine engine = server.bean(AlertEngine.class);

    // The ACKED alert of the firing test, as the active-alert views show it.
    JsonNode overviewItem =
        find(
            viewer.get("/api/v1/overview/alerts?state=ACKED").json().path("data"),
            "event_id",
            firingEventId);
    assertThat(overviewItem).isNotNull();
    assertThat(overviewItem.path("rule").path("metric").asString()).isEqualTo("error_rate");
    assertThat(overviewItem.path("rule").path("unit").asString()).isEqualTo("ratio");
    Instant to = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
    JsonNode firing =
        viewer
            .get(
                "/api/v1/apps/"
                    + appId
                    + "/dashboard?from="
                    + enc(to.minus(Duration.ofHours(1)).toString())
                    + "&to="
                    + enc(to.toString()))
            .json()
            .path("data")
            .path("firing");
    JsonNode dashboardItem = find(firing, "event_id", firingEventId);
    assertThat(dashboardItem).as(firing.toString()).isNotNull();
    assertThat(dashboardItem.path("rule").path("metric").asString()).isEqualTo("error_rate");
    assertThat(dashboardItem.path("rule").path("unit").asString()).isEqualTo("ratio");

    // Disabling the rule resolves its ACKED alert: reason rule_disabled.
    assertThat(operator.post("/api/v1/alerts/rules/" + firingRuleId + "/disable", null).status())
        .isEqualTo(204);
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(transitions(firingRuleId))
                    .containsExactlyInAnyOrder(
                        "PENDING/condition",
                        "FIRING/condition",
                        "ACKED/acked",
                        "RESOLVED/rule_disabled"));
    List<JsonNode> history = events(firingRuleId);
    for (JsonNode e : history) {
      assertThat(e.path("rule").path("metric").asString()).isEqualTo("error_rate");
      assertThat(e.path("rule").path("unit").asString()).isEqualTo("ratio");
    }
    assertThat(history)
        .filteredOn(e -> e.path("state").asString().equals("RESOLVED"))
        .singleElement()
        .satisfies(e -> assertThat(e.path("from_state").asString()).isEqualTo("ACKED"));

    // An instance unit (no_data ignore) whose instance becomes DOWN: reason target_gone.
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-gone:8080")
            .clock(AgentClock.skewed(Duration.ZERO))
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      int instance = agent.instanceId();
      AlertSampleIntake intake = server.bean(AlertSampleIntake.class);
      long before = intake.xlogPointsReceived();
      long now = agent.clock().nowMs();
      List<TransactionRecord> txs = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        txs.add(
            agent
                .records()
                .withError(
                    agent.records().http("GET /pay", now - 300 - i, 80_000),
                    "java.lang.IllegalStateException",
                    "payment down",
                    "com.example.shop.PayService.pay(PayService.java:9)")
                .build());
      }
      assertThat(agent.sendTransactions(agent.batches().transactions(txs)).ok()).isTrue();
      Awaitility.await()
          .atMost(20, TimeUnit.SECONDS)
          .until(() -> intake.xlogPointsReceived() >= before + 10);

      Map<String, Object> body = rule("instance error rate", appId, "error_rate", 0.5);
      body.put(
          "target",
          Map.of(
              "app_id", appId, "env", "prod", "type", "instance", "ids", List.of("" + instance)));
      ApiResponse saved = operator.post("/api/v1/alerts/rules", body);
      assertThat(saved.status()).as(saved.toString()).isEqualTo(201);
      String instanceRule = saved.json().path("data").path("rule_id").asString();
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .until(
              () -> {
                engine.evaluateNow();
                return engine.activeAlerts().stream()
                    .anyMatch(a -> a.ruleId().toString().equals(instanceRule));
              });

      engine.onInstanceState(
          new InstanceStateChange(
              instance,
              appId,
              "prod",
              InstanceState.UP,
              InstanceState.DOWN,
              Set.of("heartbeat_timeout"),
              Instant.now()));
      engine.evaluateNow();
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(transitions(instanceRule))
                      .contains("PENDING/condition", "FIRING/condition", "RESOLVED/target_gone"));
      JsonNode gone =
          events(instanceRule).stream()
              .filter(e -> e.path("reason").asString("").equals("target_gone"))
              .findFirst()
              .orElseThrow();
      assertThat(gone.path("target").path("type").asString()).isEqualTo("instance");
      assertThat(gone.path("target").path("id").asString()).isEqualTo("" + instance);
      assertThat(gone.path("value").isNull()).as("no value: the target is gone").isTrue();

      // The unit still has errors in its window: it fires again; deleting the rule resolves it.
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .until(
              () -> {
                engine.evaluateNow();
                return engine.activeAlerts().stream()
                    .anyMatch(a -> a.ruleId().toString().equals(instanceRule));
              });
      assertThat(operator.delete("/api/v1/alerts/rules/" + instanceRule).status()).isEqualTo(204);
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .untilAsserted(
              () -> assertThat(transitions(instanceRule)).contains("RESOLVED/rule_deleted"));
    }

    // The history of a rule names the user of each version (changed_by_name).
    ApiResponse detail = viewer.get("/api/v1/alerts/rules/" + firingRuleId);
    assertThat(detail.status()).isEqualTo(200);
    JsonNode versions = detail.json().path("data").path("history");
    assertThat(versions).isNotEmpty();
    for (JsonNode h : versions) {
      assertThat(h.path("changed_by").asString()).isNotEmpty();
      assertThat(h.path("changed_by_name").asString()).isEqualTo(operatorName);
    }
    assertThat(detail.json().path("data").path("rule").path("metric").asString())
        .isEqualTo("error_rate");
  }

  private static void insertDelivery(
      Connection c, UUID event, UUID channel, String status, int attempt, String error, Instant at)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO argos.notification_deliveries (id, alert_event_id, channel_id, attempt,"
                + " status, http_status, error, sent_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, event);
      ps.setObject(3, channel);
      ps.setInt(4, attempt);
      ps.setString(5, status);
      ps.setString(6, error);
      ps.setTimestamp(7, Timestamp.from(at));
      ps.executeUpdate();
    }
  }
}
