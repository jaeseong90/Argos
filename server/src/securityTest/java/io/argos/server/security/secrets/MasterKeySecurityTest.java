/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.alert.channel.WebhookSignature;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupAbortedException;
import io.argos.server.privacy.salt.AppSaltService;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.secrets.SecretField;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.storage.postgres.secrets.SecretsRekeyService;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.TestCertificates;
import io.argos.server.testing.secrets.SecretRows;
import io.argos.servertest.security.HeaderAuthentication;
import io.argos.storage.clickhouse.MemoryBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The master key on a real server (16 §8.2, ADR-0010 C-226 (2); WO-28):
 *
 * <ul>
 *   <li>T-SEC-52c: no key, a malformed key and (POSIX) a key file others can read stop the start in
 *       the {@code KEYS} phase with one ERROR line naming the setting, never the key;
 *   <li>T-SEC-52d / T-SEC-22b: after a rotation (new key as {@code master-key-file}, old key as
 *       {@code previous-file}, {@link SecretsRekeyService}) no secret is in a PostgreSQL dump in
 *       the clear, every envelope names the new key, and a server with the new key only (the old
 *       one removed) sends a signed test notification, shows the command result and keeps the
 *       application's user id salt.
 * </ul>
 *
 * The refusals use the suite's databases (the start stops before any connection); the rotation has
 * its own, so that only its own envelopes are in them.
 */
@Tag("req:SEC-52")
@Tag("req:SEC-22")
@Tag("req:C-226")
class MasterKeySecurityTest {

