/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static io.argos.server.realtime.BusFixture.point;
import static io.argos.server.realtime.BusFixture.session;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.argos.server.realtime.WsFixture.RecordingTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The subscription logic of a connection (14 §5.1-§5.3) over a real bus and a connection whose
 * frames are sent inline: frame order, error codes, limits, the role check and role changes.
 */
@Tag("req:SEC-44")
@Tag("req:F-S03")
class SubscriptionManagerTest {

  private static final int APP = 3;

  private final TestClock clock = new TestClock(T0 + 3_000);
  private final Sources sources = new Sources().app(APP, "prod", 1_000).name(APP, 17, "GET /a");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RealtimeBus bus = BusFixture.bus(sources, clock, registry);
  private final SubscriptionManager manager = new SubscriptionManager(bus, clock, registry);
  private final RecordingTransport transport = new RecordingTransport();

  private WsConnection connection(Role role) {
    return WsFixture.connection(bus, transport, Runnable::run, role, clock.millis());
  }

  private static String subscribe(String id, String topic, String params) {
    return "{\"op\":\"subscribe\",\"id\":\""
        + id
        + "\",\"topic\":\""
        + topic
        + "\",\"params\":"
        + params
        + "}";
  }

  private JsonNode last() {
    List<JsonNode> frames = transport.frames();
    return frames.get(frames.size() - 1);
  }

  @Test
  @DisplayName(
      "T-API-007b subscribe -> ack (id, topic, since) before the first push; the push carries the"
          + " point with u64 strings, dict and dropped")
  void ackBeforePush() {
    WsConnection c = connection(Role.VIEWER);
    manager.onFrame(c, subscribe("s1", "xlog", "{\"app\":3,\"kind\":\"HTTP\"}"));
    bus.publishXlog(
        session(APP, "prod", 12, clock.millis()),
        List.of(point(-1L, clock.millis(), 50, false, 17)));
    bus.bundleTick(clock.millis());
    List<JsonNode> frames = transport.frames();
    assertThat(frames).hasSize(2);
    JsonNode ack = frames.get(0);
    assertThat(ack.get("op").asString()).isEqualTo("ack");
    assertThat(ack.get("id").asString()).isEqualTo("s1");
    assertThat(ack.get("topic").asString()).isEqualTo("xlog");
    assertThat(ack.get("since").asString()).isEqualTo(ServerFrames.time(clock.millis()));
    JsonNode push = frames.get(1);
    assertThat(push.get("op").asString()).isEqualTo("push");
    assertThat(push.get("data").get("tx_id").get(0).asString()).isEqualTo("18446744073709551615");
    assertThat(push.get("dict").get("service").get("17").asString()).isEqualTo("GET /a");
    assertThat(push.get("dropped").asLong()).isZero();
  }

