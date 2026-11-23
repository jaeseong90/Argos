/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.channel.WebhookSignature;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.FakeSmtpServer;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The notification channel API on a real server and PostgreSQL (T-SEC-51 to T-SEC-54, T-SV-035, 16
 * §8): destination validation at save and at test send, masked targets, encrypted secrets in the
 * database, signatures and their rotation, SMTP with STARTTLS, and no secret in any log line or
 * response. The destinations are in-process receivers on 127.0.0.1 ({@code allow-cidrs
 * 127.0.0.1/32}); nothing leaves the machine.
 */
@Tag("req:SEC-51")
@Tag("req:SEC-52")
@Tag("req:SEC-53")
@Tag("req:SEC-54")
class AlertChannelSecurityTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SMTP_USER = "argos-alerts";

  private static Path dir;
  private static TestMasterKey masterKey;
  private static String smtpPassword;
  private static FakeWebhookReceiver receiver;
  private static FakeWebhookReceiver stolen;
  private static FakeSmtpServer smtp;
  private static ArgosTestServer server;
  private static final List<String> SECRETS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    dir = Files.createTempDirectory("argos-alert-security");
    TestCertificates.TestPki pki = TestTls.pki(dir);
    SSLContext tls = TestTls.serverContext(pki);
    receiver = FakeWebhookReceiver.https(tls);
    stolen = FakeWebhookReceiver.https(tls);
    smtpPassword = "smtp-" + token();
    SECRETS.add(smtpPassword);
    smtp = new FakeSmtpServer(true, tls, SMTP_USER, smtpPassword);
    Path passwordFile = Files.writeString(dir.resolve("smtp-password"), smtpPassword + "\n");
    masterKey = TestMasterKey.create(dir);
    SECRETS.add(masterKey.hex());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(masterKey.property(), masterKey.file().toString());
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    p.put("argos.alert.smtp.host", "localhost");
    p.put("argos.alert.smtp.port", Integer.toString(smtp.port()));
    p.put("argos.alert.smtp.username", SMTP_USER);
    p.put("argos.alert.smtp.password-file", passwordFile.toString());
    p.put("argos.alert.smtp.from", "argos@example.test");
    p.put("argos.alert.smtp.allowed-recipient-domains[0]", "example.test");
    server = SecuritySuiteAccess.start(p);
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      if (server != null) {
        server.close();
      }
      // T-SEC-52 / SEC-48: no secret of this class in any line of the server log
      String log = Files.readString(SecuritySuiteAccess.logFile(), StandardCharsets.UTF_8);
      for (String secret : SECRETS) {
        assertThat(log).as("server log").doesNotContain(secret);
      }
    } finally {
      receiver.close();
      stolen.close();
      smtp.close();
    }
  }

  private static String token() {
    byte[] b = new byte[18];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static ApiTestClient admin() {
    SecuritySuiteAccess.resetLimits(server);
    return SecuritySuiteAccess.client(server, "admin");
  }

  /** A webhook URL on the receiver with a secret path; the path goes to {@link #SECRETS}. */
  private static String secretUrl(FakeWebhookReceiver on) {
    String path = "/services/" + token();
    SECRETS.add(path.substring("/services/".length()));
    return "https://127.0.0.1:" + on.port() + path;
  }

  private static JsonNode created(ApiResponse r) {
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    return r.json().path("data");
  }

  private static ApiResponse create(String type, String name, String target) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("name", name);
    body.put("target", target);
    return admin().post("/api/v1/alerts/channels", body);
  }

  @ParameterizedTest(name = "T-SEC-51b saving {0} answers 422 webhook_destination_blocked")
  @ValueSource(
      strings = {
        "https://127.0.0.2/hook",
        "https://10.0.0.1/hook",
        "https://172.16.0.1/hook",
        "https://192.168.0.1/hook",
        "https://169.254.169.254/latest/meta-data",
        "https://100.64.0.1/hook",
        "https://0.0.0.0/hook",
        "https://224.0.0.1/hook",
        "https://[::1]/hook",
        "https://[fe80::1]/hook",
        "https://[fd00::1]/hook",
        "https://[::ffff:10.0.0.1]/hook",
        "https://2130706433/hook",
        "https://user:pw@127.0.0.1/hook"
      })
  void blocked(String url) {
    for (String type : List.of("webhook", "slack")) {
      ApiResponse r = create(type, "blocked", url);
      assertThat(r.status()).as(r.toString()).isEqualTo(422);
      assertThat(r.errorCode()).contains("webhook_destination_blocked");
      assertThat(r.json().path("error").path("details").get(0).path("field").asString())
          .isEqualTo("target");
      assertThat(r.body()).doesNotContain(url);
    }
  }

  @Test
  @DisplayName("T-SEC-51b http:// is refused by default (allow-http off), also with allow_http")
  void httpRefused() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "webhook");
    body.put("name", "plain");
    body.put("target", "http://127.0.0.1:" + receiver.port() + "/hook");
    body.put("allow_http", true);
    ApiResponse r = admin().post("/api/v1/alerts/channels", body);
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.json().path("error").path("details").get(0).path("reason").asString())
        .isEqualTo("scheme");
  }

  @Test
  @DisplayName(
      "T-SEC-51c T-SEC-52b T-SEC-53 an allowed destination is saved; the target is masked, the"
          + " signing key is shown once; a test send is signed, schema_version 1, and its body"
          + " never comes back")
  void createListAndTest() {
    String url = secretUrl(receiver);
    JsonNode data = created(create("webhook", "ops-hook", url));
    String id = data.path("channel").path("channel_id").asString();
    String signingKey = data.path("signing_key").asString();
    SECRETS.add(signingKey);
    assertThat(signingKey).hasSize(43);
    assertThat(data.path("channel").path("target_masked").asString())
        .isEqualTo("https://127.0.0.1:" + receiver.port() + "/****");
    assertThat(data.path("channel").path("signing_enabled").asBoolean()).isTrue();

    ApiResponse list = SecuritySuiteAccess.client(server, "viewer").get("/api/v1/alerts/channels");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body()).contains(id).doesNotContain(url).doesNotContain(signingKey);
    assertThat(list.body()).doesNotContain(url.substring(url.lastIndexOf('/') + 1));

    receiver.clear();
    receiver.respond(200).body("RECEIVER-PRIVATE-" + token());
    ApiResponse test = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(test.status()).as(test.toString()).isEqualTo(200);
    JsonNode result = test.json().path("data");
    assertThat(result.path("delivered").asBoolean()).isTrue();
    assertThat(result.path("status_code").asInt()).isEqualTo(200);
    assertThat(result.has("duration_ms")).isTrue();
    assertThat(test.body()).doesNotContain("RECEIVER-PRIVATE");

    FakeWebhookReceiver.Received r = receiver.await(1, Duration.ofSeconds(10)).get(0);
    assertThat(
            WebhookSignature.verify(
                r.header(WebhookSignature.HEADER), signingKey, r.body(), Instant.now()))
        .isTrue();
    JsonNode payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.body());
    assertThat(payload.path("schema_version").asInt()).isEqualTo(1);
    assertThat(payload.path("alert").path("kind").asString()).isEqualTo("test");
  }

  @Test
  @DisplayName(
      "T-SEC-51d T-SEC-51e a test send to a destination that redirects is not followed and a"
          + " failing destination's body is not shown")
  void redirectAndBody() {
    String id =
        created(create("webhook", "redirecting", secretUrl(receiver)))
            .path("channel")
            .path("channel_id")
            .asString();
    receiver.clear();
    receiver.redirect(stolen.url("/stolen"));
    ApiResponse r = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().path("data").path("delivered").asBoolean()).isFalse();
    assertThat(r.json().path("data").path("status_code").asInt()).isEqualTo(302);
    assertThat(r.json().path("data").path("error").asString()).isEqualTo("redirect");
    assertThat(receiver.requests()).hasSize(1);
    assertThat(stolen.requests()).isEmpty();

    String marker = "PRIVATE-BODY-" + token();
    receiver.respond(500).body(marker);
    ApiResponse failed = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(failed.json().path("data").path("status_code").asInt()).isEqualTo(500);
    assertThat(failed.body()).doesNotContain(marker);
    receiver.respond(200);
  }

  @Test
  @DisplayName(
      "T-SEC-51 a test send validates again: a stored destination that is blocked now answers 422")
  void testSendValidatesAgain() throws Exception {
    // A channel whose stored URL resolves to a blocked range (as after a DNS change): written
    // directly with the server's master key, the way an older server could have stored it.
    UUID id = UUID.randomUUID();
    MasterKeyRing ring = new MasterKeyRing(MasterKey.parse(masterKey.hex()), null);
    String envelope =
        new EnvelopeCipher(ring)
            .encryptString(
                "{\"url\":\"https://169.254.169.254/latest\",\"allow_http\":false}",
                EnvelopeCipher.aad("notification_channels", "config_enc", id.toString()));
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.notification_channels (id, name, type, config_enc, config_masked,"
                    + " enabled, created_at, updated_at) VALUES (?, 'stale', 'slack', ?,"
                    + " '{\"target\":\"https://169.254.169.254/****\"}'::jsonb, TRUE, now(), now())")) {
      ps.setObject(1, id);
      ps.setBytes(2, envelope.getBytes(StandardCharsets.UTF_8));
      ps.executeUpdate();
    }
    ApiResponse r = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(r.status()).as(r.toString()).isEqualTo(422);
    assertThat(r.errorCode()).contains("webhook_destination_blocked");
    assertThat(r.json().path("error").path("details").get(0).path("reason").asString())
        .isEqualTo("link_local");
  }

  @Test
  @DisplayName(
      "T-SEC-52a no plaintext URL, recipient, SMTP password or signing key in PostgreSQL; the"
          + " stored envelopes decrypt with the master key, also as the previous key of a rotation")
  void databaseHoldsNoPlaintext() throws Exception {
    String url = secretUrl(receiver);
    JsonNode webhook = created(create("webhook", "db-check", url));
    String signingKey = webhook.path("signing_key").asString();
    SECRETS.add(signingKey);
    String recipient =
        "oncall-"
            + token().toLowerCase(java.util.Locale.ROOT).replace('_', 'x').replace('-', 'y')
            + "@example.test";
    JsonNode email = created(create("email", "mail", recipient));
    assertThat(email.path("channel").path("target_masked").asString())
        .isEqualTo(recipient.charAt(0) + "***@example.test");
    // a test mail exercises the SMTP password
    String emailId = email.path("channel").path("channel_id").asString();
    ApiResponse sent = admin().post("/api/v1/alerts/channels/" + emailId + "/test", null);
    assertThat(sent.json().path("data").path("delivered").asBoolean()).as(sent.toString()).isTrue();

    String dump = dumpAlertTables(SecuritySuiteAccess.environment());
    String path = url.substring(url.lastIndexOf('/') + 1);
    assertThat(dump)
        .doesNotContain(path)
        .doesNotContain(signingKey)
        .doesNotContain(recipient)
        .doesNotContain(smtpPassword);

    // decrypt the stored envelope of the webhook with the key as the *previous* key of a ring
    String id = webhook.path("channel").path("channel_id").asString();
    String envelope;
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT config_enc FROM argos.notification_channels WHERE id = ?")) {
      ps.setObject(1, UUID.fromString(id));
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        envelope = new String(rs.getBytes(1), StandardCharsets.UTF_8);
      }
    }
    assertThat(envelope).startsWith("v1:" + MasterKey.parse(masterKey.hex()).kid() + ":");
    byte[] newKey = new byte[32];
    RANDOM.nextBytes(newKey);
    EnvelopeCipher rotated =
        new EnvelopeCipher(
            new MasterKeyRing(MasterKey.of(newKey), MasterKey.parse(masterKey.hex())));
    assertThat(
            rotated.decryptString(
                envelope, EnvelopeCipher.aad("notification_channels", "config_enc", id)))
        .contains(path);
  }

  @Test
  @DisplayName(
      "T-SEC-53b T-SEC-52 after a rotation both keys sign for the grace (two v1 values); the new"
          + " key is shown once")
  void rotation() {
    JsonNode data = created(create("webhook", "rotating", secretUrl(receiver)));
    String id = data.path("channel").path("channel_id").asString();
    String oldKey = data.path("signing_key").asString();
    SECRETS.add(oldKey);
    ApiResponse rotated =
        admin().post("/api/v1/alerts/channels/" + id + "/signing-key/rotate", null);
    assertThat(rotated.status()).as(rotated.toString()).isEqualTo(200);
    String newKey = rotated.json().path("data").path("signing_key").asString();
    SECRETS.add(newKey);
    assertThat(newKey).isNotEqualTo(oldKey).hasSize(43);
    Instant until =
        Instant.parse(rotated.json().path("data").path("previous_valid_until").asString());
    assertThat(Duration.between(Instant.now(), until))
        .isBetween(Duration.ofDays(6), Duration.ofDays(7));

    receiver.clear();
    receiver.respond(200);
    assertThat(admin().post("/api/v1/alerts/channels/" + id + "/test", null).status())
        .isEqualTo(200);
    FakeWebhookReceiver.Received r = receiver.await(1, Duration.ofSeconds(10)).get(0);
    String header = r.header(WebhookSignature.HEADER);
    assertThat(header.split(",v1=", -1)).hasSize(3);
    assertThat(WebhookSignature.verify(header, newKey, r.body(), Instant.now())).isTrue();
    assertThat(WebhookSignature.verify(header, oldKey, r.body(), Instant.now())).isTrue();
    assertThat(SecuritySuiteAccess.client(server, "viewer").get("/api/v1/alerts/channels").body())
        .doesNotContain(newKey)
        .doesNotContain(oldKey);
  }

  @Test
  @DisplayName(
      "T-SEC-54c the e-mail channel sends over STARTTLS with AUTH; a recipient outside"
          + " allowed-recipient-domains is refused; a server without STARTTLS fails the send")
  void email() {
    ApiResponse outside = create("email", "outside", "someone@other.example");
    assertThat(outside.status()).isEqualTo(400);
    assertThat(outside.json().path("error").path("details").get(0).path("reason").asString())
        .isEqualTo("recipient_domain_not_allowed");

    String id =
        created(create("email", "ops-mail", "ops@example.test"))
            .path("channel")
            .path("channel_id")
            .asString();
    int before = smtp.inbox().size();
    ApiResponse sent = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(sent.json().path("data").path("delivered").asBoolean()).as(sent.toString()).isTrue();
    FakeSmtpServer.Message m = smtp.await(before + 1, Duration.ofSeconds(10)).getLast();
    assertThat(m.tls()).isTrue();
    assertThat(m.authenticatedUser()).isEqualTo(SMTP_USER);
    assertThat(m.recipients()).containsExactly("ops@example.test");
    assertThat(m.header("Subject")).isEqualTo("[Argos] Argos test notification");

    smtp.offerStartTls(false);
    try {
      ApiResponse refused = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
      assertThat(refused.json().path("data").path("delivered").asBoolean()).isFalse();
      assertThat(refused.json().path("data").path("error").asString())
          .isEqualTo("starttls_unavailable");
      assertThat(smtp.inbox()).hasSize(before + 1);
    } finally {
      smtp.offerStartTls(true);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-52 a channel used by a rule is not deleted (409); deleted, its secrets are destroyed")
  void delete() throws Exception {
    String id =
        created(create("webhook", "to-delete", secretUrl(receiver)))
            .path("channel")
            .path("channel_id")
            .asString();
    UUID ruleId = UUID.randomUUID();
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.alert_rules (id, name, spec) VALUES (?, 'uses channel',"
                    + " CAST(? AS jsonb))")) {
      ps.setObject(1, ruleId);
      ps.setString(2, "{\"channels\":{\"warn\":[],\"critical\":[\"" + id + "\"]}}");
      ps.executeUpdate();
    }
    ApiResponse refused = admin().delete("/api/v1/alerts/channels/" + id);
    assertThat(refused.status()).as(refused.toString()).isEqualTo(409);
    assertThat(refused.json().path("error").path("details").get(0).path("reason").asString())
        .isEqualTo("referenced_by_rule");

    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("UPDATE argos.alert_rules SET deleted_at = now() WHERE id = ?")) {
      ps.setObject(1, ruleId);
      ps.executeUpdate();
    }
    assertThat(admin().delete("/api/v1/alerts/channels/" + id).status()).isEqualTo(204);
    assertThat(admin().get("/api/v1/alerts/channels").body()).doesNotContain(id);
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT length(config_enc), deleted_at IS NOT NULL, (SELECT count(*) FROM"
                    + " argos.webhook_signing_keys k WHERE k.channel_id = c.id) FROM"
                    + " argos.notification_channels c WHERE id = ?")) {
      ps.setObject(1, UUID.fromString(id));
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isZero();
        assertThat(rs.getBoolean(2)).isTrue();
        assertThat(rs.getInt(3)).isZero();
      }
    }
  }

  @Test
  @DisplayName(
      "T-SEC-52 without a master key a channel cannot be created: 409 secret_unavailable (WO-25"
          + " assumption; the start refusal is WO-28's)")
  void noMasterKey() {
    ArgosTestServer plain = SecuritySuiteAccess.server();
    SecuritySuiteAccess.resetLimits(plain);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "email");
    body.put("name", "no-key");
    body.put("target", "ops@example.test");
    ApiResponse r =
        SecuritySuiteAccess.client(plain, "admin").post("/api/v1/alerts/channels", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(409);
    assertThat(r.errorCode()).contains("secret_unavailable");
  }

  @Test
  @DisplayName("T-SEC-49 only administrators change channels or send tests; viewers list them")
  void roles() {
    ApiResponse viewer =
        SecuritySuiteAccess.client(server, "viewer")
            .post(
                "/api/v1/alerts/channels",
                Map.of("type", "email", "name", "x", "target", "a@example.test"));
    assertThat(viewer.status()).isEqualTo(403);
    ApiResponse operator =
        SecuritySuiteAccess.client(server, "operator")
            .post(
                "/api/v1/alerts/channels",
                Map.of("type", "email", "name", "x", "target", "a@example.test"));
    assertThat(operator.status()).isEqualTo(403);
    assertThat(SecuritySuiteAccess.client(server, "viewer").get("/api/v1/alerts/channels").status())
        .isEqualTo(200);
  }

  /** Every text form of the alerting tables, the audit log and the settings. */
  private static String dumpAlertTables(ServerTestEnvironment env) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      for (String table :
          List.of(
              "notification_channels",
              "webhook_signing_keys",
              "notification_deliveries",
              "audit_logs",
              "settings")) {
        try (ResultSet rs = s.executeQuery("SELECT * FROM argos." + table)) {
          int columns = rs.getMetaData().getColumnCount();
          while (rs.next()) {
            for (int i = 1; i <= columns; i++) {
              Object v = rs.getObject(i);
              if (v instanceof byte[] bytes) {
                sb.append(new String(bytes, StandardCharsets.UTF_8))
                    .append(' ')
                    .append(HexFormat.of().formatHex(bytes));
              } else {
                sb.append(v);
              }
              sb.append('\n');
            }
          }
        }
      }
    }
    return sb.toString();
  }
}
