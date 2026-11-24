/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.setup.SetupTokenBootstrap;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.storage.clickhouse.Account;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The WO-28 {@code argos-admin} subcommands of 17 §4.4 on a real PostgreSQL 16 and ClickHouse
 * (T-SV-053g onwards, DSN-17-14): {@code migrate} on an empty database and again (idempotent),
 * {@code config effective|check}, {@code cleanup instances|dictionary|commands}, {@code token
 * issue|revoke|rotate}, {@code setup-token regenerate} (picked up by a running server, refused once
 * an administrator exists), {@code tls reload} (answered by a running server) and {@code version}.
 * Every case parses the {@code --json} document, checks the exit code and the audit row, and that
 * no secret (database passwords, master key, tokens beyond the one printed) appears in the output.
 */
@Tag("integration")
@Tag("req:DSN-17-14")
@Tag("req:SEC-80")
@Tag("req:SEC-47")
@Tag("req:SEC-09")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCliOpsIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ServerTestEnvironment env;
  private static final List<String> SECRETS = new ArrayList<>();

  record Run(int code, String out, String err) {
    JsonNode json() {
      return JSON.readTree(out);
    }
  }

  /** One audit row. */
  record AuditRow(
      String action,
      String actorType,
      String actorName,
      String targetType,
      String targetId,
      String targetName,
      String result,
      String reason,
      String after) {}

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    for (PgAccount a : PgAccount.values()) {
      SECRETS.add(env.password(a));
    }
    for (Account a : Account.values()) {
      SECRETS.add(env.password(a));
    }
    SECRETS.add(env.masterKey().hex());
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  private static Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    int code =
        AdminCli.run(
            args,
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    IntegrationLogs.restoreServerLogging();
    Run r =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    for (String secret : SECRETS) {
      assertThat(r.out() + r.err()).as("no secret in the output").doesNotContain(secret);
    }
    return r;
  }

  /** The newest audit row of {@code action} (and target name, when given). */
  private static AuditRow audit(String action, String targetName) throws Exception {
    List<AuditRow> rows = audits(action, targetName);
    assertThat(rows).as("an audit row " + action + " " + targetName).isNotEmpty();
    return rows.get(0);
  }

  private static List<AuditRow> audits(String action, String targetName) throws Exception {
    String sql =
        "SELECT action, actor_type, actor_name, target_type, target_id, target_name, result,"
            + " reason, after::text FROM argos.audit_logs WHERE action = ?"
            + (targetName == null ? "" : " AND target_name = ?")
            + " ORDER BY ts DESC, id DESC";
    List<AuditRow> rows = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, action);
      if (targetName != null) {
        ps.setString(2, targetName);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new AuditRow(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9)));
        }
      }
    }
    return rows;
  }

  private static void assertCliRow(AuditRow row, String result) {
    assertThat(row.actorType()).isEqualTo("system");
    assertThat(row.actorName()).isEqualTo("argos-admin");
    assertThat(row.result()).isEqualTo(result);
  }

  private static long scalar(String sql) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void exec(String sql) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  // --- migrate ---------------------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-053h migrate --json on an empty database applies every PostgreSQL and ClickHouse"
          + " version (one MIGRATION_APPLIED each, actor argos-admin); a second run applies nothing"
          + " and adds no MIGRATION_APPLIED row (idempotent); ADMIN_COMMAND_EXECUTED per run")
  void migrate() throws Exception {
    Run first = run("migrate", "--json");
    assertThat(first.code()).as(first.err()).isZero();
    JsonNode j = first.json();
    assertThat(j.path("command").asString()).isEqualTo("migrate");
    int pg = j.path("postgres").path("applied").size();
    int ch = j.path("clickhouse").path("applied").size();
    assertThat(pg).as("PostgreSQL versions V001...").isGreaterThanOrEqualTo(14);
    assertThat(ch).as("ClickHouse versions").isPositive();
    List<AuditRow> applied = audits("MIGRATION_APPLIED", null);
    assertThat(applied).hasSize(pg + ch);
    for (AuditRow row : applied) {
      assertCliRow(row, "success");
      assertThat(row.targetType()).isEqualTo("schema");
      assertThat(row.targetId()).matches("(postgres|clickhouse):.+");
    }
    assertThat(audit("ADMIN_COMMAND_EXECUTED", "migrate").after())
        .contains("\"postgres_applied\": " + pg);

    Run second = run("migrate", "--json");
    assertThat(second.code()).as(second.err()).isZero();
    assertThat(second.json().path("postgres").path("applied").size()).isZero();
    assertThat(second.json().path("clickhouse").path("applied").size()).isZero();
    assertThat(second.json().path("postgres").path("schema_version").asString())
        .isEqualTo(j.path("postgres").path("schema_version").asString());
    assertThat(audits("MIGRATION_APPLIED", null)).hasSize(pg + ch);
    assertThat(audits("ADMIN_COMMAND_EXECUTED", "migrate")).hasSize(2);

    Run table = run("migrate");
    assertThat(table.code()).isZero();
    assertThat(table.out()).contains("PostgreSQL: schema version").contains("0 versions applied");
  }

  // --- config ----------------------------------------------------------------------------------

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-053i config effective --json: every setting with value and source, a secret value as"
          + " ****, password files as paths; --changed lists the changed ones only; audited")
  void configEffective() throws Exception {
    byte[] raw = new byte[24];
    new SecureRandom().nextBytes(raw);
    String metricsToken = HexFormat.of().formatHex(raw);
    SECRETS.add(metricsToken);
    Run r = run("config", "effective", "--json", "--argos.metrics.token=" + metricsToken);
    assertThat(r.code()).as(r.err()).isZero();
    Map<String, JsonNode> byKey = new LinkedHashMap<>();
    for (JsonNode s : r.json().path("settings")) {
      byKey.put(s.path("key").asString(), s);
    }
    assertThat(r.json().path("count").asInt()).isEqualTo(byKey.size()).isGreaterThan(100);
    assertThat(byKey.get("argos.metrics.token").path("value").asString()).isEqualTo("****");
    assertThat(byKey.get("argos.metrics.token").path("source").asString())
        .isEqualTo("command line");
    assertThat(byKey.get("argos.postgres.password-file").path("value").asString())
        .isEqualTo(env.passwordFile(PgAccount.APP).toAbsolutePath().toString());
    assertThat(byKey.get("argos.retention.xlog").path("default").asBoolean()).isTrue();

    Run changed = run("config", "effective", "--changed", "--json");
    assertThat(changed.code()).isZero();
    assertThat(changed.json().path("count").asInt()).isLessThan(byKey.size()).isPositive();
    for (JsonNode s : changed.json().path("settings")) {
      assertThat(s.path("default").asBoolean()).isFalse();
    }
    AuditRow row = audit("ADMIN_COMMAND_EXECUTED", "config effective");
    assertCliRow(row, "success");
    assertThat(row.targetType()).isEqualTo("cli");
    assertThat(row.after()).contains("\"changed_only\": true");
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-053j config check --json: valid configuration exit 0; a broken cross-key rule exit 1"
          + " naming the key; a value that cannot be bound exit 1 with the same document; audited"
          + " success and failure")
  void configCheck() throws Exception {
    Run ok = run("config", "check", "--json");
    assertThat(ok.code()).as(ok.out() + ok.err()).isZero();
    assertThat(ok.json().path("valid").asBoolean()).isTrue();
    assertThat(ok.json().path("errors").size()).isZero();
    assertCliRow(audit("ADMIN_COMMAND_EXECUTED", "config check"), "success");

    Run bad = run("config", "check", "--json", "--argos.ops.disk.warn-ratio=0.95");
    assertThat(bad.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(bad.json().path("valid").asBoolean()).isFalse();
    assertThat(bad.json().path("errors").toString()).contains("argos.ops.disk");
    AuditRow failed = audit("ADMIN_COMMAND_EXECUTED", "config check");
    assertCliRow(failed, "failure");
    assertThat(failed.reason()).isEqualTo("invalid_configuration");

    Run unbound = run("config", "check", "--json", "--argos.retention.xlog=soon");
    assertThat(unbound.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(unbound.json().path("valid").asBoolean()).isFalse();
    assertThat(unbound.json().path("errors").size()).isEqualTo(1);

    Run table = run("config", "check");
    assertThat(table.code()).isZero();
    assertThat(table.out()).startsWith("Configuration valid.");
  }

  // --- cleanup ---------------------------------------------------------------------------------

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-053k cleanup instances|dictionary|commands --json: the silent instance archived, the"
          + " long archived one deleted, a connected or recent one kept; the 100-day-old dictionary"
          + " entry deleted, the recent one kept; the 40-day-old command deleted, the recent one"
          + " kept; ADMIN_COMMAND_EXECUTED with the counts")
  void cleanup() throws Exception {
    UUID user = UUID.randomUUID();
    exec(
        "INSERT INTO argos.users (id, username, password_hash, role) VALUES ('"
            + user
            + "', 'cli-it-operator', 'x', 'OPERATOR')");
    long app = scalar("INSERT INTO argos.apps (name, env) VALUES ('cli-it', 'prod') RETURNING id");
    exec(
        "INSERT INTO argos.instances (app_id, name, status, first_seen_at, last_seen_at,"
            + " archived_at) VALUES"
            + " ("
            + app
            + ", 'silent-8d', 'DOWN', now() - interval '60 days', now() - interval '8 days', NULL),"
            + " ("
            + app
            + ", 'archived-31d', 'DOWN', now() - interval '60 days', now() - interval '31 days',"
            + " now() - interval '20 days'),"
            + " ("
            + app
            + ", 'connected', 'UP', now() - interval '60 days', now() - interval '8 days', NULL),"
            + " ("
            + app
            + ", 'fresh', 'DOWN', now() - interval '2 days', now() - interval '1 day', NULL)");
    exec(
        "INSERT INTO argos.dictionary (app_id, kind, hash64, text, first_seen, last_seen) VALUES"
            + " ("
            + app
            + ", 'service', 11, '/old', now() - interval '200 days', now() - interval '100 days'),"
            + " ("
            + app
            + ", 'service', 12, '/new', now() - interval '200 days', now() - interval '10 days')");
    long fresh = scalar("SELECT id FROM argos.instances WHERE name = 'fresh'");
    exec(
        "INSERT INTO argos.commands (id, instance_id, type, requested_by, requested_at,"
            + " expires_at, status) VALUES"
            + " ('"
            + UUID.randomUUID()
            + "', "
            + fresh
            + ", 'thread_dump', '"
            + user
            + "', now() - interval '40 days', now() - interval '40 days', 'DONE'),"
            + " ('"
            + UUID.randomUUID()
            + "', "
            + fresh
            + ", 'thread_dump', '"
            + user
            + "', now() - interval '1 day', now() - interval '1 day', 'DONE')");

    Run instances = run("cleanup", "instances", "--json");
    assertThat(instances.code()).as(instances.err()).isZero();
    assertThat(instances.json().path("command").asString()).isEqualTo("cleanup instances");
    assertThat(instances.json().path("archived").asInt()).isEqualTo(1);
    assertThat(instances.json().path("deleted").asInt()).isEqualTo(1);
    assertThat(
            scalar(
                "SELECT count(*) FROM argos.instances WHERE name = 'silent-8d' AND archived_at IS"
                    + " NOT NULL"))
        .isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM argos.instances WHERE name = 'archived-31d'")).isZero();
    assertThat(
            scalar(
                "SELECT count(*) FROM argos.instances WHERE name IN ('connected', 'fresh') AND"
                    + " archived_at IS NULL"))
        .isEqualTo(2);
    AuditRow ir = audit("ADMIN_COMMAND_EXECUTED", "cleanup instances");
    assertCliRow(ir, "success");
    assertThat(ir.after()).contains("\"archived\": 1").contains("\"deleted\": 1");

    Run dictionary = run("cleanup", "dictionary", "--json");
    assertThat(dictionary.code()).as(dictionary.err()).isZero();
    assertThat(dictionary.json().path("deleted").asLong()).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM argos.dictionary WHERE text = '/old'")).isZero();
    assertThat(scalar("SELECT count(*) FROM argos.dictionary WHERE text = '/new'")).isEqualTo(1);
    assertCliRow(audit("ADMIN_COMMAND_EXECUTED", "cleanup dictionary"), "success");

    Run commands = run("cleanup", "commands", "--json");
    assertThat(commands.code()).as(commands.err()).isZero();
    assertThat(commands.json().path("deleted_expired").asInt()).isEqualTo(1);
    assertThat(commands.json().path("deleted_surplus").asInt()).isZero();
    assertThat(scalar("SELECT count(*) FROM argos.commands")).isEqualTo(1);
    assertCliRow(audit("ADMIN_COMMAND_EXECUTED", "cleanup commands"), "success");

    Run table = run("cleanup", "dictionary");
    assertThat(table.code()).isZero();
    assertThat(table.out()).startsWith("Cleanup of dictionary: deleted=0");
  }

  // --- token -----------------------------------------------------------------------------------

  @Test
  @Order(5)
  @DisplayName(
      "T-SV-053l token issue (alias of token create) --json prints the token once; token rotate"
          + " --prefix issues a successor and puts the old one in grace; token revoke --id revokes;"
          + " TOKEN_ISSUED, TOKEN_ROTATED, TOKEN_REVOKED with actor argos-admin; unknown prefix"
          + " exit 1")
  void tokens() throws Exception {
    Run issue = run("token", "issue", "--app", "cli-tok", "--env", "prod", "--json");
    assertThat(issue.code()).as(issue.err()).isZero();
    JsonNode i = issue.json();
    assertThat(i.path("command").asString()).isEqualTo("token issue");
    String first = i.path("token").asString();
    String firstPrefix = i.path("prefix").asString();
    assertThat(first).startsWith(firstPrefix).hasSize(49);
    assertThat(issue.err()).doesNotContain(first);
    AuditRow issued = audit("TOKEN_ISSUED", null);
    assertThat(issued.actorName()).isEqualTo("argos-admin");
    assertThat(issued.after()).doesNotContain(first);

    Run rotate = run("token", "rotate", "--prefix", firstPrefix, "--grace-days", "2", "--json");
    assertThat(rotate.code()).as(rotate.err()).isZero();
    JsonNode r = rotate.json();
    assertThat(r.path("command").asString()).isEqualTo("token rotate");
    assertThat(r.path("rotated_prefix").asString()).isEqualTo(firstPrefix);
    String second = r.path("token").asString();
    assertThat(second).hasSize(49).isNotEqualTo(first);
    assertThat(rotate.out()).doesNotContain(first);
    assertThat(
            scalar(
                "SELECT count(*) FROM argos.agent_tokens WHERE token_prefix = '"
                    + firstPrefix
                    + "' AND status = 'GRACE'"))
        .isEqualTo(1);
    AuditRow rotated = audit("TOKEN_ROTATED", null);
    assertThat(rotated.actorName()).isEqualTo("argos-admin");
    assertThat(rotated.result()).isEqualTo("success");
    assertThat(rotated.targetId()).isEqualTo(i.path("token_id").asString());

    String secondId = r.path("token_id").asString();
    Run revoke = run("token", "revoke", "--id", secondId, "--reason", "integration test", "--json");
    assertThat(revoke.code()).as(revoke.err()).isZero();
    assertThat(revoke.json().path("state").asString()).isEqualTo("revoked");
    assertThat(revoke.out() + revoke.err()).doesNotContain(second);
    assertThat(
            scalar(
                "SELECT count(*) FROM argos.agent_tokens WHERE id = '"
                    + secondId
                    + "' AND status = 'REVOKED'"))
        .isEqualTo(1);
    AuditRow revoked = audit("TOKEN_REVOKED", null);
    assertThat(revoked.actorName()).isEqualTo("argos-admin");
    assertThat(revoked.targetId()).isEqualTo(secondId);

    Run unknown = run("token", "revoke", "--prefix", "argos_zzzzzzzz", "--reason", "x", "--json");
    assertThat(unknown.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(unknown.json().path("code").asString()).isEqualTo("not_found");

    Run again = run("token", "revoke", "--id", secondId, "--reason", "twice", "--json");
    assertThat(again.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(again.json().path("code").asString()).isEqualTo("invalid_state");
  }

  // --- with a running server -------------------------------------------------------------------

  @Test
  @Order(6)
  @DisplayName(
      "T-SV-053m setup-token regenerate --json writes a new token to the token file only (not to"
          + " the output); a running server accepts it and no longer the one it issued; once an"
          + " administrator exists the command is refused (exit 1, admin_exists, audit rejected)")
  void setupToken() throws Exception {
    Path file = Path.of(env.springProperties().get("argos.auth.setup-token-file"));
    try (ArgosTestServer server = ArgosTestServer.start(env)) {
      SetupTokenBootstrap bootstrap = server.bean(SetupTokenBootstrap.class);
      String issued = Files.readString(file, StandardCharsets.UTF_8).strip();
      assertThat(bootstrap.isValid(issued)).as("the start's own token").isTrue();

      Run r = run("setup-token", "regenerate", "--json");
      assertThat(r.code()).as(r.err()).isZero();
      String token = Files.readString(file, StandardCharsets.UTF_8).strip();
      assertThat(token).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(issued);
      assertThat(r.out() + r.err()).doesNotContain(token);
      assertThat(r.json().path("file").asString())
          .isEqualTo(file.toAbsolutePath().normalize().toString());
      assertThat(r.json().path("expires_at").asString()).isNotBlank();
      assertThat(bootstrap.isValid(token)).as("the regenerated token is in force").isTrue();
      assertThat(bootstrap.isValid(issued)).as("the replaced token is not").isFalse();
      AuditRow row = audit("ADMIN_COMMAND_EXECUTED", "setup-token regenerate");
      assertCliRow(row, "success");
      assertThat(row.after()).doesNotContain(token);
    }
    exec(
        "INSERT INTO argos.users (id, username, password_hash, role, enabled) VALUES ('"
            + UUID.randomUUID()
            + "', 'cli-it-admin', 'x', 'ADMIN', TRUE)");
    Run refused = run("setup-token", "regenerate", "--json");
    assertThat(refused.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(refused.json().path("code").asString()).isEqualTo("admin_exists");
    AuditRow row = audit("ADMIN_COMMAND_EXECUTED", "setup-token regenerate");
    assertCliRow(row, "rejected");
    assertThat(row.reason()).isEqualTo("admin_exists");
  }

  @Test
  @Order(7)
  @DisplayName(
      "T-SV-053n tls reload --json: the certificate files checked, the running server answers the"
          + " request file within the wait (reloaded, same serial, TLS_RELOADED actor system),"
          + " ADMIN_COMMAND_EXECUTED; unreadable files exit 1 (invalid_files) without asking")
  void tlsReload() throws Exception {
    try (ArgosTestServer server = ArgosTestServer.start(env)) {
      assertThat(server.port()).isPositive();
      Run r = run("tls", "reload", "--wait", "30", "--json");
      assertThat(r.code()).as(r.out() + r.err()).isZero();
      JsonNode j = r.json();
      assertThat(j.path("listener").asString()).isEqualTo("grpc");
      String serial = j.path("certificate").path("serial").asString();
      assertThat(serial).isNotBlank();
      assertThat(j.path("server_result").isObject()).as(r.out()).isTrue();
      assertThat(j.path("server_result").path("reloaded").asBoolean()).isTrue();
      assertThat(j.path("server_result").path("serial").asString()).isEqualTo(serial);
      AuditRow reloaded = audit("TLS_RELOADED", null);
      assertThat(reloaded.actorType()).isEqualTo("system");
      AuditRow row = audit("ADMIN_COMMAND_EXECUTED", "tls reload");
      assertCliRow(row, "success");
      assertThat(row.after()).contains("\"reloaded\": true");
    }
    Run bad =
        run(
            "tls",
            "reload",
            "--wait",
            "0",
            "--json",
            "--argos.collector.tls.cert-file="
                + env.secretsDirectory().resolve("missing.crt").toAbsolutePath());
    assertThat(bad.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(bad.json().path("code").asString()).isEqualTo("invalid_files");
    assertCliRow(audit("ADMIN_COMMAND_EXECUTED", "tls reload"), "failure");
  }

  @Test
  @Order(8)
  @DisplayName(
      "T-SV-053o version --json needs no database and records no audit row (read-only, see"
          + " VersionCommand)")
  void version() throws Exception {
    Run r = run("version", "--json");
    assertThat(r.code()).isZero();
    assertThat(r.json().path("version").asString()).isNotBlank();
    assertThat(audits("ADMIN_COMMAND_EXECUTED", "version")).isEmpty();
  }
}
