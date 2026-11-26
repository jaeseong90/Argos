/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.SecretField;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.secrets.SecretRows;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
 * {@code argos-admin secrets verify} on a real PostgreSQL (WO-43; 16 §8.2, 17 §4.4; the key check
 * of the backup and restore scripts): every stored secret decrypts with the right key (a webhook
 * channel created through the running server's API and one row in every secret column), another
 * valid key fails every row with exit 1 and nothing secret in the output, a previous key decrypts
 * its rows, a database without stored secrets is {@code verifiable: false} with exit 0, the {@code
 * --argos.postgres.url} override reads another database (the restore's staging copy), and each run
 * leaves one {@code ADMIN_COMMAND_EXECUTED} row with the purpose and counts.
 */
@Tag("integration")
@Tag("req:SEC-52")
@Tag("req:SEC-84")
@Tag("req:DSN-17-14")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretsVerifyIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String STAGING = "argos_verify_staging";

  private static ServerTestEnvironment env;
  private static Path keys;
  private static SecretRows rows;
  private static String channelMarker;
  private static final List<String> SECRETS = new ArrayList<>();

  record Run(int code, String out, String err) {
    JsonNode json() {
      return JSON.readTree(out);
    }
  }

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    keys = Files.createTempDirectory("argos-verify-it");
    SECRETS.add(env.masterKey().hex());
    for (PgAccount a : PgAccount.values()) {
      SECRETS.add(env.password(a));
    }
    // A running server (its master key is env.masterKey()) writes a channel configuration through
    // the API; the start also applies the migrations.
    channelMarker = UUID.randomUUID().toString();
    SECRETS.add(channelMarker);
    try (ArgosTestServer server =
        ArgosTestServer.start(env, Map.of("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32"))) {
      TestUsers.TestUser user =
          new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
      ApiTestClient admin = server.client();
      assertThat(admin.login(user.username(), user.password()).status()).isEqualTo(200);
      Map<String, Object> channel = new LinkedHashMap<>();
      channel.put("type", "webhook");
      channel.put("name", "verify-hook");
      channel.put("target", "https://127.0.0.1:9/hook/" + channelMarker);
      ApiResponse created = admin.post("/api/v1/alerts/channels", channel);
      assertThat(created.status()).as(created.toString()).isEqualTo(201);
    }
    IntegrationLogs.restoreServerLogging();
    try (Connection c = env.connect(PgAccount.APP)) {
      rows = SecretRows.seed(c, SecretRows.cipher(env.masterKey().hex()));
    }
    SECRETS.addAll(rows.plaintexts());
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  private static Run run(Map<String, String> config, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    List<String> all = new ArrayList<>(List.of(args));
    // Configuration as the restore script gives it: command line arguments, not defaults.
    config.forEach((k, v) -> all.add("--" + k + "=" + v));
    int code =
        AdminCli.run(
            all.toArray(String[]::new),
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    IntegrationLogs.restoreServerLogging();
    Run run =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    String text = run.out() + run.err();
    for (String secret : SECRETS) {
      assertThat(text).as("no secret in the output").doesNotContain(secret);
    }
    assertThat(text).as("no envelope in the output").doesNotContainPattern("v1:[0-9a-f]{16}:");
    return run;
  }

  private static TestMasterKey newKey() {
    TestMasterKey k = TestMasterKey.create(keys);
    SECRETS.add(k.hex());
    return k;
  }

  private static String kid(TestMasterKey key) {
    return SecretRows.kid(key.hex());
  }

  /** The newest {@code ADMIN_COMMAND_EXECUTED} row of {@code secrets verify}: result and JSON. */
  private static List<String> lastAudit(String url) throws Exception {
    try (Connection c =
            DriverManager.getConnection(url, PgAccount.APP.user(), env.password(PgAccount.APP));
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT result, actor_name, row_to_json(a)::text FROM argos.audit_logs a"
                    + " WHERE action = 'ADMIN_COMMAND_EXECUTED' AND target_name = 'secrets verify'"
                    + " ORDER BY ts DESC, id DESC LIMIT 1")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("an ADMIN_COMMAND_EXECUTED row of secrets verify").isTrue();
        return List.of(rs.getString(1), rs.getString(2), rs.getString(3));
      }
    }
  }

  private static long auditCount() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(*) FROM argos.audit_logs WHERE action = 'ADMIN_COMMAND_EXECUTED'"
                    + " AND target_name = 'secrets verify'")) {
      assertThat(rs.next()).isTrue();
      return rs.getLong(1);
    }
  }

  private static JsonNode column(JsonNode json, SecretField field) {
    for (JsonNode c : json.path("columns")) {
      if (c.path("table").asString().equals(field.table())
          && c.path("column").asString().equals(field.column())) {
        return c;
      }
    }
    throw new AssertionError("no column " + field.qualifiedName() + " in " + json);
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-52e (part) secrets verify --json with the right key: every column present, every"
          + " stored secret (the API-created channel and one seeded row per column) decrypts,"
          + " kid = SHA-256(key)[0:8], exit 0; table output; audit ADMIN_COMMAND_EXECUTED with"
          + " purpose and counts only")
  void rightKey() throws Exception {
    long auditsBefore = auditCount();
    Run r = run(Map.of(), "secrets", "verify", "--json", "--purpose", "backup");
    assertThat(r.code()).as(r.err()).isZero();
    JsonNode j = r.json();
    assertThat(j.path("command").asString()).isEqualTo("secrets verify");
    assertThat(j.path("kid").asString()).isEqualTo(kid(env.masterKey())).hasSize(16);
    assertThat(j.path("previous_kid").isNull()).isTrue();
    assertThat(j.path("verifiable").asBoolean()).isTrue();
    assertThat(j.path("failed").asLong()).isZero();
    assertThat(j.path("columns")).hasSize(SecretField.values().length);
    for (SecretField f : SecretField.values()) {
      JsonNode c = column(j, f);
      assertThat(c.path("present").asBoolean()).as(f.name()).isTrue();
      assertThat(c.path("rows").asLong()).as(f.name()).isPositive();
      assertThat(c.path("ok").asLong()).as(f.name()).isEqualTo(c.path("rows").asLong());
      assertThat(c.path("kids"))
          .extracting(JsonNode::asString)
          .containsExactly(kid(env.masterKey()));
    }
    assertThat(column(j, SecretField.CHANNEL_CONFIG).path("rows").asLong())
        .as("the API-created channel and the seeded one")
        .isEqualTo(2);
    assertThat(j.path("rows").asLong()).isEqualTo(j.path("ok").asLong());

    List<String> audit = lastAudit(env.postgresJdbcUrl());
    assertThat(audit.get(0)).isEqualTo("success");
    assertThat(audit.get(1)).isEqualTo("argos-admin");
    assertThat(audit.get(2))
        .contains("\"purpose\": \"backup\"")
        .contains(kid(env.masterKey()))
        .contains("\"failed\": 0");
    assertThat(auditCount()).isEqualTo(auditsBefore + 1);

    Run table = run(Map.of(), "secrets", "verify");
    assertThat(table.code()).isZero();
    assertThat(table.out())
        .contains("Master key id " + kid(env.masterKey()) + " (previous key id none)")
        .contains("notification_channels.config_enc")
        .contains("Total: " + j.path("rows").asLong() + " stored, " + j.path("rows").asLong());
    assertThat(lastAudit(env.postgresJdbcUrl()).get(2)).contains("\"purpose\": \"manual\"");
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-52e another valid key (the wrong backup key): every stored secret fails, failed rows"
          + " listed by id and reason, exit 1, audit FAILURE undecryptable_rows; no key, plaintext"
          + " or envelope in stdout or stderr")
  void otherKey() throws Exception {
    TestMasterKey stranger = newKey();
    Run r =
        run(
            Map.of("argos.master-key-file", stranger.file().toString()),
            "secrets",
            "verify",
            "--json",
            "--purpose",
            "restore");
    assertThat(r.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    JsonNode j = r.json();
    assertThat(j.path("kid").asString()).isEqualTo(kid(stranger));
    assertThat(j.path("ok").asLong()).isZero();
    assertThat(j.path("failed").asLong()).isEqualTo(j.path("rows").asLong()).isPositive();
    JsonNode commands = column(j, SecretField.COMMAND_RESULT);
    assertThat(commands.path("failed_rows").get(0).asString())
        .isEqualTo(rows.commandId() + " (unknown_key)");
    assertThat(r.err()).contains("does not belong to this database");
    List<String> audit = lastAudit(env.postgresJdbcUrl());
    assertThat(audit.get(0)).isEqualTo("failure");
    assertThat(audit.get(2)).contains("undecryptable_rows").contains("\"purpose\": \"restore\"");
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-52e (part) a rotation in progress: current key new, previous key the one of the"
          + " rows; every row decrypts with the previous key, previous_kid reported, exit 0")
  void previousKey() throws Exception {
    TestMasterKey next = newKey();
    Run r =
        run(
            Map.of(
                "argos.master-key-file",
                next.file().toString(),
                "argos.security.master-key.previous-file",
                env.masterKey().file().toString()),
            "secrets",
            "verify",
            "--json");
    assertThat(r.code()).as(r.err()).isZero();
    JsonNode j = r.json();
    assertThat(j.path("kid").asString()).isEqualTo(kid(next));
    assertThat(j.path("previous_kid").asString()).isEqualTo(kid(env.masterKey()));
    assertThat(j.path("failed").asLong()).isZero();
    assertThat(j.path("ok").asLong()).isPositive();
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-52e (part) --argos.postgres.url reads another database (the restore's staging copy):"
          + " without stored secrets verifiable=false and exit 0, missing columns present=false;"
          + " one row under another key there makes it exit 1 while the main database still"
          + " passes")
  void urlOverride() throws Exception {
    String admin = env.postgresContainer().getUsername();
    String adminPassword = env.postgresContainer().getPassword();
    try (Connection c = DriverManager.getConnection(env.postgresJdbcUrl(), admin, adminPassword);
        Statement s = c.createStatement()) {
      s.execute("DROP DATABASE IF EXISTS " + STAGING);
      s.execute("CREATE DATABASE " + STAGING);
    }
    String stagingUrl = env.postgresJdbcUrl().replaceAll("/[^/]+$", "/" + STAGING);
    try (Connection c = DriverManager.getConnection(stagingUrl, admin, adminPassword);
        Statement s = c.createStatement()) {
      // An older schema: only apps has its secret column (and no audit table: the command's audit
      // row goes to the fallback file, the verification is unaffected).
      s.execute("CREATE SCHEMA argos");
      s.execute("CREATE TABLE argos.apps (id INTEGER PRIMARY KEY, user_id_salt_enc BYTEA)");
      s.execute("INSERT INTO argos.apps (id) VALUES (1)");
      s.execute("GRANT USAGE ON SCHEMA argos TO " + PgAccount.APP.user());
      s.execute("GRANT SELECT ON argos.apps TO " + PgAccount.APP.user());
    }
    Run empty =
        run(
            Map.of("argos.postgres.url", stagingUrl),
            "secrets",
            "verify",
            "--json",
            "--purpose",
            "restore");
    assertThat(empty.code()).as(empty.err()).isZero();
    JsonNode j = empty.json();
    assertThat(j.path("verifiable").asBoolean()).isFalse();
    assertThat(j.path("rows").asLong()).isZero();
    assertThat(column(j, SecretField.APP_USER_ID_SALT).path("present").asBoolean()).isTrue();
    assertThat(column(j, SecretField.CHANNEL_CONFIG).path("present").asBoolean()).isFalse();
    assertThat(column(j, SecretField.COMMAND_RESULT).path("present").asBoolean()).isFalse();
    assertThat(empty.err()).contains("no stored secret to check");

    TestMasterKey stranger = newKey();
    EnvelopeCipher cipher = SecretRows.cipher(stranger.hex());
    try (Connection c = DriverManager.getConnection(stagingUrl, admin, adminPassword);
        PreparedStatement ps =
            c.prepareStatement("UPDATE argos.apps SET user_id_salt_enc = ? WHERE id = 1")) {
      ps.setBytes(
          1, SecretField.stored(SecretField.APP_USER_ID_SALT.encrypt(cipher, new byte[32], "1")));
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    Run wrong = run(Map.of("argos.postgres.url", stagingUrl), "secrets", "verify", "--json");
    assertThat(wrong.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    JsonNode salts = column(wrong.json(), SecretField.APP_USER_ID_SALT);
    assertThat(salts.path("rows").asLong()).isEqualTo(1);
    assertThat(salts.path("failed").asLong()).isEqualTo(1);
    assertThat(salts.path("kids")).extracting(JsonNode::asString).containsExactly(kid(stranger));
    assertThat(salts.path("failed_rows").get(0).asString()).isEqualTo("1 (unknown_key)");

    Run main = run(Map.of(), "secrets", "verify", "--json");
    assertThat(main.code()).as("the main database is unaffected").isZero();
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SEC-52e (part) a missing or malformed key file exits 1 with code master_key before any"
          + " database read; a database that cannot be reached exits 1 with code database; bad"
          + " arguments exit 2")
  void failures() throws Exception {
    Run missing =
        run(
            Map.of("argos.master-key-file", keys.resolve("absent.key").toString()),
            "secrets",
            "verify",
            "--json");
    assertThat(missing.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(missing.json().path("code").asString()).isEqualTo("master_key");

    Path malformed = keys.resolve("malformed.key");
    Files.writeString(malformed, "not-a-key\n", StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(
          malformed, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException e) {
      // Windows: the loader cannot check permissions and notes it; the format check still runs.
    }
    Run bad =
        run(Map.of("argos.master-key-file", malformed.toString()), "secrets", "verify", "--json");
    assertThat(bad.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(bad.json().path("code").asString()).isEqualTo("master_key");
    assertThat(lastAudit(env.postgresJdbcUrl()).get(2)).contains("master_key");

    Run unreachable =
        run(
            Map.of("argos.postgres.url", "jdbc:postgresql://127.0.0.1:1/argos"),
            "secrets",
            "verify",
            "--json");
    assertThat(unreachable.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(unreachable.json().path("code").asString()).isEqualTo("database");

    assertThat(run(Map.of(), "secrets", "verify", "--purpose", "nightly").code())
        .isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(run(Map.of(), "secrets", "verify", "--write").code()).isEqualTo(AdminCli.EXIT_USAGE);
  }
}
