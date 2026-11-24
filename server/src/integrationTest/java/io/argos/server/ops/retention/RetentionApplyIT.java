/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.cli.AdminCli;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retention changes on real ClickHouse and PostgreSQL (T-ST-040 TTL and retention rehearsal, N-08;
 * 13 §8, 17 §9.1, 14 §4.14; ADR-0010 C-99, C-112; the {@code argos-admin retention} commands,
 * T-SV-053d to f):
 *
 * <ul>
 *   <li>the start applies {@code argos.retention.stat} != {@code metric} (key split): {@code
 *       sql_stat_raw} and {@code ext_stat_raw} get the stat TTL, {@code metric} its own, recorded
 *       as {@code RETENTION_CHANGED} by the system;
 *   <li>an extension through the API changes the TTL without rewriting a part ({@code system.parts}
 *       names and modification times unchanged); a shortening needs {@code confirm: true}, then
 *       drops the partitions outside the new retention and keeps the others;
 *   <li>{@code retention show --json} and {@code retention apply --json} report and apply the
 *       effective retention (API values win over the configuration), with exit codes and audit.
 * </ul>
 */
@Tag("integration")
@Tag("req:N-08")
@Tag("req:F-S23")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionApplyIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter ID = DateTimeFormatter.BASIC_ISO_DATE;
  private static final int APP = 950;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp ch;
  private static ClickHouseHttp reader;
  private static ApiTestClient admin;
  private static Map<String, Object> properties;
  private static LocalDate today;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> retention = new LinkedHashMap<>();
    retention.put("argos.retention.stat", "21d");
    retention.put("argos.retention.metric", "14d");
    server = ArgosTestServer.start(env, retention);
    properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    properties.putAll(retention);
    ch = env.clickHouse(Account.MIGRATOR);
    // system.parts is granted to the query account only (13 §7.3).
    reader = env.clickHouse(Account.READER);
    today = LocalDate.now(ZoneOffset.UTC);
    // One INSERT: one part per day partition, so no background merge changes the parts.
    List<XlogRow> tx = new ArrayList<>();
    List<StepRow> steps = new ArrayList<>();
    for (int daysAgo : new int[] {1, 5, 10, 20}) {
      XlogRow r =
          XlogRow.at(APP, 9501, today.minusDays(daysAgo).atTime(8, 0).toInstant(ZoneOffset.UTC));
      tx.add(r);
      if (daysAgo <= 5) {
        steps.add(StepRow.of(r, 1, "SQL"));
      }
    }
    HandMadeRows.of(env).xlog(tx);
    HandMadeRows.of(env).steps(steps);
    TestUsers.TestUser a = new TestUsers(env).create(Role.ADMIN);
    admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
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

  private static int ttl(String table) {
    String engine =
        ch.scalar(
            "SELECT engine_full FROM system.tables WHERE database = 'argos' AND name = '"
                + table
                + "'");
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("toIntervalDay\\((\\d+)\\)").matcher(engine);
    assertThat(m.find()).as(engine).isTrue();
    return Integer.parseInt(m.group(1));
  }

  private static String parts(String table) {
    return reader.scalar(
        "SELECT arrayStringConcat(groupArray(p), ',') FROM (SELECT concat(name, '@',"
            + " toString(modification_time)) AS p FROM system.parts WHERE database = 'argos' AND"
            + " table = '"
            + table
            + "' AND active ORDER BY name)");
  }

  private static List<String> partitions(String table) {
    String v =
        reader.scalar(
            "SELECT arrayStringConcat(groupArray(partition_id), ',') FROM (SELECT DISTINCT"
                + " partition_id FROM system.parts WHERE database = 'argos' AND table = '"
                + table
                + "' AND active ORDER BY partition_id)");
    return v.isBlank() ? List.of() : List.of(v.strip().split(",", -1));
  }

  private static String id(int daysAgo) {
    return ID.format(today.minusDays(daysAgo));
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-ST-040d/C-112 the start gives sql_stat_raw and ext_stat_raw the stat retention (21d)"
          + " and metric its own (14d): stat != metric is accepted; RETENTION_CHANGED by the"
          + " system")
  void startAppliesSplitKeys() {
    assertThat(ttl("sql_stat_raw")).isEqualTo(21);
    assertThat(ttl("ext_stat_raw")).isEqualTo(21);
    assertThat(ttl("metric")).isEqualTo(14);
    assertThat(ttl("xlog")).isEqualTo(30);
    List<AuditLog.Row> rows = AuditLog.rows(env, "RETENTION_CHANGED");
    assertThat(rows).hasSize(1);
    AuditLog.Row r = rows.get(0);
    assertThat(r.actorType()).isEqualTo("system");
    assertThat(r.targetType()).isEqualTo("retention");
    JsonNode before = JSON.readTree(r.before());
    JsonNode after = JSON.readTree(r.after());
    assertThat(before.path("ttl_sql_stat_raw").asInt()).isEqualTo(14);
    assertThat(after.path("ttl_sql_stat_raw").asInt()).isEqualTo(21);
    assertThat(after.path("ttl_ext_stat_raw").asInt()).isEqualTo(21);
    assertThat(after.has("ttl_metric")).isFalse();
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-ST-040e an extension (PUT xlog_days 45) changes the TTL with MODIFY TTL and rewrites no"
          + " part: system.parts names and modification times stay; GET shows the API value as"
          + " source settings")
  void extensionRewritesNothing() {
    String before = parts("xlog");
    assertThat(before).isNotBlank();
    ApiResponse r = admin.put("/api/v1/admin/settings/retention", Map.of("xlog_days", 45));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    assertThat(data.path("applied").asBoolean()).isTrue();
    assertThat(data.path("values").path("xlog_days").asInt()).isEqualTo(45);
    assertThat(data.path("sources").path("xlog_days").asString()).isEqualTo("settings");
    assertThat(data.path("changes").toString()).contains("\"table\":\"xlog\"");
    assertThat(data.path("queued_drops").asInt()).isZero();
    assertThat(ttl("xlog")).isEqualTo(45);
    assertThat(parts("xlog"))
        .as("no part rewritten (materialize_ttl_after_modify=0)")
        .isEqualTo(before);

    ApiResponse get = admin.get("/api/v1/admin/settings/retention");
    assertThat(get.status()).as(get.body()).isEqualTo(200);
    JsonNode view = get.json().path("data");
    assertThat(view.path("values").path("xlog_days").asInt()).isEqualTo(45);
    assertThat(view.path("config").path("xlog_days").asInt()).isEqualTo(30);
    assertThat(view.path("values").path("stat_raw_days").asInt()).isEqualTo(21);
    assertThat(view.path("values").path("metric_raw_days").asInt()).isEqualTo(14);
    JsonNode xlog = null;
    for (JsonNode t : view.path("tables")) {
      if (t.path("table").asString().equals("xlog")) {
        xlog = t;
      }
    }
    assertThat(xlog).as(view.toString()).isNotNull();
    assertThat(xlog.path("current_days").asInt()).isEqualTo(45);
    assertThat(xlog.path("oldest_partition").asString()).isEqualTo(id(20));
    AuditLog.Row audit = AuditLog.rows(env, "RETENTION_CHANGED").getLast();
    assertThat(audit.actorType()).isEqualTo("user");
    assertThat(JSON.readTree(audit.before()).path("xlog_days").asInt()).isEqualTo(30);
    assertThat(JSON.readTree(audit.after()).path("xlog_days").asInt()).isEqualTo(45);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-ST-040f a shortening without confirm is refused (400 confirm required, nothing stored);"
          + " with confirm the TTL changes and the partitions outside the new retention are"
          + " dropped, the others stay")
  void shortening() {
    ApiResponse refused = admin.put("/api/v1/admin/settings/retention", Map.of("xlog_days", 15));
    assertThat(refused.status()).as(refused.body()).isEqualTo(400);
    assertThat(refused.body()).contains("confirm").contains("xlog_days");
    assertThat(ttl("xlog")).isEqualTo(45);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("xlog_days", 15);
    body.put("confirm", true);
    ApiResponse r = admin.put("/api/v1/admin/settings/retention", body);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    assertThat(data.path("queued_drops").asInt()).isEqualTo(1);
    assertThat(ttl("xlog")).isEqualTo(15);
    // The scheduled run drops 10 a minute; drain now instead of waiting for it.
    server.bean(RetentionService.class).drainDrops(100);
    assertThat(partitions("xlog")).containsExactly(id(10), id(5), id(1));
    assertThat(
            ch.scalar("SELECT toString(min(toDate(ts))) FROM argos.xlog WHERE app_id = " + APP)
                .strip())
        .isEqualTo(today.minusDays(10).toString());
    assertThat(server.bean(RetentionService.class).pendingDrops()).isZero();
  }

  /** Output of one command run. */
  private record Run(int exitCode, String out, String err) {}

  private static Run admin(Map<String, Object> extra, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, Object> p = new LinkedHashMap<>(properties);
    p.putAll(extra);
    int code =
        AdminCli.run(
            args,
            p,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    IntegrationLogs.restoreServerLogging();
    Run run =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    for (PgAccount a : PgAccount.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    for (Account a : Account.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    return run;
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-053d argos-admin retention show --json: effective values with sources (the API value"
          + " wins over the configuration), each table's TTL, oldest partition and size; no audit")
  void cliShow() {
    int audits = AuditLog.rows(env, "RETENTION_CHANGED").size();
    Run run = admin(Map.of(), "retention", "show", "--json");
    assertThat(run.exitCode()).as(run.err()).isEqualTo(AdminCli.EXIT_OK);
    JsonNode root = JSON.readTree(run.out());
    assertThat(root.path("command").asString()).isEqualTo("retention show");
    assertThat(root.path("values").path("xlog_days").asInt()).isEqualTo(15);
    assertThat(root.path("sources").path("xlog_days").asString()).isEqualTo("settings");
    assertThat(root.path("values").path("profile_days").asInt()).isEqualTo(7);
    assertThat(root.path("sources").path("profile_days").asString()).isEqualTo("config");
    JsonNode xlog = null;
    List<String> tables = new ArrayList<>();
    for (JsonNode t : root.path("tables")) {
      tables.add(t.path("table").asString());
      if (t.path("table").asString().equals("xlog")) {
        xlog = t;
      }
    }
    assertThat(tables).contains("xlog", "profile_step", "metric", "app_stat_1m", "app_stat_1h");
    assertThat(xlog).isNotNull();
    assertThat(xlog.path("current_days").asInt()).isEqualTo(15);
    assertThat(xlog.path("oldest_partition").asString()).isEqualTo(id(10));
    assertThat(xlog.path("rows").asLong()).isEqualTo(3);
    assertThat(xlog.path("bytes").asLong()).isPositive();
    assertThat(AuditLog.rows(env, "RETENTION_CHANGED")).hasSize(audits);

    Run table = admin(Map.of(), "retention", "show");
    assertThat(table.exitCode()).isZero();
    assertThat(table.out()).contains("TABLE").contains("xlog");
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SV-053e argos-admin retention apply --json with argos.retention.profile=3d: profile_step"
          + " TTL 7 -> 3, its partition outside dropped at once, RETENTION_CHANGED by argos-admin;"
          + " a second run changes nothing")
  void cliApply() {
    assertThat(partitions("profile_step")).containsExactly(id(5), id(1));
    Map<String, Object> profile = Map.of("argos.retention.profile", "3d");
    Run run = admin(profile, "retention", "apply", "--json");
    assertThat(run.exitCode()).as(run.err()).isEqualTo(AdminCli.EXIT_OK);
    JsonNode root = JSON.readTree(run.out());
    assertThat(root.path("command").asString()).isEqualTo("retention apply");
    assertThat(root.path("changes").size()).as(run.out()).isEqualTo(1);
    assertThat(root.path("changes").get(0).path("table").asString()).isEqualTo("profile_step");
    assertThat(root.path("changes").get(0).path("from_days").asInt()).isEqualTo(7);
    assertThat(root.path("changes").get(0).path("to_days").asInt()).isEqualTo(3);
    assertThat(root.path("dropped").size()).isEqualTo(1);
    assertThat(root.path("dropped").get(0).path("partition").asString()).isEqualTo(id(5));
    assertThat(ttl("profile_step")).isEqualTo(3);
    assertThat(partitions("profile_step")).containsExactly(id(1));
    AuditLog.Row audit = AuditLog.rows(env, "RETENTION_CHANGED").getLast();
    assertThat(audit.actorName()).isEqualTo("argos-admin");
    assertThat(JSON.readTree(audit.after()).path("ttl_profile_step").asInt()).isEqualTo(3);

    Run again = admin(profile, "retention", "apply", "--json");
    assertThat(again.exitCode()).isZero();
    assertThat(JSON.readTree(again.out()).path("changes").size()).isZero();
  }

  @Test
  @Order(6)
  @DisplayName(
      "T-SV-053f argos-admin retention: an unknown option is exit 2 with the usage, a ClickHouse"
          + " that cannot be reached is exit 1 with a JSON error and no secret")
  void cliExitCodes() {
    Run usage = admin(Map.of(), "retention", "show", "--bogus");
    assertThat(usage.exitCode()).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(usage.err()).contains("Usage: argos-admin retention");
    Run down =
        admin(Map.of("argos.clickhouse.url", "http://127.0.0.1:9"), "retention", "show", "--json");
    assertThat(down.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(JSON.readTree(down.out()).path("error").asString()).isNotBlank();
  }
}
