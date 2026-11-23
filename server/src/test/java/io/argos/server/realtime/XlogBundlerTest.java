/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static io.argos.server.realtime.BusFixture.point;
import static io.argos.server.realtime.BusFixture.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.TransactionKind;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.realtime.BusFixture.Collector;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code xlog} topic (12 §3.7, 14 §5.2, §5.3): 100 ms bundles per application, the subscription
 * filters (kind, service, instance, env), the push columns (equal lengths, unsigned decimal ids
 * C-175 (1), the REST status rule), {@code dict.service} and the strict parameters.
 */
@Tag("req:F-S03")
class XlogBundlerTest {

  private static final int APP = 3;
  private static final long BIG = -2L; // 18446744073709551614 unsigned

  private final TestClock clock = new TestClock(T0 + 3_000);
  private final Sources sources =
      new Sources()
          .app(APP, "prod", 1_000)
          .app(4, "prod", 1_000)
          .name(APP, 17, "GET /a")
          .name(APP, BIG, "GET /big");
  private final RealtimeBus bus = BusFixture.bus(sources, clock, new SimpleMeterRegistry());

  @Test
  @DisplayName("T-SV-020d one drain is one bundle per application with the points in arrival order")
  void bundlesPerApplication() {
    XlogBundler bundler = new XlogBundler();
    long now = clock.millis();
    bundler.offer(session(APP, "prod", 12, now), List.of(point(1, now, 5, false, 17)));
    bundler.offer(session(4, "prod", 20, now), List.of(point(2, now, 5, false, 17)));
    bundler.offer(
        session(APP, "prod", 13, now),
        List.of(point(3, now, 5, false, 17), point(4, now, 5, false, 17)));
    List<XlogBundle> bundles = bundler.drain(now + 100);
    assertThat(bundles).extracting(XlogBundle::appId).containsExactly(APP, 4);
    assertThat(bundles.get(0).points()).extracting(BusPoint::txId).containsExactly(1L, 3L, 4L);
    assertThat(bundles.get(0).points())
        .extracting(BusPoint::instanceId)
        .containsExactly(12, 13, 13);
    assertThat(bundles.get(0).createdAtMs()).isEqualTo(now + 100);
    assertThat(bundler.drain(now + 200)).isEmpty();
    assertThat(bundler.pending()).isZero();
  }

  @Test
  @DisplayName(
      "an xlog push has seven columns of equal length, unsigned decimal ids, ts in ms and the"
          + " status rule of the REST XLog")
  void columns() {
    Collector c = new Collector();
    bus.subscribe("s1", req(TxKind.HTTP, Set.of(), Set.of()), c.outbox);
    long now = clock.millis();
    bus.publishXlog(
        session(APP, "prod", 12, now),
        List.of(
            point(BIG, now - 5, 999, false, BIG),
            point(Long.MIN_VALUE, now - 4, 1_000, false, 17),
            point(7, now - 3, 1, true, 17)));
    bus.bundleTick(now);
    List<JsonNode> frames = c.drain();
    assertThat(frames).hasSize(1);
    JsonNode push = frames.get(0);
    assertThat(push.get("op").asString()).isEqualTo("push");
    assertThat(push.get("id").asString()).isEqualTo("s1");
    assertThat(push.get("topic").asString()).isEqualTo("xlog");
    assertThat(push.get("t").asString()).isEqualTo(ServerFrames.time(now));
    assertThat(push.get("dropped").asLong()).isZero();
    JsonNode d = push.get("data");
    for (String col :
        List.of("tx_id", "ts", "elapsed_ms", "status", "instance_id", "service_id", "kind")) {
      assertThat(d.get(col).size()).as(col).isEqualTo(3);
    }
    assertThat(texts(d.get("tx_id")))
        .containsExactly("18446744073709551614", "9223372036854775808", "7");
    assertThat(d.get("tx_id").get(0).isString()).isTrue();
    assertThat(texts(d.get("service_id"))).containsExactly("18446744073709551614", "17", "17");
    assertThat(d.get("ts").get(0).asLong()).isEqualTo(now - 5);
    assertThat(d.get("elapsed_ms").get(1).asInt()).isEqualTo(1_000);
    // 999 < 1,000 normal; 1,000 >= 1,000 slow; error wins over a short elapsed.
    assertThat(ints(d.get("status"))).containsExactly(0, 1, 2);
    assertThat(ints(d.get("instance_id"))).containsExactly(12, 12, 12);
    assertThat(ints(d.get("kind"))).containsExactly(1, 1, 1);
    assertThat(push.get("dict").get("service").get("18446744073709551614").asString())
        .isEqualTo("GET /big");
    assertThat(push.get("dict").get("service").get("17").asString()).isEqualTo("GET /a");
    assertThat(d.properties()).hasSize(7);
  }

