/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionKind;
import io.argos.server.auth.Role;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.ws.WsTestClient;
import io.argos.server.testing.ws.WsTestClient.Frame;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The WebSocket protocol of {@code /ws} against the real server with a real client (T-API-007, 14
 * §5): frame schemas, {@code since}, the error codes, the limits (20 subscriptions, 4 KiB, 5
 * connections, subscription rate), the 60 s timeout and the backpressure of a slow subscriber
 * (T-SV-021, WebSocket path).
 */
@Tag("integration")
@Tag("req:SEC-44")
@Tag("req:F-S03")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketProtocolIT {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private RealtimeStack stack;
  private int appId;

  @BeforeAll
  void start() throws Exception {
    stack = RealtimeStack.start();
    appId = stack.app("ws-protocol-" + Long.toHexString(System.nanoTime())).appId();
  }

  @AfterAll
  void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  @Test
  @DisplayName(
      "T-API-007 ack {id, topic, since} (ISO-8601 UTC ms), pong {t, server_t}, topic_unknown,"
          + " invalid_params (frame and parameters), unknown application, forbidden admin topic;"
          + " the connection stays open")
  void framesAndErrors() {
    ApiTestClient viewer = stack.login(Role.VIEWER);
    try (WsTestClient ws = WsTestClient.open(viewer)) {
      Instant before = stack.clock.instant().minusMillis(1);
      ws.subscribe("s1", "xlog", "{\"app\":" + appId + ",\"kind\":\"HTTP\"}");
      Frame ack = ws.expect("ack", "s1", WAIT);
      assertThat(ack.json().path("topic").asString()).isEqualTo("xlog");
      String since = ack.json().path("since").asString();
      assertThat(since).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
      assertThat(Instant.parse(since))
          .isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

      ws.ping(1729645323456L);
      Frame pong = ws.expect("pong", null, WAIT);
      assertThat(pong.json().path("t").asLong()).isEqualTo(1729645323456L);
      assertThat(pong.json().path("server_t").asString()).endsWith("Z");

      ws.subscribe("s2", "events", "{}");
      assertThat(ws.expect("error", "s2", WAIT).json().path("code").asString())
          .isEqualTo("topic_unknown");
      ws.subscribe("s3", "counter", "{\"app\":\"three\"}");
      Frame invalid = ws.expect("error", "s3", WAIT);
      assertThat(invalid.json().path("code").asString()).isEqualTo("invalid_params");
      assertThat(invalid.json().path("message").asString()).isEqualTo("app: invalid_value");
      ws.subscribe("s4", "counter", "{\"app\":" + appId + ",\"extra\":true}");
      assertThat(ws.expect("error", "s4", WAIT).json().path("code").asString())
          .isEqualTo("invalid_params");
      ws.subscribe("s5", "counter", "{\"app\":2147483000}");
      assertThat(ws.expect("error", "s5", WAIT).json().path("message").asString())
          .isEqualTo("app: unknown");
      ws.send("{\"op\":\"subscribe\",\"id\":\"s6\",\"topic\":\"xlog\",\"params\":{},\"x\":1}");
      assertThat(ws.expect("error", "s6", WAIT).json().path("code").asString())
          .isEqualTo("invalid_params");
      ws.send("{\"op\":\"hello\"}");
      Frame noId = ws.expect("error", null, WAIT);
      assertThat(noId.json().has("id")).isFalse();
      assertThat(noId.json().path("code").asString()).isEqualTo("invalid_params");
      ws.send("not json at all");
      assertThat(ws.expect("error", null, WAIT).json().path("code").asString())
          .isEqualTo("invalid_params");
      ws.subscribe("s7", "admin", "{\"change_id\":\"c-1\"}");
      assertThat(ws.expect("error", "s7", WAIT).json().path("code").asString())
          .isEqualTo("forbidden");
      ws.subscribe("s8", "alert", "{\"env\":\"prod\"}");
      ws.expect("ack", "s8", WAIT);
      ws.subscribe("s9", "overview", "{}");
      ws.expect("ack", "s9", WAIT);
      ws.unsubscribe("s1");
      ws.unsubscribe("never-subscribed");
      ws.ping(2);
      ws.expect("pong", null, WAIT);
      assertThat(ws.isOpen()).isTrue();
    }
    try (WsTestClient admin = WsTestClient.open(stack.login(Role.ADMIN))) {
      admin.subscribe("a1", "admin", "{\"change_id\":\"c-1\"}");
      admin.expect("ack", "a1", WAIT);
    }
  }

  @Test
  @DisplayName(
      "T-API-007 the 21st subscription of a connection is error limit_exceeded and the connection"
          + " stays; the same id replaces; unsubscribe frees a place")
  void subscriptionLimit() throws InterruptedException {
    try (WsTestClient ws = WsTestClient.open(stack.login(Role.VIEWER))) {
      for (int i = 1; i <= 20; i++) {
        ws.subscribe("c" + i, "counter", "{\"app\":" + appId + "}");
        ws.expect("ack", "c" + i, WAIT);
      }
      // The 20 subscribes used the rate-limit bucket (capacity 20, one token per second).
      Thread.sleep(1_100);
      ws.subscribe("c21", "counter", "{\"app\":" + appId + "}");
      Frame limit = ws.expect("error", "c21", WAIT);
      assertThat(limit.json().path("code").asString()).isEqualTo("limit_exceeded");
      assertThat(limit.json().path("message").asString()).contains("20 subscriptions");
      Thread.sleep(1_100);
      ws.subscribe("c3", "active", "{\"app\":" + appId + "}");
      ws.expect("ack", "c3", WAIT);
      ws.unsubscribe("c1");
      ws.ping(1);
      ws.expect("pong", null, WAIT);
      assertThat(ws.isOpen()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-API-007 subscription changes are rate limited per connection (20 at once, then 1 per"
          + " second): the 21st subscribe within a second is error limit_exceeded, the connection"
          + " stays, and a second later a subscribe passes")
  void rateLimit() throws Exception {
    try (WsTestClient ws = WsTestClient.open(stack.login(Role.VIEWER))) {
      for (int i = 0; i < 20; i++) {
        ws.subscribe("r", "counter", "{\"app\":" + appId + "}");
      }
      for (int i = 0; i < 20; i++) {
        ws.expect("ack", "r", WAIT);
      }
      ws.subscribe("r", "counter", "{\"app\":" + appId + "}");
      Frame limited = ws.expect("error", "r", WAIT);
      assertThat(limited.json().path("code").asString()).isEqualTo("limit_exceeded");
      assertThat(limited.json().path("message").asString()).contains("rate limited");
      Thread.sleep(1_100);
      ws.subscribe("r2", "counter", "{\"app\":" + appId + "}");
      ws.expect("ack", "r2", WAIT);
      assertThat(ws.isOpen()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-API-007 a message of 4,096 bytes is answered, one of 4,097 bytes closes the connection"
          + " with 4413")
  void messageSize() {
    try (WsTestClient ws = WsTestClient.open(stack.login(Role.VIEWER))) {
      String ping = "{\"op\":\"ping\",\"t\":7}";
      String exact = ping.substring(0, ping.length() - 1) + " ".repeat(4096 - ping.length()) + "}";
      assertThat(exact.length()).isEqualTo(4096);
      ws.send(exact);
      assertThat(ws.expect("pong", null, WAIT).json().path("t").asLong()).isEqualTo(7);
      ws.send(exact.substring(0, 10) + " " + exact.substring(10));
      assertThat(ws.awaitClose(WAIT)).isTrue();
      assertThat(ws.closeCode()).isEqualTo(4413);
    }
  }

  @Test
  @DisplayName(
      "T-API-007 five connections of a user are open, the sixth closes with 4429; after one"
          + " closes, a new one is accepted")
  void connectionsPerUser() {
    ApiTestClient viewer = stack.login(Role.VIEWER);
    List<WsTestClient> open = new ArrayList<>();
    try {
      for (int i = 0; i < 5; i++) {
        open.add(WsTestClient.open(viewer));
      }
      try (WsTestClient sixth = WsTestClient.open(viewer)) {
        assertThat(sixth.awaitClose(WAIT)).isTrue();
        assertThat(sixth.closeCode()).isEqualTo(4429);
      }
      for (WsTestClient ws : open) {
        ws.ping(3);
        ws.expect("pong", null, WAIT);
      }
      open.remove(0).close();
      long deadline = System.nanoTime() + WAIT.toNanos();
      WsTestClient again = null;
      while (System.nanoTime() < deadline) {
        WsTestClient c = WsTestClient.open(viewer);
        c.ping(4);
        if (c.await(f -> f.op().equals("pong"), Duration.ofSeconds(2)).isPresent()) {
          again = c;
          break;
        }
        c.close();
      }
      assertThat(again).as("a new connection after one closed").isNotNull();
      open.add(again);
    } finally {
      open.forEach(WsTestClient::close);
    }
  }

  @Test
  @DisplayName(
      "T-API-007 60 s without a client message (server clock) close the connection with 4408;"
          + " a connection that pings stays")
  void clientTimeout() {
    try (WsTestClient silent = WsTestClient.open(stack.login(Role.VIEWER));
        WsTestClient talking = WsTestClient.open(stack.login(Role.VIEWER))) {
      stack.clock.advance(Duration.ofSeconds(30));
      talking.ping(1);
      talking.expect("pong", null, WAIT);
      stack.clock.advance(Duration.ofSeconds(31));
      assertThat(silent.awaitClose(Duration.ofSeconds(5))).isTrue();
      assertThat(silent.closeCode()).isEqualTo(4408);
      talking.ping(2);
      talking.expect("pong", null, WAIT);
      assertThat(talking.isOpen()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-SV-021 a slow subscriber over the real connection: the oldest xlog pushes are dropped,"
          + " notice backpressure and the next push's dropped report them; the publisher never"
          + " blocks")
  void slowSubscriber() throws Exception {
    ApiTestClient viewer = stack.login(Role.VIEWER);
    WsTestClient.Handshake h =
        WsTestClient.connect(
            viewer.baseUrl(),
            viewer.cookie(ApiTestClient.ACCESS_COOKIE).orElseThrow(),
            viewer.serverOrigin(),
            true);
    assertThat(h.accepted()).isTrue();
    try (WsTestClient slow = h.client()) {
      slow.subscribe("x", "xlog", "{\"app\":" + appId + ",\"kind\":\"ALL\"}");
      RealtimeBus bus = stack.server.bean(RealtimeBus.class);
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (bus.subscriptions(Topic.XLOG) == 0 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertThat(bus.subscriptions(Topic.XLOG)).isPositive();
      SessionContext session =
          new SessionContext(
              appId,
              1,
              "prod",
              UUID.randomUUID(),
              1L,
              IpMode.MASKED,
              UserIdMode.OFF,
              500,
              stack.clock.millis(),
              "1.0",
              0);
      long tx = 255_183_728_640_000_000L;
      long maxPublishNanos = 0;
      for (int round = 0; round < 40; round++) {
        long now = stack.clock.millis();
        List<XlogPoint> points = new ArrayList<>(40_000);
        for (int i = 0; i < 40_000; i++) {
          points.add(new XlogPoint(tx++, now - 5, 40, TransactionKind.HTTP, 17L, false, 200, 0));
        }
        long t0 = System.nanoTime();
        bus.publishXlog(session.atReceipt(now, 0, 500), points);
        maxPublishNanos = Math.max(maxPublishNanos, System.nanoTime() - t0);
        Thread.sleep(110);
      }
      slow.resume();
      Frame notice =
          slow.await(f -> f.op().equals("notice"), Duration.ofSeconds(30))
              .orElseThrow(() -> new AssertionError("no notice backpressure"));
      assertThat(notice.json().path("code").asString()).isEqualTo("backpressure");
      assertThat(notice.json().path("topic").asString()).isEqualTo("xlog");
      long dropped = notice.json().path("dropped").asLong();
      assertThat(dropped).isPositive();
      Frame next =
          slow.await(f -> f.op().equals("push") && f.json().path("dropped").asLong() > 0, WAIT)
              .orElseThrow(() -> new AssertionError("no push with dropped > 0"));
      assertThat(next.json().path("id").asString()).isEqualTo("x");
      assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(maxPublishNanos))
          .as("publishXlog never waits for the subscriber")
          .isLessThan(1_000);
      System.out.printf(
          "[wo22-backpressure] dropped=%d maxPublishMs=%d%n",
          dropped, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(maxPublishNanos));
    }
  }
}
