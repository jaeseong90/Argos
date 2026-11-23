/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.ConfigReplies;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 * T-SV-042 on a real server, PostgreSQL and the TLS gRPC listener with protocol-level fake agents
 * (WO-26): a configuration change needs a reason and schema-valid keys allowed by at least one
 * target, previews a diff and the per-instance rejections, is pushed to the connected instances
 * (only the keys each one's remote-config.allow names) with per-instance results applied, partial
 * (server or agent rejections) and pending for an instance without a session, which gets the values
 * in its next ServerHello.config; the app's configuration shows the server-held values and the
 * drift; a second change is reverted (ConfigPush.revert with the previous value); the instance's
 * effective configuration says which server-held values it runs; every step is audited. The real
 * agent's application of a push (WO-17) is tested against this server by
 * ui/e2e/specs/agent-commands.spec.ts (T-AG-069, T-SEC-23, T-AG-043 (stack): applied within 10 s
 * and run as remote, a key outside remote-config.allow refused, the kill switch) and against
 * FakeCollector by agent-testing io.argos.agent.testing.remoteconfig (RemoteConfigE2eTest,
 * KillSwitchRemoteTest).
 */
@Tag("integration")
@Tag("req:F-S29")
@Tag("req:SEC-23")
@Tag("req:DSN-12-09")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigChangeIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String RATE = "sampling.profile.normal-rate";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient admin;
  private static ApiTestClient operator;
  private static String appName;
  private static int appId;
  private static AgentTokens.IssuedToken token;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();
  private static final Map<String, FakeAgent> BY_NAME = new HashMap<>();
  private static UUID first;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.ratelimit.api-per-user-per-second", "100000");
    p.put("argos.ratelimit.api-burst-per-user", "100000");
    server = ArgosTestServer.start(env, p);
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    admin = login(Role.ADMIN);
    operator = login(Role.OPERATOR);
    appName = "cfg-it-" + Long.toHexString(System.nanoTime());
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      token = AgentTokens.issue(c, appId);
    }
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

  private static FakeAgent agent(
      String name, String hash, Function<ConfigPush, ConfigApplyResult> replies, String... allow) {
    FakeAgent a =
        FakeAgent.builder(channel)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-" + name + ":8080")
            .hello(
                h -> {
                  h.clearRemoteConfigAllow().setConfigHash(hash);
                  for (String pattern : allow) {
                    h.addRemoteConfigAllow(pattern);
                  }
                  return h;
                })
            .onConfigPush(replies)
            .build();
    AGENTS.add(a);
    BY_NAME.put(name, a);
    return a;
  }

  private static FakeAgent connected(FakeAgent a) {
    HelloResult hello = a.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return a;
  }

  private static JsonNode awaitChange(UUID id, String status) {
    JsonNode[] last = new JsonNode[1];
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = admin.get("/api/v1/admin/config/changes/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              last[0] = r.json().path("data");
              assertThat(last[0].path("status").asString()).isEqualTo(status);
            });
    return last[0];
  }

  private static JsonNode resultOf(JsonNode change, int instanceId) {
    for (JsonNode r : change.path("results")) {
      if (r.path("instance_id").asInt() == instanceId) {
        return r;
      }
    }
    throw new AssertionError("no result of instance " + instanceId + " in " + change);
  }

  private static List<ConfigPush> pushesOf(FakeAgent a) {
    List<ConfigPush> out = new ArrayList<>();
    for (ServerMessage m : a.serverMessages()) {
      if (m.hasConfigPush()) {
        out.add(m.getConfigPush());
      }
    }
    return out;
  }

  private static List<String> errorFields(ApiResponse r) {
    List<String> out = new ArrayList<>();
    for (JsonNode d : r.json().path("error").path("details")) {
      out.add(d.path("field").asString() + "=" + d.path("reason").asString());
    }
    return out;
  }

  private static String changesPath() {
    return "/api/v1/admin/apps/" + appId + "/config/changes";
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-042 a change needs a reason and schema-valid keys (400 otherwise, naming the keys)"
          + " that some target allows (422 remote_key_not_allowed otherwise, naming the key and"
          + " the instances); the 201 preview has the diff against"
          + " the server-held values and the per-instance rejections; operators get 403")
  void proposeValidation() {
    connected(agent("a", "aaaaaaaaaaaaaaaa", ConfigReplies.applyAll(), "sampling.**", "log.level"));
    connected(agent("b", "aaaaaaaaaaaaaaaa", ConfigReplies.applyAll(), "log.level"));
    connected(
        agent(
            "c",
            "cccccccccccccccc",
            ConfigReplies.rejecting(Map.of(RATE, RejectCode.INVALID_VALUE)),
            "sampling.**",
            "log.level"));
    FakeAgent d =
        connected(
            agent("d", "aaaaaaaaaaaaaaaa", ConfigReplies.applyAll(), "sampling.**", "log.level"));
    d.cancelSession();
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              JsonNode config = admin.get("/api/v1/admin/apps/" + appId + "/config").json();
              boolean offline = false;
              for (JsonNode i : config.path("data").path("remote_allowed_by_instance")) {
                if (i.path("instance_id").asInt() == d.instanceId()) {
                  offline = !i.path("online").asBoolean();
                }
              }
              assertThat(offline).as("instance d without a session").isTrue();
            });

    ApiResponse noReason = admin.post(changesPath(), "{\"changes\":{\"log.level\":\"DEBUG\"}}");
    assertThat(noReason.status()).as(noReason.toString()).isEqualTo(400);
    assertThat(errorFields(noReason)).containsExactly("reason=required");

    ApiResponse immutable =
        admin.post(
            changesPath(),
            "{\"reason\":\"r\",\"changes\":{\"server.address\":\"evil:9100\",\"log.dir\":\"/tmp\","
                + "\"nope.key\":1,\"capture.stack-depth\":999}}");
    assertThat(immutable.status()).as(immutable.toString()).isEqualTo(400);
    assertThat(errorFields(immutable))
        .containsExactlyInAnyOrder(
            "changes.server.address=immutable",
            "changes.log.dir=immutable",
            "changes.nope.key=unknown_key",
            "changes.capture.stack-depth=invalid_value");

    ApiResponse nobody =
        admin.post(
            changesPath(), "{\"reason\":\"r\",\"changes\":{\"capture.sql-parameters\":\"full\"}}");
    assertThat(nobody.status()).as(nobody.toString()).isEqualTo(422);
    assertThat(nobody.json().path("error").path("code").asString())
        .isEqualTo("remote_key_not_allowed");
    assertThat(errorFields(nobody))
        .contains(
            "changes.capture.sql-parameters=not_allowed_by_targets",
            "targets.instance_ids." + BY_NAME.get("a").instanceId() + "=not_allowed",
            "targets.instance_ids." + d.instanceId() + "=not_allowed");

    ApiResponse forbidden =
        operator.post(changesPath(), "{\"reason\":\"r\",\"changes\":{\"log.level\":\"DEBUG\"}}");
    assertThat(forbidden.status()).as(forbidden.toString()).isEqualTo(403);

    ApiResponse created =
        admin.post(
            changesPath(),
            "{\"reason\":\"Investigate slow checkout (INC-42)\",\"changes\":{\"log.level\":"
                + "\"debug\",\"argos."
                + RATE
                + "\":0.5},\"targets\":{\"all\":true}}");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode data = created.json().path("data");
    assertThat(data.path("status").asString()).isEqualTo("previewed");
    assertThat(data.path("kind").asString()).isEqualTo("config");
    assertThat(data.path("reason").asString()).isEqualTo("Investigate slow checkout (INC-42)");
    first = UUID.fromString(data.path("change_id").asString());
    assertThat(first.version()).isEqualTo(4);
    JsonNode diff = data.path("preview").path("diff");
    assertThat(diff).hasSize(2);
    assertThat(diff.get(0).path("key").asString()).isEqualTo("log.level");
    assertThat(diff.get(0).path("before").isNull()).isTrue();
    assertThat(diff.get(0).path("after").asString()).isEqualTo("DEBUG");
    assertThat(diff.get(1).path("key").asString()).isEqualTo(RATE);
    assertThat(diff.get(1).path("after").asString()).isEqualTo("0.5");
    JsonNode rejected = data.path("preview").path("rejected_keys_by_instance");
    assertThat(rejected).hasSize(1);
    assertThat(rejected.get(0).path("instance_id").asInt())
        .isEqualTo(BY_NAME.get("b").instanceId());
    assertThat(rejected.get(0).path("keys").get(0).asString()).isEqualTo(RATE);
    assertThat(pushesOf(BY_NAME.get("a"))).as("a preview pushes nothing").isEmpty();
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-042 apply pushes each connected instance only the keys its allow list names; results"
          + " per instance: applied, partial (server rejection: never pushed; agent rejection:"
          + " invalid_value), pending for the instance without a session; then that instance's"
          + " next ServerHello.config carries the values and its result becomes applied")
  void applyAndPending() throws Exception {
    FakeAgent a = BY_NAME.get("a");
    FakeAgent b = BY_NAME.get("b");
    FakeAgent c = BY_NAME.get("c");
    FakeAgent d = BY_NAME.get("d");
    ApiResponse applied = admin.post("/api/v1/admin/config/changes/" + first + "/apply", "{}");
    assertThat(applied.status()).as(applied.toString()).isEqualTo(202);
    JsonNode done = awaitChange(first, "applied");

    JsonNode ra = resultOf(done, a.instanceId());
    assertThat(ra.path("status").asString()).isEqualTo("applied");
    assertThat(ra.path("applied_keys")).hasSize(2);
    assertThat(ra.path("effective_config_hash").asString()).hasSize(16);
    JsonNode rb = resultOf(done, b.instanceId());
    assertThat(rb.path("status").asString()).isEqualTo("partial");
    assertThat(rb.path("applied_keys").get(0).asString()).isEqualTo("log.level");
    assertThat(rb.path("rejected_keys").get(0).asString()).isEqualTo(RATE);
    assertThat(rb.path("rejected").get(0).path("by").asString()).isEqualTo("server");
    JsonNode rc = resultOf(done, c.instanceId());
    assertThat(rc.path("status").asString()).isEqualTo("partial");
    assertThat(rc.path("rejected").get(0).path("code").asString()).isEqualTo("invalid_value");
    assertThat(rc.path("rejected").get(0).path("by").asString()).isEqualTo("agent");
    JsonNode rd = resultOf(done, d.instanceId());
    assertThat(rd.path("status").asString()).isEqualTo("pending");
    assertThat(rd.path("pushed_at").isNull()).isTrue();

    assertThat(pushesOf(a)).hasSize(1);
    assertThat(pushesOf(a).get(0).getValuesMap())
        .containsEntry("log.level", "DEBUG")
        .containsEntry(RATE, "0.5");
    assertThat(pushesOf(a).get(0).getRevert()).isFalse();
    assertThat(pushesOf(b)).hasSize(1);
    assertThat(pushesOf(b).get(0).getValuesMap())
        .as("never a key outside b's allow list")
        .containsOnlyKeys("log.level");

    FakeAgent again =
        agent("d", "aaaaaaaaaaaaaaaa", ConfigReplies.applyAll(), "sampling.**", "log.level");
    HelloResult hello = again.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    assertThat(hello.hello().getConfigMap())
        .containsEntry("log.level", "DEBUG")
        .containsEntry(RATE, "0.5")
        .hasSize(2);
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              JsonNode change = admin.get("/api/v1/admin/config/changes/" + first).json();
              JsonNode r = resultOf(change.path("data"), d.instanceId());
              assertThat(r.path("status").asString()).isEqualTo("applied");
            });

    try (Connection conn = env.connect(PgAccount.APP);
        PreparedStatement st =
            conn.prepareStatement(
                "SELECT count(*) FROM argos.events WHERE app_id = ? AND type = 'config'")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-042 the app's configuration: server-held values, keys every instance allows, each"
          + " instance's list and the drift (hash other than the majority, not_allowed, rejected);"
          + " the effective configuration of an instance (operator) says which values it runs")
  void configAndDrift() {
    FakeAgent b = BY_NAME.get("b");
    FakeAgent c = BY_NAME.get("c");
    ApiResponse r = admin.get("/api/v1/admin/apps/" + appId + "/config");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    assertThat(data.path("current").path("log.level").asString()).isEqualTo("DEBUG");
    assertThat(data.path("current").path(RATE).asString()).isEqualTo("0.5");
    List<String> common = new ArrayList<>();
    data.path("remote_allowed_keys").forEach(k -> common.add(k.asString()));
    assertThat(common).containsExactly("log.level");
    assertThat(data.path("remote_allowed_by_instance")).hasSize(4);
    List<String> drift = new ArrayList<>();
    for (JsonNode dr : data.path("drift")) {
      drift.add(
          dr.path("instance_id").asInt()
              + "/"
              + (dr.path("key").isNull() ? "-" : dr.path("key").asString())
              + "/"
              + dr.path("reason").asString());
    }
    assertThat(drift)
        .containsExactlyInAnyOrder(
            c.instanceId() + "/-/config_hash",
            b.instanceId() + "/" + RATE + "/not_allowed",
            c.instanceId() + "/" + RATE + "/rejected");

    assertThat(operator.get("/api/v1/admin/apps/" + appId + "/config").status()).isEqualTo(403);
    ApiResponse effective =
        operator.get("/api/v1/instances/" + c.instanceId() + "/effective-config");
    assertThat(effective.status()).as(effective.toString()).isEqualTo(200);
    Map<String, JsonNode> byKey = new HashMap<>();
    effective.json().path("data").forEach(e -> byKey.put(e.path("key").asString(), e));
    assertThat(byKey.get("log.level").path("value").asString()).isEqualTo("DEBUG");
    assertThat(byKey.get("log.level").path("source").asString()).isEqualTo("server");
    assertThat(byKey.get("log.level").path("server_status").asString()).isEqualTo("applied");
    assertThat(byKey.get(RATE).path("value").isNull()).isTrue();
    assertThat(byKey.get(RATE).path("server_status").asString()).isEqualTo("rejected");
    assertThat(byKey.get("capture.sql-parameters").path("remote_allowed").asBoolean()).isFalse();
    assertThat(byKey.get("capture.sql-parameters").path("source").isNull()).isTrue();
    assertThat(byKey).doesNotContainKey("server.address");
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-042 revert: a second change (log.level INFO) is reverted in one call: a new applied"
          + " change with revert_of and ConfigPush.revert = true carrying the previous value;"
          + " a preview is not revertable (409); the history lists the changes newest first;"
          + " every step audited with the change id")
  void revertAndAudit() {
    FakeAgent a = BY_NAME.get("a");
    ApiResponse created =
        admin.post(
            changesPath(),
            "{\"reason\":\"back to normal\",\"changes\":{\"log.level\":\"INFO\"},\"targets\":"
                + "{\"instance_ids\":["
                + a.instanceId()
                + "]}}");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    UUID second = UUID.fromString(created.json().path("data").path("change_id").asString());
    ApiResponse previewRevert =
        admin.post("/api/v1/admin/config/changes/" + second + "/revert", "{\"reason\":\"x\"}");
    assertThat(previewRevert.status()).as(previewRevert.toString()).isEqualTo(409);
    assertThat(admin.post("/api/v1/admin/config/changes/" + second + "/apply", "{}").status())
        .isEqualTo(202);
    awaitChange(second, "applied");
    assertThat(admin.post("/api/v1/admin/config/changes/" + second + "/apply", "{}").status())
        .as("applied twice")
        .isEqualTo(409);

    ApiResponse noReason = admin.post("/api/v1/admin/config/changes/" + second + "/revert", "{}");
    assertThat(noReason.status()).isEqualTo(400);
    ApiResponse reverted =
        admin.post(
            "/api/v1/admin/config/changes/" + second + "/revert",
            "{\"reason\":\"INFO hid the errors\"}");
    assertThat(reverted.status()).as(reverted.toString()).isEqualTo(201);
    JsonNode rv = reverted.json().path("data");
    UUID third = UUID.fromString(rv.path("change_id").asString());
    assertThat(rv.path("revert_of").asString()).isEqualTo(second.toString());
    assertThat(rv.path("preview").path("diff").get(0).path("before").asString()).isEqualTo("INFO");
    assertThat(rv.path("preview").path("diff").get(0).path("after").asString()).isEqualTo("DEBUG");
    JsonNode done = awaitChange(third, "applied");
    assertThat(resultOf(done, a.instanceId()).path("status").asString()).isEqualTo("applied");
    List<ConfigPush> pushes = pushesOf(a);
    ConfigPush last = pushes.get(pushes.size() - 1);
    assertThat(last.getChangeId()).isEqualTo(third.toString());
    assertThat(last.getRevert()).isTrue();
    assertThat(last.getValuesMap()).containsOnly(Map.entry("log.level", "DEBUG"));

    ApiResponse list = admin.get(changesPath() + "?limit=2");
    assertThat(list.status()).as(list.toString()).isEqualTo(200);
    assertThat(list.json().path("data")).hasSize(2);
    assertThat(list.json().path("data").get(0).path("change_id").asString())
        .isEqualTo(third.toString());
    String cursor = list.json().path("meta").path("next_cursor").asString();
    ApiResponse page2 = admin.get(changesPath() + "?limit=2&cursor=" + cursor);
    assertThat(page2.status()).as(page2.toString()).isEqualTo(200);
    assertThat(page2.json().path("data")).hasSize(1);
    assertThat(page2.json().path("data").get(0).path("change_id").asString())
        .isEqualTo(first.toString());

    Map<String, List<String>> actions = new HashMap<>();
    for (AuditLog.Row row : AuditLog.all(env)) {
      if (row.targetId() != null) {
        actions.computeIfAbsent(row.targetId(), k -> new ArrayList<>()).add(row.action());
      }
    }
    assertThat(actions.get(first.toString()))
        .containsSubsequence("CONFIG_CHANGE_CREATED", "CONFIG_PUSH", "CONFIG_CHANGE_APPLIED");
    assertThat(actions.get(third.toString()))
        .contains("CONFIG_CHANGE_CREATED", "CONFIG_ROLLBACK", "CONFIG_PUSH");
    for (AuditLog.Row row : AuditLog.rows(env, "CONFIG_CHANGE_CREATED")) {
      if (first.toString().equals(row.targetId())) {
        assertThat(row.after()).contains("Investigate slow checkout", "log.level", "DEBUG");
      }
    }
  }
}
