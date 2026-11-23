/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code kinds} of {@code GET /apps/{appId}} (ADR-0010 C-205 (4), C-191 (2); docs/15 §3.2 "배치가
 * 하나도 없는 앱은 비활성") on real PostgreSQL and ClickHouse: the kinds an application recorded in the last
 * 7 days, read from the hour rollup that the materialized views fill from {@code xlog} rows,
 * sorted; an older kind is not listed, an application without transactions has none, and the
 * application list leaves the field null (not computed there).
 */
@Tag("req:D-20")
@Tag("req:F-U02")
class AppKindsIT {

  private static final int APP_ALL = 920;
  private static final int APP_HTTP = 921;
  private static final int APP_NONE = 922;
  private static final long SERVICE = 0x7E00_0000_0000_0001L;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient viewer;

  @BeforeAll
  static void start() throws SQLException {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of());
    app(APP_ALL, "wo33-kinds-all");
    app(APP_HTTP, "wo33-kinds-http");
    app(APP_NONE, "wo33-kinds-none");
    Instant now = Instant.now();
    List<XlogRow> rows = new ArrayList<>();
    // APP_ALL: requests now, a batch job 3 days ago and a message 6 days ago.
    rows.add(XlogRow.at(APP_ALL, 9201, now.minusSeconds(30)).service(SERVICE));
    rows.add(
        XlogRow.at(APP_ALL, 9201, now.minus(Duration.ofDays(3))).kind("BATCH").service(SERVICE));
    rows.add(
        XlogRow.at(APP_ALL, 9201, now.minus(Duration.ofDays(6))).kind("MESSAGE").service(SERVICE));
    // APP_HTTP: requests now; its only batch job ran 9 days ago (outside the 7 days).
    rows.add(XlogRow.at(APP_HTTP, 9211, now.minus(Duration.ofHours(2))).service(SERVICE));
    rows.add(
        XlogRow.at(APP_HTTP, 9211, now.minus(Duration.ofDays(9))).kind("BATCH").service(SERVICE));
    HandMadeRows.of(env).xlog(rows);
    TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
    viewer = server.client();
    ApiResponse login = viewer.login(user.username(), user.password());
    assertThat(login.status()).as("login " + login.body()).isEqualTo(200);
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

  private static void app(int id, String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.apps (id, name, env, auto_register) VALUES (?, ?, 'prod', TRUE)"
                    + " ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  private static List<String> kinds(int appId) {
    ApiResponse r = viewer.get("/api/v1/apps/" + appId);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode kinds = r.json().path("data").path("kinds");
    assertThat(kinds.isArray()).as("kinds of app %d: %s", appId, r.body()).isTrue();
    List<String> out = new ArrayList<>();
    kinds.forEach(k -> out.add(k.asString()));
    return out;
  }

  @Test
  @DisplayName("GET /apps/{appId} lists the kinds of the last 7 days, sorted (C-205 (4))")
  void kindsOfTheLastSevenDays() {
    assertThat(kinds(APP_ALL)).containsExactly("BATCH", "HTTP", "MESSAGE");
    // The batch job of 9 days ago is outside the window: the batch filter is disabled for it.
    assertThat(kinds(APP_HTTP)).containsExactly("HTTP");
    assertThat(kinds(APP_NONE)).isEmpty();
  }

  @Test
  @DisplayName("the application list leaves kinds null (only GET /apps/{appId} reads ClickHouse)")
  void listLeavesKindsNull() {
    ApiResponse r = viewer.get("/api/v1/apps?q=wo33-kinds");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    assertThat(data.size()).isEqualTo(3);
    data.forEach(app -> assertThat(app.has("kinds") && app.get("kinds").isNull()).isTrue());
  }
}
