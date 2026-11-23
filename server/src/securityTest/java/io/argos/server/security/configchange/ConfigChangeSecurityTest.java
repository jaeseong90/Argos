/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.configchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.auth.Role;
import io.argos.server.security.SecuritySuiteAccess;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-SEC-23 server side (WO-26) on a real server with real login sessions, PostgreSQL and fake
 * agents over the TLS gRPC listener: the remote configuration hard cap.
 *
 * <ul>
 *   <li>a key outside an instance's {@code remote_config_allow} is never pushed to it (neither in a
 *       {@code ConfigPush} nor in its next {@code ServerHello.config}) and is recorded as rejected
 *       by the server;
 *   <li>a key the agent itself refuses (its own re-check) is stored in {@code rejected_keys} with
 *       the agent's code and audited in {@code CONFIG_CHANGE_APPLIED};
 *   <li>allowed keys are applied;
 *   <li>immutable keys ({@code server.*}, {@code remote-config.*}, {@code log.dir}) are refused
 *       before any push;
 *   <li>configuration changes and the kill switch are for administrators: viewers and operators get
 *       403 on every operation, and nothing reaches an agent.
 * </ul>
 *
 * The agent's local value staying unchanged after a rejection (WO-17) is agent-testing
 * RemoteConfigE2eTest (FakeCollector) and, behind this server, ui/e2e/specs/agent-commands.spec.ts
 * T-SEC-23 (stack).
 */
