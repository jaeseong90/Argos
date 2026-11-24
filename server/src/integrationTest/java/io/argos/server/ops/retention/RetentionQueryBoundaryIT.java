/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentTokens;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-ST-040g on a real server with PostgreSQL and ClickHouse (ADR-0010 C-238 (3), WO-39): after
 * {@code PUT /admin/settings/retention} shortens the xlog retention, the query API uses the new raw
 * retention boundary at once, without a restart: a range that was inside the old retention is
 * {@code meta.retention_exceeded} for the XLog and the dashboard, whose source leaves the raw
 * table.
 */
@Tag("integration")
@Tag("req:N-08")
@Tag("req:F-S23")
class RetentionQueryBoundaryIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient admin;
  private static int appId;

  @BeforeAll
  static void start() throws Exception {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of());
    TestUsers.TestUser a = new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
    admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, "retention-boundary-it", "prod");
    }
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

  private static JsonNode meta(String path) {
    ApiResponse r = admin.get(path);
    assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
    return r.json().path("meta");
  }

  @Test
  @DisplayName(
      "T-ST-040g PUT /admin/settings/retention xlog 30 -> 3 days: a 5-day-old range of GET"
          + " /apps/{id}/xlog and GET /apps/{id}/dashboard becomes retention_exceeded and leaves"
          + " the raw source at once, without a restart (C-238 (3))")
  void shorterRetentionMovesTheBoundary() {
    Instant from = Instant.now().minus(Duration.ofDays(5)).truncatedTo(ChronoUnit.HOURS);
    Instant to = from.plus(Duration.ofMinutes(10));
    String range = "?from=" + from + "&to=" + to;
    String xlog = "/api/v1/apps/" + appId + "/xlog" + range;
    String dashboard = "/api/v1/apps/" + appId + "/dashboard" + range;

    assertThat(meta(xlog).path("retention_exceeded").asBoolean())
        .as("xlog inside the configured 30 days")
        .isFalse();
    JsonNode before = meta(dashboard);
    assertThat(before.path("retention_exceeded").asBoolean()).isFalse();
    assertThat(before.path("source").asString()).as("dashboard source before").isEqualTo("raw");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("xlog_days", 3);
    body.put("confirm", true);
    ApiResponse put = admin.put("/api/v1/admin/settings/retention", body);
    assertThat(put.status()).as(put.body()).isEqualTo(200);
    assertThat(put.json().path("data").path("values").path("xlog_days").asInt()).isEqualTo(3);

    // No restart and no wait: the next request plans with the stored value.
    assertThat(meta(xlog).path("retention_exceeded").asBoolean())
        .as("xlog outside the new 3 days")
        .isTrue();
    JsonNode after = meta(dashboard);
    assertThat(after.path("retention_exceeded").asBoolean()).isTrue();
    assertThat(after.path("source").asString())
        .as("dashboard source after: a rollup, not the raw table")
        .isNotEqualTo("raw");

    // Back to 30 days: the boundary follows again.
    ApiResponse back = admin.put("/api/v1/admin/settings/retention", Map.of("xlog_days", 30));
    assertThat(back.status()).as(back.body()).isEqualTo(200);
    assertThat(meta(xlog).path("retention_exceeded").asBoolean()).isFalse();
    assertThat(meta(dashboard).path("source").asString()).isEqualTo("raw");
  }
}
