/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-80b: the alert channel events of the catalogue (16 §11.1 group 알림; WO-25) as {@code
 * audit_logs} rows of the real server: {@code CHANNEL_CREATED}, {@code CHANNEL_UPDATED}, {@code
 * CHANNEL_TEST_SENT}, {@code CHANNEL_SIGNING_KEY_ROTATED}, {@code CHANNEL_DELETED}, each with the
 * administrator as actor and the channel as target, and none carrying the channel URL or a signing
 * key (SEC-52).
 */
@Tag("req:SEC-80")
@Tag("req:SEC-52")
class ChannelAuditSecurityTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static Path dir;
  private static FakeWebhookReceiver receiver;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() throws Exception {
    dir = Files.createTempDirectory("argos-channel-audit");
    TestCertificates.TestPki pki = TestTls.pki(dir);
    SSLContext tls = TestTls.serverContext(pki);
    receiver = FakeWebhookReceiver.https(tls);
    TestMasterKey masterKey = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(masterKey.property(), masterKey.file().toString());
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    server = SecuritySuiteAccess.start(p);
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      receiver.close();
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

  private static AuditLog.Row row(String action, String channelId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      for (AuditLog.Row r : AuditLog.rows(SecuritySuiteAccess.environment(), action)) {
        if (channelId.equals(r.targetId())) {
          return r;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("No " + action + " row for channel " + channelId);
  }

  @Test
  @DisplayName(
      "T-SEC-80b channel create, update, test send, signing key rotation and delete are audited"
          + " (actor the administrator, target the channel), never with the URL or a key")
  void channelEvents() throws Exception {
    String secretPath = token();
    String url = "https://127.0.0.1:" + receiver.port() + "/services/" + secretPath;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "webhook");
    body.put("name", "audit-hook");
    body.put("target", url);
    ApiResponse created = admin().post("/api/v1/alerts/channels", body);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String id = created.json().path("data").path("channel").path("channel_id").asString();
    List<String> secrets = new ArrayList<>(List.of(secretPath, url));
    secrets.add(created.json().path("data").path("signing_key").asString());

    String newPath = token();
    secrets.add(newPath);
    ApiResponse updated =
        admin()
            .put(
                "/api/v1/alerts/channels/" + id,
                Map.of(
                    "name",
                    "audit-hook-2",
                    "target",
                    "https://127.0.0.1:" + receiver.port() + "/services/" + newPath));
    assertThat(updated.status()).as(updated.toString()).isEqualTo(200);

    receiver.clear();
    receiver.respond(200);
    ApiResponse test = admin().post("/api/v1/alerts/channels/" + id + "/test", null);
    assertThat(test.status()).as(test.toString()).isEqualTo(200);
    receiver.await(1, Duration.ofSeconds(10));

    ApiResponse rotated =
        admin().post("/api/v1/alerts/channels/" + id + "/signing-key/rotate", null);
    assertThat(rotated.status()).as(rotated.toString()).isEqualTo(200);
    secrets.add(rotated.json().path("data").path("signing_key").asString());

    ApiResponse deleted = admin().delete("/api/v1/alerts/channels/" + id);
    assertThat(deleted.status()).as(deleted.toString()).isIn(200, 204);

    for (String action :
        List.of(
            "CHANNEL_CREATED",
            "CHANNEL_UPDATED",
            "CHANNEL_TEST_SENT",
            "CHANNEL_SIGNING_KEY_ROTATED",
            "CHANNEL_DELETED")) {
      AuditLog.Row r = row(action, id);
      assertThat(r.actorType()).as(action).isEqualTo("user");
      assertThat(r.actorId()).as(action).isNotBlank();
      assertThat(r.targetType()).as(action).isEqualTo("channel");
      String text = r.toString();
      for (String secret : secrets) {
        assertThat(text).as(action + " carries no URL or key").doesNotContain(secret);
      }
    }
  }
}