  @Test
  @DisplayName("the slow threshold is the application's slow_threshold_ms")
  void slowThresholdOfApplication() {
    sources.app(APP, "prod", 200);
    Collector c = new Collector();
    bus.subscribe("s1", req(TxKind.HTTP, Set.of(), Set.of()), c.outbox);
    long now = clock.millis();
    bus.publishXlog(
        session(APP, "prod", 12, now),
        List.of(point(1, now, 199, false, 17), point(2, now, 200, false, 17)));
    bus.bundleTick(now);
    assertThat(ints(c.drain().get(0).get("data").get("status"))).containsExactly(0, 1);
  }

  @Test
  @DisplayName("kind, service and instance filters select the points of a subscription")
  void filters() {
    Collector all = new Collector();
    Collector batch = new Collector();
    Collector service = new Collector();
    Collector instance = new Collector();
    Collector everyKind = new Collector();
    bus.subscribe("all", req(TxKind.HTTP, Set.of(), Set.of()), all.outbox);
    bus.subscribe("batch", req(TxKind.BATCH, Set.of(), Set.of()), batch.outbox);
    bus.subscribe("svc", req(TxKind.HTTP, Set.of(BIG), Set.of()), service.outbox);
    bus.subscribe("inst", req(TxKind.HTTP, Set.of(), Set.of(13)), instance.outbox);
    bus.subscribe("any", req(TxKind.ALL, Set.of(), Set.of()), everyKind.outbox);
    long now = clock.millis();
    bus.publishXlog(
        session(APP, "prod", 12, now),
        List.of(
            point(1, now, 5, TransactionKind.HTTP, 17),
            point(2, now, 5, TransactionKind.BATCH, 17),
            point(3, now, 5, TransactionKind.HTTP, BIG),
            point(4, now, 5, TransactionKind.MESSAGE, 17)));
    bus.publishXlog(
        session(APP, "prod", 13, now), List.of(point(5, now, 5, TransactionKind.HTTP, 17)));
    bus.publishXlog(
        session(4, "prod", 13, now), List.of(point(6, now, 5, TransactionKind.HTTP, 17)));
    bus.bundleTick(now);
    assertThat(tx(all)).containsExactly("1", "3", "5");
    JsonNode batchPush = push(batch);
    assertThat(texts(batchPush.get("data").get("tx_id"))).containsExactly("2");
    assertThat(tx(service)).containsExactly("3");
    assertThat(tx(instance)).containsExactly("5");
    assertThat(tx(everyKind)).containsExactly("1", "2", "3", "4", "5");
    assertThat(ints(batchPush.get("data").get("kind"))).containsExactly(2);
  }

  @Test
  @DisplayName("a subscription without matching points gets no push")
  void noEmptyPush() {
    Collector c = new Collector();
    bus.subscribe("s1", req(TxKind.BATCH, Set.of(), Set.of()), c.outbox);
    long now = clock.millis();
    bus.publishXlog(session(APP, "prod", 12, now), List.of(point(1, now, 5, false, 17)));
    bus.bundleTick(now);
    assertThat(c.drain()).isEmpty();
  }

  @Test
  @DisplayName(
      "dict.service names a service once per subscription and again after its push was dropped")
  void dictionaryOncePerSubscription() {
    Collector a = new Collector();
    Collector b = new Collector();
    bus.subscribe("a", req(TxKind.HTTP, Set.of(), Set.of()), a.outbox);
    long now = clock.millis();
    bus.publishXlog(session(APP, "prod", 12, now), List.of(point(1, now, 5, false, 17)));
    bus.bundleTick(now);
    bus.subscribe("b", req(TxKind.HTTP, Set.of(), Set.of()), b.outbox);
    bus.publishXlog(session(APP, "prod", 12, now), List.of(point(2, now, 5, false, 17)));
    bus.bundleTick(now);
    List<JsonNode> fa = a.drain();
    assertThat(fa.get(0).get("dict").get("service").get("17").asString()).isEqualTo("GET /a");
    assertThat(fa.get(1).get("dict").get("service").isEmpty()).isTrue();
    assertThat(push(b).get("dict").get("service").get("17").asString()).isEqualTo("GET /a");

    // A dropped push gives its names back: the next push carries them again.
    XlogSubscription s =
        new XlogSubscription(
            "x", req(TxKind.HTTP, Set.of(), Set.of()), new Collector(0).outbox, BusFixture.JSON);
    XlogBundle bundle =
        new XlogBundle(APP, "prod", now, List.of(bp(1, 17)), 1_000, Map.of(17L, "GET /a"));
    s.onBundle(bundle); // budget 0: dropped at once
    assertThat(s.takeDropped()).isEqualTo(1);
    Collector after = new Collector();
    XlogSubscription s2 =
        new XlogSubscription(
            "y", req(TxKind.HTTP, Set.of(), Set.of()), after.outbox, BusFixture.JSON);
    s2.onBundle(bundle);
    s2.dropped(0, Set.of(17L));
    s2.onBundle(bundle);
    List<JsonNode> frames = after.drain();
    assertThat(frames.get(1).get("dict").get("service").get("17").asString()).isEqualTo("GET /a");
  }