  @Test
  @DisplayName(
      "T-API-007b topic_unknown, invalid_params (frame and parameters, never the sent value),"
          + " unknown application; the connection stays open")
  void errors() {
    WsConnection c = connection(Role.VIEWER);
    manager.onFrame(c, subscribe("s1", "events", "{}"));
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.TOPIC_UNKNOWN);
    assertThat(last().get("id").asString()).isEqualTo("s1");
    manager.onFrame(c, subscribe("s2", "xlog", "{\"app\":\"<script>\"}"));
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.INVALID_PARAMS);
    assertThat(last().get("message").asString()).isEqualTo("app: invalid_value");
    manager.onFrame(c, subscribe("s3", "xlog", "{\"app\":3,\"bogus\":1}"));
    assertThat(last().get("message").asString()).isEqualTo("bogus: unknown_parameter");
    manager.onFrame(c, subscribe("s4", "counter", "{\"app\":99}"));
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.INVALID_PARAMS);
    assertThat(last().get("message").asString()).isEqualTo("app: unknown");
    manager.onFrame(c, subscribe("s5", "counter", "{\"app\":3,\"env\":\"dev\"}"));
    assertThat(last().get("message").asString()).isEqualTo("env: not_the_application_env");
    manager.onFrame(c, "{\"op\":\"subscribe\",\"id\":\"s6\",\"topic\":\"xlog\"}");
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.INVALID_PARAMS);
    manager.onFrame(c, "{\"op\":\"bogus\"}");
    assertThat(last().has("id")).isFalse();
    manager.onFrame(c, subscribe("s7", "xlog", "{\"" + "k".repeat(1_000) + "\":1}"));
    assertThat(last().get("message").asString().length())
        .isLessThanOrEqualTo(SubscriptionManager.MAX_MESSAGE_LENGTH);
    assertThat(c.subscriptionCount()).isZero();
    assertThat(transport.closes).isEmpty();
    assertThat(transport.ops()).doesNotContain("ack");
  }

  @Test
  @DisplayName(
      "T-API-007b the 21st subscription is limit_exceeded and the connection stays; the same id"
          + " replaces its subscription; unsubscribe frees a place")
  void subscriptionLimit() {
    WsConnection c = connection(Role.VIEWER);
    for (int i = 1; i <= WsConnection.MAX_SUBSCRIPTIONS; i++) {
      manager.onFrame(c, subscribe("s" + i, "counter", "{\"app\":3}"));
    }
    assertThat(c.subscriptionCount()).isEqualTo(20);
    assertThat(bus.subscriptions(Topic.COUNTER)).isEqualTo(20);
    manager.onFrame(c, subscribe("s21", "counter", "{\"app\":3}"));
    assertThat(last().get("op").asString()).isEqualTo("error");
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.LIMIT_EXCEEDED);
    assertThat(last().get("id").asString()).isEqualTo("s21");
    manager.onFrame(c, subscribe("s5", "xlog", "{\"app\":3}"));
    assertThat(last().get("op").asString()).isEqualTo("ack");
    assertThat(c.subscription("s5").topic()).isEqualTo(Topic.XLOG);
    assertThat(bus.subscriptions(Topic.COUNTER)).isEqualTo(19);
    manager.onFrame(c, "{\"op\":\"unsubscribe\",\"id\":\"s1\"}");
    manager.onFrame(c, "{\"op\":\"unsubscribe\",\"id\":\"never\"}");
    assertThat(c.subscriptionCount()).isEqualTo(19);
    manager.onFrame(c, subscribe("s21", "counter", "{\"app\":3}"));
    assertThat(last().get("op").asString()).isEqualTo("ack");
    assertThat(transport.closes).isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-007b subscription changes are rate limited per connection: 20 at once, then 1 per"
          + " second; the refusal is limit_exceeded, counted in argos_ratelimit_rejections_total")
  void rateLimit() {
    AtomicLong nanos = new AtomicLong();
    WsConnection c =
        new WsConnection(
            "c1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            Role.VIEWER,
            transport,
            Runnable::run,
            bus::outbox,
            new SubscribeBucket(nanos::get),
            clock.millis());
    for (int i = 0; i < 20; i++) {
      manager.onFrame(c, subscribe("s1", "counter", "{\"app\":3}"));
      assertThat(last().get("op").asString()).isEqualTo("ack");
    }
    manager.onFrame(c, subscribe("s1", "counter", "{\"app\":3}"));
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.LIMIT_EXCEEDED);
    assertThat(last().get("message").asString()).contains("rate limited");
    manager.onFrame(c, "{\"op\":\"unsubscribe\",\"id\":\"s1\"}");
    manager.onFrame(c, "{\"op\":\"ping\",\"t\":5}");
    assertThat(last().get("op").asString()).as("ping takes no token").isEqualTo("pong");
    assertThat(c.subscriptionCount()).as("unsubscribe takes no token").isZero();
    nanos.addAndGet(SubscribeBucket.REFILL_NANOS);
    manager.onFrame(c, subscribe("s1", "counter", "{\"app\":3}"));
    assertThat(last().get("op").asString()).isEqualTo("ack");
    assertThat(
            registry
                .get("argos.ratelimit.rejections")
                .tag("scope", "ws_subscribe")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "T-API-007b ping -> pong {t, server_t}; admin needs ADMIN (forbidden for a viewer), alert"
          + " and admin are acknowledged without data")
  void pingAndRoles() {
    WsConnection viewer = connection(Role.VIEWER);
    manager.onFrame(viewer, "{\"op\":\"ping\",\"t\":1729645323456}");
    JsonNode pong = last();
    assertThat(pong.get("op").asString()).isEqualTo("pong");
    assertThat(pong.get("t").asLong()).isEqualTo(1729645323456L);
    assertThat(pong.get("server_t").asString()).isEqualTo(ServerFrames.time(clock.millis()));
    manager.onFrame(viewer, subscribe("a1", "admin", "{\"change_id\":\"c-1\"}"));
    assertThat(last().get("code").asString()).isEqualTo(ServerFrames.FORBIDDEN);
    manager.onFrame(viewer, subscribe("al", "alert", "{\"env\":\"prod\"}"));
    assertThat(last().get("op").asString()).isEqualTo("ack");
    WsConnection admin = connection(Role.ADMIN);
    manager.onFrame(admin, subscribe("a1", "admin", "{\"change_id\":\"c-1\"}"));
    assertThat(last().get("op").asString()).isEqualTo("ack");
    assertThat(SubscriptionManager.authorize(Role.OPERATOR, Topic.ADMIN)).isFalse();
    assertThat(SubscriptionManager.authorize(Role.VIEWER, Topic.OVERVIEW)).isTrue();
    assertThat(SubscriptionManager.authorize(Role.PUBLIC, Topic.XLOG)).isFalse();
  }

  @Test
  @DisplayName(
      "T-API-007b a demotion ends only the subscriptions the new role does not allow: error"
          + " forbidden for admin, xlog stays")
  void demotion() {
    WsConnection c = connection(Role.ADMIN);
    manager.onFrame(c, subscribe("x", "xlog", "{\"app\":3}"));
    manager.onFrame(c, subscribe("a", "admin", "{}"));
    assertThat(c.subscriptionCount()).isEqualTo(2);
    manager.roleChanged(c, Role.VIEWER);
    assertThat(c.role()).isEqualTo(Role.VIEWER);
    assertThat(c.subscriptionCount()).isEqualTo(1);
    assertThat(c.subscription("x")).isNotNull();
    JsonNode error = last();
    assertThat(error.get("op").asString()).isEqualTo("error");
    assertThat(error.get("id").asString()).isEqualTo("a");
    assertThat(error.get("code").asString()).isEqualTo(ServerFrames.FORBIDDEN);
    assertThat(bus.subscriptions(Topic.ADMIN)).isZero();
    assertThat(transport.closes).isEmpty();
  }
}
