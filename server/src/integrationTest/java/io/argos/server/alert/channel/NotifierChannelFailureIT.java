/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.NotificationSink;
import io.argos.server.audit.Actor;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SV-034 on a real server, PostgreSQL and in-process webhook receivers: a failing channel is
 * retried 3 times with the configured waits, its deliveries end {@code failed}, and after 3
 * consecutive failed deliveries the channel is {@code failing} and every other enabled channel gets
 * the {@code channel_failure} self alert (12 §5.5, F-S27). The waits are shortened with {@code
 * argos.testing.notify-retry-delays}.
 */
@Tag("req:F-S27")
class NotifierChannelFailureIT {

  private static final List<Duration> DELAYS =
      List.of(Duration.ofMillis(300), Duration.ofMillis(600), Duration.ofMillis(900));
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static FakeWebhookReceiver failing;
  private static FakeWebhookReceiver healthy;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Path dir = Files.createTempDirectory("argos-notifier-it");
    TestCertificates.TestPki pki = TestTls.pki(dir);
    SSLContext tls = TestTls.serverContext(pki);
    failing = FakeWebhookReceiver.https(tls).respond(500);
    healthy = FakeWebhookReceiver.https(tls);
    TestMasterKey key = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(key.property(), key.file().toString());
    p.put("argos.alert.webhook.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.testing.alert-ca-file", pki.caCertificate().toString());
    p.put(
        "argos.testing.notify-retry-delays",
        DELAYS.stream().map(d -> d.toMillis() + "ms").reduce((a, b) -> a + "," + b).orElseThrow());
    server = ArgosTestServer.start(env, p);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (failing != null) {
      failing.close();
    }
    if (healthy != null) {
      healthy.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static UUID channel(String name, FakeWebhookReceiver on) {
    ChannelService service = server.bean(ChannelService.class);
    ChannelService.Created created =
        service.create(
            new ChannelService.CreateCommand("webhook", name, on.url("/hook"), null, null),
            Actor.system());
    return created.channel().id();
  }

  private static AlertNotification notification(UUID channel, UUID eventId) {
    AlertNotification.Item item =
        new AlertNotification.Item(
            eventId,
            UUID.randomUUID(),
            AlertNotification.Kind.FIRING,
            AlertNotification.Severity.WARN,
            Instant.now(),
            Map.of("app", "shop", "metric", "error_rate", "value", "12"),
            "shop error_rate 12 >= 5");
    return new AlertNotification(
        new AlertNotification.Group(null, null, null, AlertNotification.Severity.WARN),
        AlertNotification.Kind.FIRING,
        List.of(item),
        0,
        "shop: error rate",
        "shop error_rate 12 >= 5",
        AlertNotification.Recipients.of(List.of(channel)),
        List.of(eventId));
  }

  private static List<Map<String, Object>> deliveries(UUID channel) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT alert_event_id, attempt, status, http_status, error"
                    + " FROM argos.notification_deliveries WHERE channel_id = ?")) {
      ps.setObject(1, channel);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("event", rs.getObject(1, UUID.class));
          row.put("attempt", rs.getInt(2));
          row.put("status", rs.getString(3));
          row.put("http_status", rs.getObject(4));
          row.put("error", rs.getString(5));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private static boolean selfRuleExists() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM argos.alert_rules WHERE deleted_at IS NULL AND enabled AND"
                    + " spec->>'metric' = 'argos_notification_failures_total'");
        ResultSet rs = ps.executeQuery()) {
      return rs.next() && rs.getInt(1) > 0;
    }
  }

  @Test
  @DisplayName(
      "T-SV-034 a failing channel is retried 3 times with the configured waits, its deliveries end"
          + " failed, and after 3 consecutive failures the other channel receives channel_failure")
  void retriesAndSelfAlert() throws Exception {
    await().atMost(90, TimeUnit.SECONDS).until(NotifierChannelFailureIT::selfRuleExists);
    UUID bad = channel("failing-hook", failing);
    UUID good = channel("healthy-hook", healthy);
    NotificationSink sink = server.bean(NotificationSink.class);
    List<UUID> events = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    for (UUID event : events) {
      sink.submit(notification(bad, event));
    }

    await()
        .atMost(60, TimeUnit.SECONDS)
        .until(
            () ->
                deliveries(bad).stream().filter(r -> "failed".equals(r.get("status"))).count()
                    == 3);
    for (Map<String, Object> row : deliveries(bad)) {
      assertThat(row.get("attempt")).as("1 attempt + 3 retries").isEqualTo(4);
      assertThat(row.get("http_status")).isEqualTo(500);
      assertThat(row.get("error")).isEqualTo("http_status");
      assertThat(events).contains((UUID) row.get("event"));
    }
    // per notification: 4 requests, the gaps at least the configured waits
    for (UUID event : events) {
      List<Instant> times = new ArrayList<>();
      for (FakeWebhookReceiver.Received r : failing.requests()) {
        if (r.bodyText().contains(event.toString())) {
          times.add(r.at());
        }
      }
      assertThat(times).hasSize(4);
      for (int i = 1; i < times.size(); i++) {
        assertThat(Duration.between(times.get(i - 1), times.get(i)))
            .isGreaterThanOrEqualTo(DELAYS.get(i - 1).minusMillis(20));
      }
    }
    ChannelService.Summary badNow =
        server.bean(ChannelService.class).list().stream()
            .filter(s -> s.id().equals(bad))
            .findFirst()
            .orElseThrow();
    assertThat(badNow.status()).isEqualTo("failing");
    assertThat(badNow.consecutiveFailures()).isEqualTo(3);
    assertThat(badNow.lastFailureAt()).isNotNull();

    FakeWebhookReceiver.Received self = healthy.await(1, Duration.ofSeconds(60)).get(0);
    JsonNode payload = JSON.readTree(self.body());
    assertThat(payload.path("alert").path("kind").asString()).isEqualTo("self");
    assertThat(payload.path("alert").path("severity").asString()).isEqualTo("critical");
    assertThat(failing.requests().stream().filter(r -> r.bodyText().contains("\"self\"")))
        .isEmpty();
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> deliveries(good).stream().anyMatch(r -> "sent".equals(r.get("status"))));
  }

  @Test
  @DisplayName(
      "C-111 (2) the daily cleanup deletes deliveries older than 365 days and signing keys whose"
          + " rotation grace ended; newer rows and the current key stay")
  void cleanup() throws Exception {
    UUID channel = channel("cleanup-hook", healthy);
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.notification_deliveries (id, alert_event_id, channel_id, attempt,"
                    + " status, sent_at) VALUES (?, NULL, ?, 1, 'sent', now() - CAST(? AS"
                    + " interval))")) {
      ps.setObject(1, old);
      ps.setObject(2, channel);
      ps.setString(3, "366 days");
      ps.executeUpdate();
      ps.setObject(1, recent);
      ps.setObject(2, channel);
      ps.setString(3, "364 days");
      ps.executeUpdate();
    }
    // a rotation leaves the previous key in its grace; end the grace in the past
    server.bean(ChannelService.class).rotateSigningKey(channel, Actor.system());
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE argos.webhook_signing_keys SET retire_after = now() - interval '1 minute'"
                    + " WHERE channel_id = ? AND retire_after IS NOT NULL")) {
      ps.setObject(1, channel);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }

    DeliveryCleanupJob.Result result = server.bean(DeliveryCleanupJob.class).run();

    assertThat(result.deliveries()).isGreaterThanOrEqualTo(1);
    assertThat(result.signingKeys()).isGreaterThanOrEqualTo(1);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT (SELECT count(*) FROM argos.notification_deliveries WHERE id = ?),"
                    + " (SELECT count(*) FROM argos.notification_deliveries WHERE id = ?),"
                    + " (SELECT count(*) FROM argos.webhook_signing_keys WHERE channel_id = ?)")) {
      ps.setObject(1, old);
      ps.setObject(2, recent);
      ps.setObject(3, channel);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).as("older than 365 days").isZero();
        assertThat(rs.getInt(2)).as("within 365 days").isEqualTo(1);
        assertThat(rs.getInt(3)).as("the current key").isEqualTo(1);
      }
    }
  }
}