  @Test
  @DisplayName("a bundle of more than 5,000 matching points is split into pushes of 5,000")
  void splitLargeBundles() {
    Collector c = new Collector(Long.MAX_VALUE);
    XlogSubscription s =
        new XlogSubscription("x", req(TxKind.HTTP, Set.of(), Set.of()), c.outbox, BusFixture.JSON);
    List<BusPoint> points = new ArrayList<>();
    for (int i = 0; i < 12_001; i++) {
      points.add(bp(i, 17));
    }
    s.onBundle(new XlogBundle(APP, "prod", clock.millis(), points, 1_000, Map.of()));
    List<JsonNode> frames = c.drain();
    assertThat(frames)
        .extracting(f -> f.get("data").get("tx_id").size())
        .containsExactly(5_000, 5_000, 2_001);
  }

  @Test
  @DisplayName(
      "subscription parameters are strict: app required, known keys only, u64 service keys,"
          + " positive instances, kind HTTP by default")
  void strictParameters() {
    TopicRequest.Xlog x =
        (TopicRequest.Xlog)
            TopicRequest.parse(
                Topic.XLOG,
                Map.of(
                    "app",
                    3,
                    "env",
                    "prod",
                    "service",
                    List.of("18446744073709551615", 17),
                    "instance",
                    List.of(12)));
    assertThat(x.kind()).isEqualTo(TxKind.HTTP);
    assertThat(x.services()).containsExactlyInAnyOrder(-1L, 17L);
    assertThat(x.instances()).containsExactly(12);
    assertThat(TopicRequest.parse(Topic.XLOG, Map.of("app", 3, "kind", "batch")))
        .isEqualTo(new TopicRequest.Xlog(3, null, TxKind.BATCH, Set.of(), Set.of()));
    assertThat(TopicRequest.parse(Topic.OVERVIEW, null))
        .isEqualTo(new TopicRequest.Overview(null, TxKind.HTTP));
    assertThat(TopicRequest.parse(Topic.COUNTER, Map.of("app", 3.0)))
        .isEqualTo(new TopicRequest.Counter(3, null, TxKind.HTTP));

    Map<Map<String, Object>, String> invalid = new HashMap<>();
    invalid.put(Map.of(), "app: required");
    invalid.put(Map.of("app", 0), "app: invalid_value");
    invalid.put(Map.of("app", "3"), "app: invalid_value");
    invalid.put(Map.of("app", 3.5), "app: invalid_value");
    invalid.put(Map.of("app", 3, "error", true), "error: unknown_parameter");
    invalid.put(Map.of("app", 3, "kind", "SQL"), "kind: unsupported_value");
    invalid.put(
        Map.of("app", 3, "service", List.of("18446744073709551616")), "service: invalid_value");
    invalid.put(Map.of("app", 3, "service", List.of("-1")), "service: invalid_value");
    invalid.put(Map.of("app", 3, "service", "17"), "service: invalid_value");
    invalid.put(Map.of("app", 3, "instance", List.of(0)), "instance: invalid_value");
    invalid.put(Map.of("app", 3, "env", ""), "env: invalid_value");
    invalid.put(Map.of("app", 3, "env", "x".repeat(33)), "env: invalid_value");
    List<Integer> many = new ArrayList<>();
    for (int i = 1; i <= TopicRequest.MAX_ARRAY_VALUES + 1; i++) {
      many.add(i);
    }
    invalid.put(Map.of("app", 3, "instance", many), "instance: too_many_values");
    invalid.forEach(
        (params, message) ->
            assertThatThrownBy(() -> TopicRequest.parse(Topic.XLOG, params))
                .as(params.toString())
                .isInstanceOf(InvalidTopicParams.class)
                .hasMessage(message));
    assertThatThrownBy(() -> TopicRequest.parse(Topic.ADMIN, Map.of("change_id", 5)))
        .hasMessage("change_id: invalid_value");
    assertThat(Topic.fromWire("xlog")).contains(Topic.XLOG);
    assertThat(Topic.fromWire("instance_state")).isEmpty();
  }

  private static BusPoint bp(long tx, long service) {
    return new BusPoint(tx, T0, 5, false, 12, service, 1);
  }

  private static TopicRequest.Xlog req(TxKind kind, Set<Long> services, Set<Integer> instances) {
    return new TopicRequest.Xlog(APP, null, kind, services, instances);
  }

  private static JsonNode push(Collector c) {
    List<JsonNode> frames = c.drain();
    assertThat(frames).hasSize(1);
    return frames.get(0);
  }

  private static List<String> tx(Collector c) {
    List<String> out = new ArrayList<>();
    for (JsonNode f : c.drain()) {
      out.addAll(texts(f.get("data").get("tx_id")));
    }
    return out;
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  private static List<Integer> ints(JsonNode array) {
    List<Integer> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asInt()));
    return out;
  }
}