  private static Path dir;
  private static ServerTestEnvironment env;
  private static FakeWebhookReceiver receiver;
  private static TestCertificates.TestPki pki;
  private static final List<String> SECRETS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    dir = Files.createTempDirectory("argos-master-key-security");
    pki = TestTls.pki(dir);
    receiver = FakeWebhookReceiver.https(TestTls.serverContext(pki));
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      String log = Files.readString(SecuritySuiteAccess.logFile(), StandardCharsets.UTF_8);
      for (String secret : SECRETS) {
        assertThat(log).as("server log").doesNotContain(secret);
      }
    } finally {
      receiver.close();
      if (env != null) {
        env.close();
      }
    }
  }

  private static StartupAbortedException refused(ServerTestEnvironment on, Map<String, ?> props) {
    Throwable failure = null;
    try {
      ArgosTestServer.start(on, props).close();
    } catch (RuntimeException e) {
      failure = e;
    }
    assertThat(failure).as("the start must fail").isNotNull();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof StartupAbortedException aborted) {
        return aborted;
      }
    }
    throw new AssertionError("not a start refusal", failure);
  }

  private static void assertKeysRefusal(StartupAbortedException aborted, String reason) {
    assertThat(aborted.phase()).isEqualTo(PhaseOrder.KEYS);
    assertThat(aborted.step()).isEqualTo("master-key");
    assertThat(aborted.getExitCode()).isEqualTo(1);
    assertThat(aborted.getMessage())
        .contains("Startup phase KEYS step master-key failed")
        .contains(reason)
        .contains("The server does not start.");
  }

  @Test
  @DisplayName(
      "T-SEC-52c no key file and a malformed key refuse the start in KEYS with one line naming the"
          + " setting, not the key or the file")
  void refusedMissingOrMalformed() throws Exception {
    ServerTestEnvironment suite = SecuritySuiteAccess.environment();
    Path absent = dir.resolve("absent.key");
    StartupAbortedException missing =
        refused(suite, Map.of("argos.master-key-file", absent.toString()));
    assertKeysRefusal(missing, "No master key: argos.master-key is not set");
    assertThat(missing.getMessage()).doesNotContain(absent.toString());

    String garbage = "not-a-master-key-" + SecretRows.token();
    SECRETS.add(garbage);
    Path malformed = Files.createTempFile(dir, "malformed", ".key");
    if (malformed.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(malformed, PosixFilePermissions.fromString("rw-------"));
    }
    Files.writeString(malformed, garbage);
    StartupAbortedException bad =
        refused(suite, Map.of("argos.master-key-file", malformed.toString()));
    assertKeysRefusal(bad, "argos.master-key-file: the master key is not 32 bytes");
    assertThat(bad.getMessage()).doesNotContain(garbage).doesNotContain(malformed.toString());

    String log = Files.readString(SecuritySuiteAccess.logFile(), StandardCharsets.UTF_8);
    assertThat(
            log.lines()
                .filter(l -> l.contains("\"logger\":\"io.argos.server.bootstrap.StartupSequence\""))
                .filter(l -> l.contains("step master-key failed")))
        .allMatch(l -> l.contains("\"level\":\"ERROR\""))
        .hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-SEC-52c a key file group or others can read (0644) refuses the start; permissive=true"
          + " starts with a WARN (POSIX file systems only)")
  void refusedLoosePermissions() throws Exception {
    TestMasterKey key = TestMasterKey.create(dir);
    assumeTrue(
        key.file().getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions only: on this file system (Windows NTFS) the server cannot check them,"
            + " logs one INFO line and starts (16 §8.2, documented in docs/install)");
    SECRETS.add(key.hex());
    Files.setPosixFilePermissions(key.file(), PosixFilePermissions.fromString("rw-r--r--"));
    ServerTestEnvironment suite = SecuritySuiteAccess.environment();
    StartupAbortedException loose =
        refused(suite, Map.of("argos.master-key-file", key.file().toString()));
    assertKeysRefusal(
        loose, "argos.master-key-file: the key file is accessible to group or others");

    Map<String, Object> permissive = new LinkedHashMap<>();
    permissive.put("argos.master-key-file", key.file().toString());
    permissive.put("argos.security.master-key.permissive", "true");
    try (ArgosTestServer started = SecuritySuiteAccess.start(permissive)) {
      assertThat(started.bean(MasterKeyRing.class).current().orElseThrow().kid())
          .isEqualTo(MasterKey.parse(key.hex()).kid());
    }
    String log = Files.readString(SecuritySuiteAccess.logFile(), StandardCharsets.UTF_8);
    assertThat(log).contains("accepted because argos.security.master-key.permissive=true");
  }

  private static ArgosTestServer server(Map<String, Object> extra) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    p.putAll(extra);
    return ArgosTestServer.start(
        env, p, app -> app.addPrimarySources(List.of(HeaderAuthentication.class)));
  }

  private static ApiTestClient admin(ArgosTestServer on) {
    ApiTestClient client = on.client();
    client.cookies().put(ApiTestClient.CSRF_COOKIE, csrfToken(), "/");
    client.header(HeaderAuthentication.ROLE_HEADER, "admin");
    return client;
  }

  /** A CSRF cookie value of the server's token shape (32 random bytes, base64url). */
  private static String csrfToken() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Every text form of the tables with secrets, the audit log and the settings. */
  private static String dump() throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      for (String table :
          List.of(
              "notification_channels",
              "webhook_signing_keys",
              "command_results",
              "apps",
              "audit_logs",
              "settings")) {
        try (ResultSet rs =
            s.executeQuery("SELECT row_to_json(t)::text, t::text FROM argos." + table + " t")) {
          while (rs.next()) {
            sb.append(rs.getString(1)).append('\n').append(rs.getString(2)).append('\n');
          }
        }
      }
      try (ResultSet rs =
          s.executeQuery(
              "SELECT encode(config_enc, 'escape') FROM argos.notification_channels UNION ALL"
                  + " SELECT encode(key_enc, 'escape') FROM argos.webhook_signing_keys UNION ALL"
                  + " SELECT encode(body_enc, 'escape') FROM argos.command_results UNION ALL"
                  + " SELECT encode(user_id_salt_enc, 'escape') FROM argos.apps")) {
        while (rs.next()) {
          sb.append(rs.getString(1)).append('\n');
        }
      }
    }
    return sb.toString();
  }

  @Test
  @DisplayName(
      "T-SEC-52d T-SEC-22b after the rotation no secret is in PostgreSQL in the clear, every"
          + " envelope names the new key, and a server with the new key only sends a signed test,"
          + " shows the command result and keeps the user id salt")
  void rotation() throws Exception {
    env = ServerTestEnvironment.builder().clickHouseMemoryLimit(MemoryBudget.GIB).start();
    TestMasterKey oldKey = env.masterKey();
    SECRETS.add(oldKey.hex());

    // 1. A server with the old key stores a webhook channel through the API.
    String path = SecretRows.token();
    SECRETS.add(path);
    String url = "https://127.0.0.1:" + receiver.port() + "/services/" + path;
    String channelId;
    String signingKey;
    SecretRows rows;
    try (ArgosTestServer first = server(Map.of())) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("type", "webhook");
      body.put("name", "rekey-hook");
      body.put("target", url);
      ApiResponse created = admin(first).post("/api/v1/alerts/channels", body);
      assertThat(created.status()).as(created.toString()).isEqualTo(201);
      JsonNode data = created.json().path("data");
      channelId = data.path("channel").path("channel_id").asString();
      signingKey = data.path("signing_key").asString();
      SECRETS.add(signingKey);
      // 2. A command result and an application salt under the old key.
      try (Connection c = env.connect(PgAccount.APP)) {
        rows = SecretRows.seed(c, SecretRows.cipher(oldKey.hex()));
      }
      SECRETS.addAll(rows.plaintexts());
      assertThat(first.bean(AppSaltService.class).salt(rows.appId()).orElseThrow())
          .isEqualTo(rows.salt());
    }

    // 3. Rotation: the new key encrypts, the old one decrypts.
    TestMasterKey newKey = TestMasterKey.create(dir);
    SECRETS.add(newKey.hex());
    MasterKeyRing ring =
        MasterKeyRing.of(MasterKey.parse(newKey.hex()), List.of(MasterKey.parse(oldKey.hex())));
    SecretsRekeyService.Result result;
    try (Connection c = env.connect(PgAccount.APP)) {
      SecretsRekeyService service = SecretsRekeyService.over(c, ring);
      result = service.rekey(2, SecretsRekeyService.Progress.NONE);
      assertThat(result.failed()).isZero();
      assertThat(result.reencrypted()).isGreaterThanOrEqualTo(6);
      Map<SecretField, Map<String, Long>> kids = service.kidsInUse();
      String newKid = MasterKey.parse(newKey.hex()).kid();
      for (Map.Entry<SecretField, Map<String, Long>> e : kids.entrySet()) {
        assertThat(e.getValue().keySet()).as(e.getKey().name()).containsExactly(newKid);
      }
    } finally {
      ring.destroy();
    }

    String dump = dump();
    assertThat(dump).contains("v1:" + MasterKey.parse(newKey.hex()).kid() + ":");
    assertThat(dump).doesNotContain("v1:" + MasterKey.parse(oldKey.hex()).kid() + ":");
    for (String secret : SECRETS) {
      assertThat(dump).as("PostgreSQL dump").doesNotContain(secret);
    }

    // 4. A server with only the new key: the old key file is gone.
    Files.delete(oldKey.file());
    try (ArgosTestServer second =
        server(Map.of("argos.master-key-file", newKey.file().toString()))) {
      receiver.clear();
      receiver.respond(200);
      ApiResponse test = admin(second).post("/api/v1/alerts/channels/" + channelId + "/test", null);
      assertThat(test.status()).as(test.toString()).isEqualTo(200);
      assertThat(test.json().path("data").path("delivered").asBoolean())
          .as(test.toString())
          .isTrue();
      FakeWebhookReceiver.Received r = receiver.await(1, Duration.ofSeconds(10)).get(0);
      assertThat(r.path()).isEqualTo("/services/" + path);
      assertThat(
              WebhookSignature.verify(
                  r.header(WebhookSignature.HEADER), signingKey, r.body(), Instant.now()))
          .isTrue();

      ApiResponse view =
          admin(second).get("/api/v1/commands/" + rows.commandId() + "/result?format=text");
      assertThat(view.status()).as(view.toString()).isEqualTo(200);
      String token = rows.commandBody().replaceAll(".*\"([A-Za-z0-9_-]{24})\".*", "$1");
      assertThat(view.body()).contains(token);

      assertThat(second.bean(AppSaltService.class).salt(rows.appId()).orElseThrow())
          .isEqualTo(rows.salt());
    }
  }
}
