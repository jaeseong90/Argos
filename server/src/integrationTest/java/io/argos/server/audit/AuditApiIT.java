/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/admin/audit} and {@code /admin/audit/export} on the real server (14 §4.14, 16
 * §11.5, SEC-80..83): filters, keyset paging with the 500-row page cap, the CSV export with the 16
 * §9.6 rules and {@code no-store}, the audit of reading the audit log ({@code AUDIT_LOG_VIEWED},
 * {@code AUDIT_LOG_EXPORTED}), the personal data subject, and administrators only (T-SEC-83).
 */
@Tag("integration")
@Tag("req:SEC-80")
@Tag("req:SEC-81")
@Tag("req:SEC-83")
class AuditApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static TestUsers users;
  private static TestUsers.TestUser admin;
  private static ApiTestClient adminClient;
  private static Instant from;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
    users = new TestUsers(env);
    admin = users.create(Role.ADMIN);
    adminClient = server.client();
    assertThat(adminClient.login(admin.username(), admin.password()).status()).isEqualTo(200);
    from = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
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

  private static String range() {
    return "from="
        + from
        + "&to="
        + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
  }

  private static String q(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void seed(String targetType, String marker, int n) {
    AuditService audit = server.bean(AuditService.class);
    for (int i = 0; i < n; i++) {
      audit.record(
          AuditEvent.RULE_UPDATED,
          new Actor(Actor.Type.USER, "seed-actor", "seed-user", "127.0.0.1", "JUnit"),
          new Target(targetType, marker, "rule " + i),
          AuditResult.success(),
          null,
          Map.of("i", i));
    }
  }

  private static List<JsonNode> data(ApiResponse r) {
    List<JsonNode> out = new ArrayList<>();
    r.json().path("data").forEach(out::add);
    return out;
  }

  @Test
  @DisplayName(
      "T-SV-044e filters by target and action, newest first, keyset pages of limit with"
          + " meta.next_cursor; a cursor of other filters is refused")
  void filtersAndCursor() {
    String marker = "it-api-" + UUID.randomUUID();
    seed("it-rule", marker, 7);
    String base = "/api/v1/admin/audit?" + range() + "&target_type=it-rule&target_id=" + q(marker);
    ApiResponse first = adminClient.get(base + "&limit=3");
    assertThat(first.status()).as(first.toString()).isEqualTo(200);
    List<JsonNode> page1 = data(first);
    assertThat(page1).hasSize(3);
    assertThat(page1.get(0).path("target").path("name").asString()).isEqualTo("rule 6");
    assertThat(page1.get(0).path("action").asString()).isEqualTo("RULE_UPDATED");
    assertThat(page1.get(0).path("actor").path("type").asString()).isEqualTo("user");
    assertThat(page1.get(0).path("actor").path("name").asString()).isEqualTo("seed-user");
    assertThat(page1.get(0).path("ip").asString()).isEqualTo("127.0.0.1");
    assertThat(page1.get(0).path("after").path("i").asInt()).isEqualTo(6);
    assertThat(first.json().path("meta").path("truncated").asBoolean()).isTrue();
    String cursor = first.json().path("meta").path("next_cursor").asString();
    assertThat(cursor).isNotBlank();
    List<String> names = new ArrayList<>();
    page1.forEach(n -> names.add(n.path("target").path("name").asString()));
    String next = cursor;
    while (next != null && !next.isEmpty()) {
      ApiResponse r = adminClient.get(base + "&limit=3&cursor=" + q(next));
      assertThat(r.status()).isEqualTo(200);
      data(r).forEach(n -> names.add(n.path("target").path("name").asString()));
      JsonNode c = r.json().path("meta").path("next_cursor");
      next = c.isNull() || c.isMissingNode() ? null : c.asString();
    }
    assertThat(names)
        .containsExactly("rule 6", "rule 5", "rule 4", "rule 3", "rule 2", "rule 1", "rule 0");

    ApiResponse other =
        adminClient.get(
            "/api/v1/admin/audit?" + range() + "&target_type=it-rule&cursor=" + q(cursor));
    assertThat(other.status()).isEqualTo(400);
    assertThat(other.errorCode()).contains("filter_invalid");

    ApiResponse byAction =
        adminClient.get(
            "/api/v1/admin/audit?" + range() + "&action=RULE_UPDATED&target_id=" + q(marker));
    assertThat(data(byAction)).hasSize(7);
    ApiResponse byActor =
        adminClient.get(
            "/api/v1/admin/audit?" + range() + "&actor=seed-actor&target_id=" + q(marker));
    assertThat(data(byActor)).hasSize(7);
    ApiResponse none =
        adminClient.get(
            "/api/v1/admin/audit?" + range() + "&action=RULE_DELETED&target_id=" + q(marker));
    assertThat(data(none)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-044e limit is at most 500 (501: 400 limit_exceeded); 501 matching rows give a full"
          + " page of 500 and a cursor; bad ranges and unknown events are 400")
  void limits() {
    String marker = "it-cap-" + UUID.randomUUID();
    seed("it-cap", marker, 501);
    String base = "/api/v1/admin/audit?" + range() + "&target_type=it-cap&target_id=" + q(marker);
    ApiResponse page = adminClient.get(base + "&limit=500");
    assertThat(page.status()).isEqualTo(200);
    assertThat(data(page)).hasSize(500);
    assertThat(page.json().path("meta").path("next_cursor").asString()).isNotBlank();
    ApiResponse defaults = adminClient.get(base);
    assertThat(data(defaults)).hasSize(100);
    ApiResponse tooMany = adminClient.get(base + "&limit=501");
    assertThat(tooMany.status()).isEqualTo(400);
    assertThat(tooMany.errorCode()).contains("limit_exceeded");
    assertThat(adminClient.get("/api/v1/admin/audit?to=" + Instant.now()).status()).isEqualTo(400);
    ApiResponse longRange =
        adminClient.get(
            "/api/v1/admin/audit?from=2020-01-01T00:00:00Z&to="
                + Instant.now().truncatedTo(ChronoUnit.SECONDS));
    assertThat(longRange.status()).isEqualTo(400);
    ApiResponse unknown =
        adminClient.get("/api/v1/admin/audit?" + range() + "&action=NO_SUCH_EVENT");
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.errorCode()).contains("filter_invalid");
  }

  @Test
  @DisplayName(
      "T-SV-044e reading the audit log is audited: AUDIT_LOG_VIEWED with the filters and the row"
          + " count, actor the administrator")
  void viewIsAudited() {
    String marker = "it-viewed-" + UUID.randomUUID();
    seed("it-viewed", marker, 2);
    ApiResponse r = adminClient.get("/api/v1/admin/audit?" + range() + "&target_id=" + q(marker));
    assertThat(r.status()).isEqualTo(200);
    AuditLog.Row viewed =
        AuditLog.rows(env, "AUDIT_LOG_VIEWED").stream()
            .filter(row -> row.after() != null && row.after().contains(marker))
            .findFirst()
            .orElseThrow();
    assertThat(viewed.actorName()).isEqualTo(admin.username());
    assertThat(viewed.actorId()).isEqualTo(admin.id().toString());
    assertThat(viewed.targetType()).isEqualTo("audit_log");
    JsonNode after = JSON.readTree(viewed.after());
    assertThat(after.path("rows").asInt()).isEqualTo(2);
    assertThat(after.path("filters").path("target_id").asString()).isEqualTo(marker);
  }

  @Test
  @DisplayName(
      "T-SV-044e CSV export: text/csv with BOM, no-store, attachment name, formula prefix,"
          + " AUDIT_LOG_EXPORTED with the row count")
  void export() {
    String marker = "it-csv-" + UUID.randomUUID();
    server
        .bean(AuditService.class)
        .record(
            AuditEvent.CHANNEL_UPDATED,
            new Actor(Actor.Type.USER, "csv-actor", "=HYPERLINK(\"http://x\")", null, null),
            new Target("channel", marker, "+cmd|' /C calc'!A0"),
            AuditResult.success());
    ApiResponse r =
        adminClient.get(
            "/api/v1/admin/audit/export?" + range() + "&target_id=" + q(marker) + "&format=csv");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.header("Content-Type").orElseThrow()).startsWith("text/csv");
    assertThat(r.header("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(r.header("Content-Disposition").orElseThrow())
        .startsWith("attachment; filename=\"argos-audit-")
        .endsWith(".csv\"");
    String body = r.body();
    assertThat(body).startsWith("﻿\"id\",\"ts\",\"actor_type\"");
    String[] lines = body.split("\r\n");
    assertThat(lines).hasSize(2);
    assertThat(lines[1])
        .contains("\"CHANNEL_UPDATED\"")
        .contains("\"'=HYPERLINK(\"\"http://x\"\")\"")
        .contains("\"'+cmd|' /C calc'!A0\"");
    AuditLog.Row exported =
        AuditLog.rows(env, "AUDIT_LOG_EXPORTED").stream()
            .filter(row -> row.after() != null && row.after().contains(marker))
            .findFirst()
            .orElseThrow();
    assertThat(exported.actorName()).isEqualTo(admin.username());
    assertThat(JSON.readTree(exported.after()).path("rows").asInt()).isEqualTo(1);
    assertThat(JSON.readTree(exported.after()).path("format").asString()).isEqualTo("csv");
    ApiResponse json = adminClient.get("/api/v1/admin/audit/export?" + range() + "&format=json");
    assertThat(json.status()).isEqualTo(400);
  }

  @Test
  @DisplayName(
      "T-SEC-81 a record with a personal data subject shows pii_subject (user id, tx id as an"
          + " unsigned decimal string) and stores subject_user_id / subject_tx_id")
  void piiSubject() {
    String marker = "it-pii-" + UUID.randomUUID();
    server
        .bean(AuditService.class)
        .record(
            AuditEvent.SENSITIVE_VIEW,
            new Actor(Actor.Type.USER, admin.id().toString(), admin.username(), null, null),
            new Target("transaction", marker, null),
            AuditResult.success("sql_params_full"),
            null,
            Map.of("type", "sql_params_full"),
            new AuditSubject("kim@example.com", -2L));
    ApiResponse r = adminClient.get("/api/v1/admin/audit?" + range() + "&target_id=" + q(marker));
    JsonNode rec = data(r).get(0);
    assertThat(rec.path("pii_subject").path("user_id").asString()).isEqualTo("kim@example.com");
    assertThat(rec.path("pii_subject").path("tx_id").asString()).isEqualTo("18446744073709551614");
    JsonNode plain =
        data(adminClient.get("/api/v1/admin/audit?" + range() + "&action=AUDIT_LOG_VIEWED&limit=1"))
            .get(0);
    assertThat(plain.path("pii_subject").isNull() || plain.path("pii_subject").isMissingNode())
        .isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-83 viewers and operators get 403 on the audit list and export, anonymous 401; no"
          + " write method exists (405)")
  void adminsOnly() {
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      TestUsers.TestUser u = users.create(role);
      ApiTestClient c = server.client();
      assertThat(c.login(u.username(), u.password()).status()).isEqualTo(200);
      assertThat(c.get("/api/v1/admin/audit?" + range()).status()).as(role.name()).isEqualTo(403);
      assertThat(c.get("/api/v1/admin/audit/export?" + range()).status())
          .as(role.name())
          .isEqualTo(403);
    }
    ApiTestClient anonymous = server.client();
    anonymous.fetchCsrf();
    assertThat(anonymous.get("/api/v1/admin/audit?" + range()).status()).isEqualTo(401);
    ApiResponse delete = adminClient.delete("/api/v1/admin/audit");
    assertThat(delete.status()).isIn(404, 405);
    ApiResponse post = adminClient.post("/api/v1/admin/audit", Map.of("action", "LOGIN_SUCCESS"));
    assertThat(post.status()).isIn(404, 405);
  }
}
