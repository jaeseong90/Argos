/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
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
import java.util.ArrayList;
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
 * T-SV-031 (N-25) on a real server and PostgreSQL with 50 protocol-level fake agents of one app
 * over the TLS gRPC listener (WO-26): one configuration change and one kill switch, each applied
 * through the REST API and pushed to all 50 sessions, with all 50 per-instance results aggregated
 * (the change {@code applied}) within 10 seconds of the apply request. The elapsed times are
 * printed as {@code [T-SV-031]} lines; numbers of a shared laptop are references only, the Gate 1
 * measurement on the target hardware is WO-53's (docs/bench/gate1.md).
 */
@Tag("integration")
@Tag("req:N-25")
@Tag("req:F-S29")
class ConfigPushScaleIT {

  private static final int AGENTS = 50;
  private static final long BUDGET_MS = 10_000;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static final List<ManagedChannel> CHANNELS = new ArrayList<>();
  private static ApiTestClient admin;
  private static int appId;
  private static final List<FakeAgent> FLEET = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.ratelimit.api-per-user-per-second", "100000");
    p.put("argos.ratelimit.api-burst-per-user", "100000");
    server = ArgosTestServer.start(env, p);
    TestUsers.TestUser user =
        new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
    admin = server.client();
    assertThat(admin.login(user.username(), user.password()).status()).isEqualTo(200);
    String appName = "cfg-scale-" + Long.toHexString(System.nanoTime());
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      token = AgentTokens.issue(c, appId);
    }
    for (int i = 0; i < AGENTS; i++) {
      // One connection per agent, as real agents: the collector takes at most
      // GrpcCollectorServer.MAX_CALLS_PER_CONNECTION (8) calls per connection.
      ManagedChannel channel =
          AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
      CHANNELS.add(channel);
      FakeAgent a =
          FakeAgent.builder(channel)
              .token(token.token())
              .appName(appName)
              .instanceName(appName + "-" + i + ":8080")
              .hello(
                  h ->
                      h.clearRemoteConfigAllow()
                          .addRemoteConfigAllow("sampling.**")
                          .addRemoteConfigAllow("log.level")
                          .addRemoteConfigAllow("argos.enabled"))
              .onConfigPush(ConfigReplies.applyAll())
              .build();
      FLEET.add(a);
      HelloResult hello = a.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
    }
  }

  @AfterAll
  static void stop() {
    FLEET.forEach(FakeAgent::close);
    CHANNELS.forEach(ManagedChannel::shutdownNow);
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  /**
   * Waits until the change is applied with every result {@code applied}; returns the elapsed ms.
   */
  private static long awaitAllApplied(UUID id, long startNanos) {
    long[] elapsed = new long[1];
    Awaitility.await()
        .atMost(BUDGET_MS * 3, TimeUnit.MILLISECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = admin.get("/api/v1/admin/config/changes/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              JsonNode data = r.json().path("data");
              assertThat(data.path("status").asString()).isEqualTo("applied");
              elapsed[0] = (System.nanoTime() - startNanos) / 1_000_000;
              int applied = 0;
              for (JsonNode result : data.path("results")) {
                if ("applied".equals(result.path("status").asString())) {
                  applied++;
                }
              }
              assertThat(applied).isEqualTo(AGENTS);
            });
    return elapsed[0];
  }

  private static long pushesWith(String key, String value) {
    long n = 0;
    for (FakeAgent a : FLEET) {
      for (ServerMessage m : a.serverMessages()) {
        if (m.hasConfigPush()) {
          ConfigPush push = m.getConfigPush();
          if (value.equals(push.getValuesMap().get(key))) {
            n++;
            break;
          }
        }
      }
    }
    return n;
  }

  @Test
  @DisplayName(
      "T-SV-031 a configuration push and a kill switch to 50 instances: all 50 results"
          + " aggregated within 10 s of the apply request (N-25)")
  void fiftyInstances() throws Exception {
    ApiResponse created =
        admin.post(
            "/api/v1/admin/apps/" + appId + "/config/changes",
            "{\"reason\":\"T-SV-031 scale\",\"changes\":{\"log.level\":\"DEBUG\","
                + "\"sampling.profile.normal-rate\":0.2}}");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    UUID change = UUID.fromString(created.json().path("data").path("change_id").asString());
    long start = System.nanoTime();
    ApiResponse applied = admin.post("/api/v1/admin/config/changes/" + change + "/apply", "{}");
    assertThat(applied.status()).as(applied.toString()).isEqualTo(202);
    long configMs = awaitAllApplied(change, start);
    System.out.println(
        "[T-SV-031] config push to "
            + AGENTS
            + " instances, results aggregated in "
            + configMs
            + " ms (budget "
            + BUDGET_MS
            + " ms; shared-laptop reference)");
    assertThat(pushesWith("log.level", "DEBUG")).isEqualTo(AGENTS);

    long killStart = System.nanoTime();
    ApiResponse kill =
        admin.post(
            "/api/v1/admin/apps/" + appId + "/kill-switch",
            "{\"enabled\":false,\"reason\":\"T-SV-031 kill switch\"}");
    assertThat(kill.status()).as(kill.toString()).isEqualTo(202);
    JsonNode k = kill.json().path("data");
    assertThat(k.path("kind").asString()).isEqualTo("kill_switch");
    UUID killId = UUID.fromString(k.path("change_id").asString());
    long killMs = awaitAllApplied(killId, killStart);
    System.out.println(
        "[T-SV-031] kill switch to "
            + AGENTS
            + " instances, results aggregated in "
            + killMs
            + " ms (budget "
            + BUDGET_MS
            + " ms; shared-laptop reference)");
    assertThat(pushesWith("enabled", "false")).isEqualTo(AGENTS);

    assertThat(configMs).as("config push aggregated within 10 s").isLessThanOrEqualTo(BUDGET_MS);
    assertThat(killMs).as("kill switch aggregated within 10 s").isLessThanOrEqualTo(BUDGET_MS);

    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT count(*) FROM argos.events WHERE app_id = ? AND type = 'kill_switch'")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }
}