@Tag("req:SEC-23")
@Tag("req:F-S29")
class ConfigChangeSecurityTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String CAPTURE = "capture.sql-parameters";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static final Map<Role, ApiTestClient> CLIENTS = new EnumMap<>(Role.class);
  private static String appName;
  private static int appId;
  private static AgentTokens.IssuedToken token;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    env = SecuritySuiteAccess.environment();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.ratelimit.login-per-ip-per-minute", "100000");
    p.put("argos.ratelimit.api-per-user-per-second", "100000");
    p.put("argos.ratelimit.api-burst-per-user", "100000");
    server = ArgosTestServer.start(env, p);
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR, Role.ADMIN)) {
      TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
      ApiTestClient client = server.client();
      ApiResponse r = client.login(user.username(), user.password());
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      CLIENTS.put(role, client);
    }
    appName = "cfg-sec-" + Long.toHexString(System.nanoTime());
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
  }

  private static FakeAgent agent(String name, Map<String, RejectCode> rejects, String... allow) {
    FakeAgent a =
        FakeAgent.builder(channel)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-" + name + ":8080")
            .hello(
                h -> {
                  h.clearRemoteConfigAllow();
                  for (String pattern : allow) {
                    h.addRemoteConfigAllow(pattern);
                  }
                  return h;
                })
            .onConfigPush(ConfigReplies.rejecting(rejects))
            .build();
    AGENTS.add(a);
    HelloResult hello = a.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return a;
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

  private static JsonNode awaitApplied(ApiTestClient admin, UUID id) {
    JsonNode[] last = new JsonNode[1];
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = admin.get("/api/v1/admin/config/changes/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              last[0] = r.json().path("data");
              assertThat(last[0].path("status").asString()).isEqualTo("applied");
            });
    return last[0];
  }

  private static JsonNode resultOf(JsonNode change, int instanceId) {
    for (JsonNode r : change.path("results")) {
      if (r.path("instance_id").asInt() == instanceId) {
        return r;
      }
    }
    throw new AssertionError("no result of instance " + instanceId);
  }

  @Test
  @DisplayName(
      "T-SEC-23 a key outside an instance's remote_config_allow is never pushed to it (push nor"
          + " ServerHello.config) and is recorded rejected by the server; a key the agent refuses"
          + " is stored in rejected_keys with its code and audited; allowed keys are applied;"
          + " immutable keys are refused before any push")
  void hardCap() throws Exception {
    ApiTestClient admin = CLIENTS.get(Role.ADMIN);
    FakeAgent narrow = agent("narrow", Map.of(), "log.level");
    FakeAgent wide =
        agent("wide", Map.of(CAPTURE, RejectCode.FORBIDDEN_PATTERN), "log.level", CAPTURE);

    for (String immutable :
        List.of("server.address", "server.token-file", "remote-config.allow", "log.dir")) {
      ApiResponse refused =
          admin.post(
              "/api/v1/admin/apps/" + appId + "/config/changes",
              "{\"reason\":\"r\",\"changes\":{\"" + immutable + "\":\"x\"}}");
      assertThat(refused.status()).as(immutable + " " + refused).isEqualTo(400);
    }

    ApiResponse created =
        admin.post(
            "/api/v1/admin/apps/" + appId + "/config/changes",
            "{\"reason\":\"T-SEC-23\",\"changes\":{\"log.level\":\"WARN\",\""
                + CAPTURE
                + "\":\"full\"},\"targets\":{\"instance_ids\":["
                + narrow.instanceId()
                + ","
                + wide.instanceId()
                + "]}}");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode preview = created.json().path("data").path("preview");
    assertThat(preview.path("rejected_keys_by_instance")).hasSize(1);
    assertThat(preview.path("rejected_keys_by_instance").get(0).path("instance_id").asInt())
        .isEqualTo(narrow.instanceId());
    UUID id = UUID.fromString(created.json().path("data").path("change_id").asString());
    assertThat(admin.post("/api/v1/admin/config/changes/" + id + "/apply", "{}").status())
        .isEqualTo(202);
    JsonNode done = awaitApplied(admin, id);

    for (ConfigPush push : pushesOf(narrow)) {
      assertThat(push.getValuesMap())
          .as("never pushed outside the allow list")
          .doesNotContainKey(CAPTURE);
    }
    assertThat(pushesOf(narrow)).hasSize(1);
    assertThat(pushesOf(wide).get(0).getValuesMap()).containsKeys("log.level", CAPTURE);

    JsonNode n = resultOf(done, narrow.instanceId());
    assertThat(n.path("applied_keys").get(0).asString()).isEqualTo("log.level");
    assertThat(n.path("rejected_keys").get(0).asString()).isEqualTo(CAPTURE);
    assertThat(n.path("rejected").get(0).path("by").asString()).isEqualTo("server");
    JsonNode w = resultOf(done, wide.instanceId());
    assertThat(w.path("applied_keys").get(0).asString()).isEqualTo("log.level");
    assertThat(w.path("rejected_keys").get(0).asString()).isEqualTo(CAPTURE);
    assertThat(w.path("rejected").get(0).path("code").asString()).isEqualTo("forbidden_pattern");
    assertThat(w.path("rejected").get(0).path("by").asString()).isEqualTo("agent");

    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT instance_id, rejected_keys, applied_keys FROM"
                    + " argos.config_change_results WHERE change_id = ? ORDER BY instance_id")) {
      st.setObject(1, id);
      try (ResultSet rs = st.executeQuery()) {
        int rows = 0;
        while (rs.next()) {
          rows++;
          assertThat((Object[]) rs.getArray(2).getArray()).containsExactly(CAPTURE);
          assertThat((Object[]) rs.getArray(3).getArray()).containsExactly("log.level");
        }
        assertThat(rows).isEqualTo(2);
      }
    }
    List<AuditLog.Row> applied = new ArrayList<>();
    for (AuditLog.Row row : AuditLog.rows(env, "CONFIG_CHANGE_APPLIED")) {
      if (id.toString().equals(row.targetId())) {
        applied.add(row);
      }
    }
    assertThat(applied).hasSize(1);
    assertThat(applied.get(0).after())
        .contains("\"" + narrow.instanceId() + "\"", "\"" + wide.instanceId() + "\"", CAPTURE);
    assertThat(
            AuditLog.rows(env, "CONFIG_PUSH").stream()
                .anyMatch(r -> id.toString().equals(r.targetId())))
        .isTrue();

    FakeAgent narrowAgain = agent("narrow", Map.of(), "log.level");
    assertThat(narrowAgain.serverHello().orElseThrow().getConfigMap())
        .as("the next Hello holds no key outside the allow list")
        .containsOnly(Map.entry("log.level", "WARN"));
  }

  @Test
  @DisplayName(
      "T-SEC-23 configuration changes, the app configuration and the kill switch are for"
          + " administrators: viewers and operators get 403 and nothing reaches an agent")
  void administratorsOnly() {
    FakeAgent target = agent("roles", Map.of(), "log.level", "enabled");
    ApiResponse created =
        CLIENTS
            .get(Role.ADMIN)
            .post(
                "/api/v1/admin/apps/" + appId + "/config/changes",
                "{\"reason\":\"roles\",\"changes\":{\"log.level\":\"ERROR\"},\"targets\":"
                    + "{\"instance_ids\":["
                    + target.instanceId()
                    + "]}}");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String id = created.json().path("data").path("change_id").asString();
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      ApiTestClient c = CLIENTS.get(role);
      assertThat(c.get("/api/v1/admin/apps/" + appId + "/config").status())
          .as(role + " config")
          .isEqualTo(403);
      assertThat(
              c.post(
                      "/api/v1/admin/apps/" + appId + "/config/changes",
                      "{\"reason\":\"r\",\"changes\":{\"log.level\":\"DEBUG\"}}")
                  .status())
          .as(role + " propose")
          .isEqualTo(403);
      assertThat(c.post("/api/v1/admin/config/changes/" + id + "/apply", "{}").status())
          .as(role + " apply")
          .isEqualTo(403);
      assertThat(
              c.post("/api/v1/admin/config/changes/" + id + "/revert", "{\"reason\":\"r\"}")
                  .status())
          .as(role + " revert")
          .isEqualTo(403);
      assertThat(
              c.post(
                      "/api/v1/admin/apps/" + appId + "/kill-switch",
                      "{\"enabled\":false,\"reason\":\"r\"}")
                  .status())
          .as(role + " kill switch")
          .isEqualTo(403);
    }
    assertThat(
            CLIENTS
                .get(Role.VIEWER)
                .get("/api/v1/instances/" + target.instanceId() + "/effective-config")
                .status())
        .as("viewer effective config")
        .isEqualTo(403);
    assertThat(
            CLIENTS
                .get(Role.OPERATOR)
                .get("/api/v1/instances/" + target.instanceId() + "/effective-config")
                .status())
        .as("operator effective config")
        .isEqualTo(200);
    assertThat(pushesOf(target)).as("nothing reached the agent").isEmpty();
  }
}
