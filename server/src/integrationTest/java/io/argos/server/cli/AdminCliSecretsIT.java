/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.audit.AuditEvent;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.SecretField;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.secrets.SecretRows;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * {@code argos-admin secrets rekey} and its alias {@code master-key rotate} on a real PostgreSQL
 * (16 §8.2, 17 §4.4, ADR-0010 C-226 (2); WO-28 T-SV-053a to c): every secret column re-encrypted
 * under the target key, the JSON and table output, exit codes, rows that cannot be decrypted, and
 * the {@code MASTER_KEY_ROTATED} audit row with key ids only.
 */
@Tag("integration")
@Tag("req:SEC-52")
@Tag("req:C-226")
@Tag("req:DSN-17-14")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCliSecretsIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROTATED = AuditEvent.MASTER_KEY_ROTATED.name();

  private static ServerTestEnvironment env;
  private static Path keys;
  private static SecretRows rows;
  private static final List<String> KEY_TEXTS = new ArrayList<>();

  /** The key of the last successful rotation (the configured key of the next case). */
  private static TestMasterKey current;

  record Run(int code, String out, String err) {
    JsonNode json() {
      return JSON.readTree(out);
    }
  }

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    keys = Files.createTempDirectory("argos-rekey-it");
    // A start applies the migrations (V013: apps.user_id_salt_enc).
    try (ArgosTestServer server = ArgosTestServer.start(env)) {
      assertThat(server.port()).isPositive();
    }
    current = env.masterKey();
    KEY_TEXTS.add(current.hex());
    try (Connection c = env.connect(PgAccount.APP)) {
      rows = SecretRows.seed(c, SecretRows.cipher(current.hex()));
    }
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
    properties.putAll(config);
    int code =
        AdminCli.run(
            args,
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    IntegrationLogs.restoreServerLogging();
    Run run =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    for (String secret : KEY_TEXTS) {
      assertThat(run.out() + run.err()).doesNotContain(secret);
    }
    for (String plain : rows == null ? List.<String>of() : rows.plaintexts()) {
      assertThat(run.out() + run.err()).doesNotContain(plain);
    }
    for (PgAccount a : PgAccount.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    return run;
  }

  private static TestMasterKey newKey() {
    TestMasterKey k = TestMasterKey.create(keys);
    KEY_TEXTS.add(k.hex());
    return k;
  }

  /** Every stored envelope of the seeded rows names {@code hex} and decrypts with it alone. */
  private static void assertUnder(TestMasterKey key) throws Exception {
    EnvelopeCipher only = SecretRows.cipher(key.hex());
    try (Connection c = env.connect(PgAccount.APP)) {
      Map<SecretField, String> envelopes = rows.envelopes(c);
      assertThat(envelopes).hasSize(4);
      for (Map.Entry<SecretField, String> e : envelopes.entrySet()) {
        assertThat(SecretField.kidOf(e.getValue())).as(e.getKey().name()).isEqualTo(kid(key));
        byte[] plain = e.getKey().decrypt(only, e.getValue(), rows.rowId(e.getKey()));
        switch (e.getKey()) {
          case APP_USER_ID_SALT -> assertThat(plain).isEqualTo(rows.salt());
          case WEBHOOK_SIGNING_KEY ->
              assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo(rows.signingSecret());
          case CHANNEL_CONFIG ->
              assertThat(new String(plain, StandardCharsets.UTF_8)).contains(rows.channelMarker());
          case COMMAND_RESULT -> assertThat(plain).isNotEmpty();
        }
      }
    }
  }

  private static String kid(TestMasterKey key) {
    return SecretRows.kid(key.hex());
  }

  /** The newest MASTER_KEY_ROTATED row: target id, result, actor and the whole row as text. */
  private static List<String> lastAudit() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT target_id, result, actor_name, row_to_json(a)::text FROM argos.audit_logs a"
                    + " WHERE action = ? ORDER BY ts DESC, id DESC LIMIT 1")) {
      ps.setString(1, ROTATED);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("a MASTER_KEY_ROTATED row").isTrue();
        return List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
      }
    }
  }

  private static void assertColumns(JsonNode json, long each) {
    assertThat(json.path("columns")).hasSize(4);
    List<String> names = new ArrayList<>();
    for (JsonNode c : json.path("columns")) {
      names.add(c.path("column").asString());
      assertThat(c.path("reencrypted").asLong()).as(c.toString()).isEqualTo(each);
      assertThat(c.path("failed").asLong()).isZero();
      assertThat(c.path("changed").asLong()).isZero();
    }
    assertThat(names)
        .containsExactly(
            "notification_channels.config_enc",
            "webhook_signing_keys.key_enc",
            "command_results.body_enc",
            "apps.user_id_salt_enc");
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-053a secrets rekey --json: new key as master-key-file, old as previous-file; every"
          + " secret column re-encrypted (1 row each), exit 0, MASTER_KEY_ROTATED with key ids"
          + " only")
  void rekey() throws Exception {
    TestMasterKey old = current;
    TestMasterKey next = newKey();
    Run r =
        run(
            Map.of(
                "argos.master-key-file",
                next.file().toString(),
                "argos.security.master-key.previous-file",
                old.file().toString()),
            "secrets",
            "rekey",
            "--json",
            "--batch",
            "1");
    assertThat(r.code()).as(r.err()).isZero();
    JsonNode json = r.json();
    assertThat(json.path("command").asString()).isEqualTo("secrets rekey");
    assertThat(json.path("target_kid").asString()).isEqualTo(kid(next));
    assertThat(json.path("decrypt_kids")).extracting(JsonNode::asString).containsExactly(kid(old));
    assertColumns(json, 1);
    assertThat(json.path("reencrypted").asLong()).isEqualTo(4);
    assertThat(json.path("failed").asLong()).isZero();
    assertThat(r.err()).contains("re-encrypting under key id " + kid(next));
    assertUnder(next);

    List<String> audit = lastAudit();
    assertThat(audit.get(0)).isEqualTo(kid(next));
    assertThat(audit.get(1)).isEqualTo("success");
    assertThat(audit.get(2)).isEqualTo("argos-admin");
    assertThat(audit.get(3)).contains(kid(old));
    for (String secret : KEY_TEXTS) {
      assertThat(audit.get(3)).doesNotContain(secret);
    }
    current = next;
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-053b master-key rotate --new-key-file: the target is that file, the configured key"
          + " decrypts; a second run has nothing to do (table output, exit 0)")
  void alias() throws Exception {
    TestMasterKey configured = current;
    TestMasterKey next = newKey();
    Run r =
        run(
            Map.of("argos.master-key-file", configured.file().toString()),
            "master-key",
            "rotate",
            "--new-key-file",
            next.file().toString(),
            "--json");
    assertThat(r.code()).as(r.err()).isZero();
    assertThat(r.json().path("command").asString()).isEqualTo("master-key rotate");
    assertThat(r.json().path("target_kid").asString()).isEqualTo(kid(next));
    assertColumns(r.json(), 1);
    assertUnder(next);
    current = next;

    Run again = run(Map.of("argos.master-key-file", next.file().toString()), "secrets", "rekey");
    assertThat(again.code()).as(again.err()).isZero();
    assertThat(again.out())
        .contains("Master key rotation: target key id " + kid(next))
        .contains("notification_channels.config_enc")
        .contains("Total: 0 re-encrypted, 0 changed during the run (run again), 0 failed");
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-053c a row of an unknown key is left unchanged and listed: exit 1, failed 1, audit"
          + " FAILURE; no key file: exit 1 code master_key; bad arguments: exit 2")
  void failures() throws Exception {
    TestMasterKey stranger = newKey();
    UUID command;
    try (Connection c = env.connect(PgAccount.APP)) {
      SecretRows other = SecretRows.seed(c, SecretRows.cipher(stranger.hex()));
      command = other.commandId();
      // Only the command result stays under the unknown key: the other columns move to the
      // current key so that this case counts exactly one failure.
      EnvelopeCipher cipher = SecretRows.cipher(current.hex(), stranger.hex());
      for (Map.Entry<SecretField, String> e : other.envelopes(c).entrySet()) {
        if (e.getKey() == SecretField.COMMAND_RESULT) {
          continue;
        }
        byte[] plain = e.getKey().decrypt(cipher, e.getValue(), other.rowId(e.getKey()));
        String sealed = e.getKey().encrypt(cipher, plain, other.rowId(e.getKey()));
        String table = e.getKey().table();
        String column = e.getKey().column();
        String where =
            switch (e.getKey()) {
              case APP_USER_ID_SALT -> "id = " + other.appId();
              case CHANNEL_CONFIG -> "id = '" + other.channelId() + "'";
              case WEBHOOK_SIGNING_KEY ->
                  "channel_id = '" + other.channelId() + "' AND key_id = '" + other.keyId() + "'";
              case COMMAND_RESULT -> "FALSE";
            };
        try (PreparedStatement ps =
            c.prepareStatement(
                "UPDATE argos." + table + " SET " + column + " = ? WHERE " + where)) {
          ps.setBytes(1, SecretField.stored(sealed));
          assertThat(ps.executeUpdate()).isEqualTo(1);
        }
      }
    }
    Run r =
        run(
            Map.of("argos.master-key-file", current.file().toString()),
            "secrets",
            "rekey",
            "--json");
    assertThat(r.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(r.json().path("failed").asLong()).isEqualTo(1);
    JsonNode commands = r.json().path("columns").get(2);
    assertThat(commands.path("column").asString()).isEqualTo("command_results.body_enc");
    assertThat(commands.path("failed").asLong()).isEqualTo(1);
    assertThat(commands.path("failed_rows").get(0).asString())
        .isEqualTo(command + " (unknown_key)");
    assertThat(commands.path("from_kids").path(kid(stranger)).asLong()).isEqualTo(1);
    assertThat(r.err()).contains("could not be decrypted");
    assertThat(lastAudit().get(1)).isEqualTo("failure");
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT body_enc FROM argos.command_results WHERE command_id = ?")) {
      ps.setObject(1, command);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(SecretField.kidOf(SecretField.envelope(rs.getBytes(1))))
            .as("left unchanged")
            .isEqualTo(kid(stranger));
      }
    }

    Run missing =
        run(
            Map.of("argos.master-key-file", keys.resolve("absent.key").toString()),
            "secrets",
            "rekey",
            "--json");
    assertThat(missing.code()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(missing.json().path("code").asString()).isEqualTo("master_key");

    Run usage = run(Map.of(), "secrets", "rekey", "--batch", "0");
    assertThat(usage.code()).isEqualTo(AdminCli.EXIT_USAGE);
  }
}
