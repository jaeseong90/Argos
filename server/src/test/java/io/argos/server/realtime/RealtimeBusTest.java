/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static io.argos.server.realtime.BusFixture.point;
import static io.argos.server.realtime.BusFixture.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.MetricPoint.Series;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.realtime.BusFixture.Collector;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.argos.server.session.AgentSession;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.session.api.SessionContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The realtime bus (12 §3.7) with the per-connection backpressure of 14 §5.3: slow subscribers lose
 * their oldest {@code xlog} pushes (announced by {@code notice backpressure} and {@code dropped}),
 * latest-only topics keep one push, a fast subscriber is unaffected and the ingest side never
 * blocks. The bus rounds are driven by hand ({@code bundleTick}, {@code periodicTick}).
 */
@Tag("req:F-S03")
class RealtimeBusTest {

  private static final int APP = 3;
  private static final String ENV = "prod";

  private final TestClock clock = new TestClock(T0 + 3_000);
  private final Sources sources = new Sources().app(APP, ENV, 1_000).name(APP, 17, "GET /a");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RealtimeBus bus = BusFixture.bus(sources, clock, registry);

  private TopicRequest.Xlog xlog() {
    return new TopicRequest.Xlog(
        APP, null, io.argos.server.api.query.QueryLimits.TxKind.HTTP, Set.of(), Set.of());
  }

  /** Publishes {@code n} points with consecutive tx ids from {@code firstTx}, then one round. */
  private void round(long firstTx, int n) {
    List<XlogPoint> points = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      points.add(point(firstTx + i, clock.millis() - 10, 50, false, 17));
    }
    bus.publishXlog(session(APP, ENV, 12, clock.millis()), points);
    bus.bundleTick(clock.millis());
    clock.advance(RealtimeBus.BUNDLE_MS);
  }

  private static List<String> txIds(List<JsonNode> frames) {
    List<String> out = new ArrayList<>();
    for (JsonNode f : frames) {
      if ("push".equals(f.get("op").asString())) {
        f.get("data").get("tx_id").forEach(n -> out.add(n.asString()));
      }
    }
    return out;
  }

  private double dropped(String reason) {
    return registry.get("argos.realtime.dropped").tag("reason", reason).counter().count();
  }

  @Test
  @DisplayName(
      "T-SV-021a a slow subscriber loses its oldest xlog pushes first; notice backpressure and the"
          + " next push's dropped count them")
  void slowSubscriberDropsOldest() {
    Collector slow = new Collector();
    bus.subscribe("s1", xlog(), slow.outbox);
    // 60 rounds of 2,000 points: each push is about 70 KB, far more than the 1 MiB budget.
    int rounds = 60;
    int perRound = 2_000;
    for (int r = 0; r < rounds; r++) {
      round(1_000_000L * (r + 1), perRound);
    }
    long droppedPoints = slow.outbox.droppedPoints();
    assertThat(droppedPoints).isPositive();
    assertThat(slow.outbox.queuedBytes()).isLessThanOrEqualTo(SubscriberOutbox.BUDGET_BYTES);

    List<JsonNode> frames = slow.drain();
    JsonNode notice = frames.get(0);
    assertThat(notice.get("op").asString()).isEqualTo("notice");
    assertThat(notice.get("code").asString()).isEqualTo("backpressure");
    assertThat(notice.get("topic").asString()).isEqualTo("xlog");
    assertThat(notice.get("dropped").asLong()).isEqualTo(droppedPoints);
    JsonNode firstPush = frames.get(1);
    assertThat(firstPush.get("dropped").asLong()).isEqualTo(droppedPoints);
    assertThat(frames.subList(2, frames.size()))
        .allSatisfy(f -> assertThat(f.get("dropped").asLong()).isZero());

    // The newest rounds survived, the oldest went: the kept pushes are a suffix of the sequence.
    List<String> kept = txIds(frames);
    int keptRounds = kept.size() / perRound;
    assertThat(kept).hasSize(keptRounds * perRound);
    assertThat(keptRounds * perRound + droppedPoints).isEqualTo((long) rounds * perRound);
    assertThat(kept.get(0)).isEqualTo(Long.toString(1_000_000L * (rounds - keptRounds + 1)));
    assertThat(kept.get(kept.size() - 1))
        .isEqualTo(Long.toString(1_000_000L * rounds + perRound - 1));
    assertThat(slow.droppedHook.stream().mapToLong(Long::longValue).sum()).isEqualTo(droppedPoints);
    assertThat(dropped("backpressure")).isZero(); // the test's own outbox hook, not the bus's
  }

  @Test
  @DisplayName("T-SV-021b a fast subscriber of the same topic receives every point, no drop")
  void fastSubscriberUnaffected() {
    Collector slow = new Collector();
    Collector fast = new Collector();
    bus.subscribe("slow", xlog(), slow.outbox);
    bus.subscribe("fast", xlog(), fast.outbox);
    List<String> received = new ArrayList<>();
    for (int r = 0; r < 60; r++) {
      round(1_000_000L * (r + 1), 2_000);
      List<JsonNode> frames = fast.drain();
      assertThat(frames).allSatisfy(f -> assertThat(f.get("op").asString()).isEqualTo("push"));
      assertThat(frames).allSatisfy(f -> assertThat(f.get("dropped").asLong()).isZero());
      received.addAll(txIds(frames));
    }
    assertThat(received).hasSize(60 * 2_000);
    assertThat(fast.outbox.droppedPoints()).isZero();
    assertThat(slow.outbox.droppedPoints()).isPositive();
  }

  @Test
  @DisplayName(
      "T-SV-021c counter, active and overview keep only the newest push of a slow subscriber")
  void latestOnlyTopics() {
    AgentSession s = BusFixture.agentSession(APP, ENV, 12, clock.millis());
    sources.sessions.put(APP, List.of(s));
    Collector slow = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    bus.subscribe("c", new TopicRequest.Counter(APP, null, kind), slow.outbox);
    bus.subscribe("a", new TopicRequest.Active(APP, null, kind, Set.of()), slow.outbox);
    bus.subscribe("o", new TopicRequest.Overview(null, kind), slow.outbox);
    bus.publishActive(session(APP, ENV, 12, clock.millis()), null);
    long lastT = 0;
    for (int w = 0; w < 4; w++) {
      BusFixture.snapshot(s, clock.millis(), 10 + w);
      round(10_000L * (w + 1), 3);
      clock.advance(5_000);
      bus.periodicTick(clock.millis());
      lastT = clock.millis();
    }
    List<JsonNode> frames = slow.drain();
    assertThat(frames).hasSize(3);
    assertThat(frames)
        .extracting(f -> f.get("id").asString())
        .containsExactlyInAnyOrder("c", "a", "o");
    String last = ServerFrames.time(lastT);
    assertThat(frames).allSatisfy(f -> assertThat(f.get("t").asString()).isEqualTo(last));
    Map<String, JsonNode> byId = new java.util.HashMap<>();
    frames.forEach(f -> byId.put(f.get("id").asString(), f.get("data")));
    JsonNode counter = byId.get("c");
    assertThat(counter.get("active").asLong()).isEqualTo(13);
    assertThat(counter.get("per_instance").get(0).get("state").asString()).isEqualTo("UP");
    assertThat(byId.get("a").get("per_instance").get(0).get("count").asLong()).isEqualTo(13);
    assertThat(byId.get("a").get("per_instance").get(0).get("state").asString()).isEqualTo("UP");
    assertThat(byId.get("o").get("apps").get(0).get("app_id").asInt()).isEqualTo(APP);
    assertThat(byId.get("o").get("apps").get(0).get("active").asLong()).isEqualTo(13);
  }

  @Test
  @DisplayName(
      "T-SV-021d publishXlog never blocks: a stuck subscriber stalls only the bus thread, a full"
          + " queue drops and counts")
  void publisherNeverBlocks() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch stuck = new CountDownLatch(1);
    SubscriberOutbox blocking =
        new SubscriberOutbox(
            () -> {
              stuck.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            n -> {});
    bus.subscribe("s1", xlog(), blocking);
    SessionContext ctx = session(APP, ENV, 12, clock.millis());
    bus.publishXlog(ctx, List.of(point(1, clock.millis(), 5, false, 17)));
    Thread busThread = new Thread(() -> bus.bundleTick(clock.millis()), "test-bus");
    busThread.start();
    assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();
    // The bus thread is stuck in the subscriber; ingest calls still return at once and, past the
    // queue budget, drop and count.
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          List<XlogPoint> batch = new ArrayList<>();
          for (int i = 0; i < 1_000; i++) {
            batch.add(point(10 + i, clock.millis(), 5, false, 17));
          }
          for (int i = 0; i < 150; i++) {
            bus.publishXlog(ctx, batch);
          }
        });
    assertThat(bus.pendingPoints()).isLessThanOrEqualTo(XlogBundler.MAX_PENDING_POINTS);
    assertThat(dropped("bus_full")).isGreaterThanOrEqualTo(50_000);
    release.countDown();
    busThread.join(5_000);
    assertThat(busThread.isAlive()).isFalse();
  }

  @Test
  @Tag("req:D-15")
  @DisplayName(
      "T-SV-015b the bus does not publish a point more than 60 s older than its receipt (counted)")
  void latePointsNotPublished() {
    Collector c = new Collector();
    bus.subscribe("s1", xlog(), c.outbox);
    long now = clock.millis();
    bus.publishXlog(
        session(APP, ENV, 12, now),
        List.of(
            point(1, now - 60_001, 5, false, 17),
            point(2, now - 60_000, 5, false, 17),
            point(3, now - 90_000, 5, false, 17)));
    bus.bundleTick(now);
    assertThat(txIds(c.drain())).containsExactly("2");
    assertThat(dropped("late")).isEqualTo(2.0);
  }

  @Test
  @DisplayName("unsubscribe stops the pushes and forgets a waiting latest-only push")
  void cancelStops() {
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    RealtimeBus.BusSubscription x = bus.subscribe("x", xlog(), c.outbox);
    RealtimeBus.BusSubscription k =
        bus.subscribe("k", new TopicRequest.Counter(APP, null, kind), c.outbox);
    assertThat(bus.subscriptions(Topic.XLOG)).isEqualTo(1);
    round(1, 2);
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    assertThat(c.outbox.size()).isEqualTo(2);
    k.cancel();
    k.cancel();
    assertThat(c.drain()).extracting(f -> f.get("id").asString()).containsExactly("x");
    x.cancel();
    round(100, 2);
    assertThat(c.drain()).isEmpty();
    assertThat(bus.subscriptions(Topic.XLOG)).isZero();
    assertThat(bus.subscriptions(Topic.COUNTER)).isZero();
    assertThat(registry.get("argos.realtime.subscribers").tag("topic", "xlog").gauge().value())
        .isZero();
  }

  @Test
  @DisplayName("a subscription for an unknown application or another env is invalid_params")
  void subscribeValidatesApplication() {
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    assertThatThrownBy(() -> bus.subscribe("s", new TopicRequest.Counter(99, null, kind), c.outbox))
        .isInstanceOf(InvalidTopicParams.class)
        .hasMessage("app: unknown");
    assertThatThrownBy(
            () -> bus.subscribe("s", new TopicRequest.Counter(APP, "stage", kind), c.outbox))
        .isInstanceOf(InvalidTopicParams.class)
        .hasMessage("env: not_the_application_env");
    bus.subscribe("s", new TopicRequest.Counter(APP, ENV, kind), c.outbox);
    bus.subscribe("al", new TopicRequest.Alert(null, null), c.outbox);
    bus.subscribe("ad", new TopicRequest.Admin(null, null), c.outbox);
    assertThat(bus.subscriptions(Topic.ALERT)).isEqualTo(1);
    assertThat(bus.subscriptions(Topic.ADMIN)).isEqualTo(1);
  }

  @Test
  @Tag("req:F-S28")
  @DisplayName(
      "counter per_instance carries the live state and keeps an instance that lost its session"
          + " with the state of its last transition")
  void instanceStates() {
    AgentSession live = BusFixture.agentSession(APP, ENV, 12, clock.millis());
    sources.sessions.put(APP, List.of(live));
    sources.states.put(12, "WARN");
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    bus.subscribe("c", new TopicRequest.Counter(APP, null, kind), c.outbox);
    bus.subscribe("o", new TopicRequest.Overview(ENV, kind), c.outbox);
    bus.onInstanceState(
        new InstanceStateChange(
            14,
            APP,
            ENV,
            InstanceState.UP,
            InstanceState.STALE,
            Set.of("session_lost"),
            Instant.ofEpochMilli(clock.millis())));
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    List<JsonNode> frames = c.drain();
    assertThat(frames).hasSize(2);
    // The periodic tick walks the topic keys in hash order: pick each push by its subscription id.
    JsonNode counter = byId(frames, "c");
    JsonNode overview = byId(frames, "o");
    JsonNode rows = counter.get("data").get("per_instance");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("instance_id").asInt()).isEqualTo(12);
    assertThat(rows.get(0).get("state").asString()).isEqualTo("WARN");
    assertThat(rows.get(1).get("instance_id").asInt()).isEqualTo(14);
    assertThat(rows.get(1).get("state").asString()).isEqualTo("STALE");
    assertThat(rows.get(1).get("active").isNull()).isTrue();
    JsonNode data = counter.get("data");
    assertThat(data.get("tps").asDouble()).isZero();
    assertThat(data.get("p95_ms").isNull()).isTrue();
    assertThat(data.get("heap_used").isNull()).isTrue();
    assertThat(data.get("gc_pause_ms").isNull()).isTrue();
    assertThat(data.get("cpu").isNull()).isTrue();
    assertThat(data.get("active").isNull()).isTrue(); // no snapshot yet: unknown, not 0 (D-23)
    JsonNode app = overview.get("data").get("apps").get(0);
    assertThat(app.get("status").asString()).isEqualTo("STALE");
    assertThat(app.get("firing").isNull()).isTrue();
  }

  @Test
  @Tag("req:D-23")
  @DisplayName(
      "T-SV-020f counter push carries heap_used, gc_pause_ms and cpu of the window's metric"
          + " samples; samples of an application without a counter subscription are dropped")
  void counterJvmValues() {
    long now = clock.millis();
    // Before any counter subscription: dropped at once, never counted into a window.
    bus.publishMetrics(
        session(APP, ENV, 12, now), List.of(new MetricPoint(Series.HEAP_USED, T0, 1_000)));
    bus.bundleTick(now);
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    bus.subscribe("c", new TopicRequest.Counter(APP, null, kind), c.outbox);
    bus.publishMetrics(
        session(APP, ENV, 12, now),
        List.of(
            new MetricPoint(Series.HEAP_USED, T0, 5_000_000),
            new MetricPoint(Series.CPU, T0, 0.25),
            new MetricPoint(Series.GC_PAUSE, T0, 0.002)));
    bus.publishMetrics(
        session(APP, ENV, 13, now),
        List.of(
            new MetricPoint(Series.HEAP_USED, T0, 7_000_000),
            new MetricPoint(Series.CPU, T0, 0.75)));
    bus.bundleTick(now);
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    JsonNode data = byId(c.drain(), "c").get("data");
    assertThat(data.get("heap_used").asDouble()).isEqualTo(12_000_000.0);
    assertThat(data.get("gc_pause_ms").asDouble()).isEqualTo(2.0);
    assertThat(data.get("cpu").asDouble()).isEqualTo(0.5);
    assertThat(bus.metricsDropped()).isZero();

    // The next window has no samples: null again (D-23), not 0 or the previous value.
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    JsonNode next = byId(c.drain(), "c").get("data");
    assertThat(next.get("heap_used").isNull()).isTrue();
    assertThat(next.get("gc_pause_ms").isNull()).isTrue();
    assertThat(next.get("cpu").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-020f JVM samples dropped by the realtime bus (beyond the batch bound, outside the open"
          + " window) count as argos_realtime_dropped_total{reason=metric} (C-220 (9))")
  void metricDropsCounted() {
    long now = clock.millis();
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    bus.subscribe("c", new TopicRequest.Counter(APP, null, kind), c.outbox);
    assertThat(dropped("metric")).isZero();
    // Beyond the bound of one batch: the excess is dropped at once.
    List<MetricPoint> many = new ArrayList<>();
    for (int i = 0; i < RealtimeBus.MAX_METRIC_POINTS + 7; i++) {
      many.add(new MetricPoint(Series.HEAP_USED, T0, 1_000 + i));
    }
    bus.publishMetrics(session(APP, ENV, 12, now), many);
    assertThat(dropped("metric")).isEqualTo(7.0);
    bus.bundleTick(now);
    assertThat(dropped("metric")).isEqualTo(7.0);
    // Outside the open window (an hour old): excluded when the bus thread drains the queue.
    bus.publishMetrics(
        session(APP, ENV, 12, now),
        List.of(
            new MetricPoint(Series.HEAP_USED, now - 3_600_000, 1),
            new MetricPoint(Series.CPU, now - 3_600_000, 0.5)));
    bus.bundleTick(now);
    assertThat(dropped("metric")).isEqualTo(9.0);
    assertThat(bus.metricsDropped()).isEqualTo(9);
    // The other reasons are untouched.
    assertThat(dropped("counter_late")).isZero();
    assertThat(dropped("late")).isZero();
  }

  @Test
  @Tag("req:F-S06")
  @DisplayName(
      "T-SV-020h publishAlert pushes each alert transition once to the matching alert"
          + " subscriptions as a control frame (never coalesced like latest-only pushes); a server"
          + " self alert reaches only the unfiltered subscription (14 §5.2, WO-25)")
  void alertPushIsControlFrame() {
    sources.app(4, ENV, 1_000);
    Collector all = new Collector();
    Collector mine = new Collector();
    Collector other = new Collector();
    Collector otherEnv = new Collector();
    bus.subscribe("all", new TopicRequest.Alert(null, null), all.outbox);
    bus.subscribe("mine", new TopicRequest.Alert(ENV, APP), mine.outbox);
    bus.subscribe("other", new TopicRequest.Alert(null, 4), other.outbox);
    bus.subscribe("env", new TopicRequest.Alert("stage", null), otherEnv.outbox);
    long t1 = clock.millis();
    bus.publishAlert(APP, ENV, t1, "{\"event_id\":\"e1\",\"state\":\"FIRING\"}");
    long t2 = t1 + 1_000;
    bus.publishAlert(APP, ENV, t2, "{\"event_id\":\"e1\",\"state\":\"RESOLVED\"}");
    bus.publishAlert(null, null, t2, "{\"event_id\":\"s1\",\"state\":\"FIRING\"}");

    // Two transitions of the same subscription both stay queued (a latest-only push would keep
    // only the newest), in publication order, each exactly once.
    List<JsonNode> m = mine.drain();
    assertThat(m).hasSize(2);
    assertThat(m)
        .allSatisfy(
            f -> {
              assertThat(f.get("op").asString()).isEqualTo("push");
              assertThat(f.get("topic").asString()).isEqualTo("alert");
              assertThat(f.get("id").asString()).isEqualTo("mine");
            });
    assertThat(m.get(0).get("t").asString()).isEqualTo(ServerFrames.time(t1));
    assertThat(m.get(0).get("data").get("event").get("state").asString()).isEqualTo("FIRING");
    assertThat(m.get(1).get("t").asString()).isEqualTo(ServerFrames.time(t2));
    assertThat(m.get(1).get("data").get("event").get("state").asString()).isEqualTo("RESOLVED");

    List<JsonNode> a = all.drain();
    assertThat(a)
        .extracting(f -> f.get("data").get("event").get("event_id").asString())
        .containsExactly("e1", "e1", "s1");
    assertThat(other.drain()).isEmpty();
    assertThat(otherEnv.drain()).isEmpty();

    // A full outbox trims xlog pushes, never the alert control frames.
    Collector tiny = new Collector(256);
    bus.subscribe("tiny", new TopicRequest.Alert(null, null), tiny.outbox);
    for (int i = 0; i < 5; i++) {
      bus.publishAlert(APP, ENV, t2 + i, "{\"event_id\":\"x" + i + "\",\"state\":\"FIRING\"}");
    }
    assertThat(tiny.drain()).hasSize(5);
    assertThat(tiny.outbox.droppedPoints()).isZero();
  }

  @Test
  @Tag("req:C-212")
  @DisplayName(
      "T-SV-020i overview rows carry firing null until the alert engine reports, then"
          + " {critical, warn} from updateFiring (0/0 for an application without active alerts;"
          + " C-212 (2), WO-25)")
  void overviewFiringFromEngine() {
    sources.app(4, ENV, 1_000);
    Collector c = new Collector();
    var kind = io.argos.server.api.query.QueryLimits.TxKind.HTTP;
    bus.subscribe("o", new TopicRequest.Overview(null, kind), c.outbox);
    bus.publishActive(session(APP, ENV, 12, clock.millis()), null);
    bus.publishActive(session(4, ENV, 21, clock.millis()), null);
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    JsonNode before = byId(c.drain(), "o").get("data").get("apps");
    assertThat(before).hasSize(2);
    assertThat(before).allSatisfy(row -> assertThat(row.get("firing").isNull()).isTrue());

    Map<Integer, int[]> counts = new java.util.HashMap<>();
    counts.put(APP, new int[] {2, 1});
    bus.updateFiring(counts);
    counts.get(APP)[0] = 99; // the bus keeps its own copy
    counts.put(4, new int[] {7, 7});
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    JsonNode rows = byId(c.drain(), "o").get("data").get("apps");
    assertThat(rows).hasSize(2);
    JsonNode app3 = rows.get(0);
    JsonNode app4 = rows.get(1);
    assertThat(app3.get("app_id").asInt()).isEqualTo(APP);
    assertThat(app3.get("firing").get("critical").asInt()).isEqualTo(2);
    assertThat(app3.get("firing").get("warn").asInt()).isEqualTo(1);
    assertThat(app3.get("firing").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("critical", "warn");
    assertThat(app4.get("app_id").asInt()).isEqualTo(4);
    assertThat(app4.get("firing").get("critical").asInt()).isZero();
    assertThat(app4.get("firing").get("warn").asInt()).isZero();

    // An engine report without any active alert: every row 0/0, no longer null.
    bus.updateFiring(Map.of());
    clock.advance(5_000);
    bus.periodicTick(clock.millis());
    assertThat(byId(c.drain(), "o").get("data").get("apps"))
        .allSatisfy(
            row -> {
              assertThat(row.get("firing").get("critical").asInt()).isZero();
              assertThat(row.get("firing").get("warn").asInt()).isZero();
            });
  }

  @Test
  @DisplayName("names are looked up once per service and cached; unknown names are retried")
  void namesCached() {
    Collector c = new Collector();
    bus.subscribe("s1", xlog(), c.outbox);
    long now = clock.millis();
    List<XlogPoint> pts = List.of(point(1, now, 5, false, 17), point(2, now, 5, false, 18));
    for (int i = 0; i < 3; i++) {
      bus.publishXlog(session(APP, ENV, 12, now), pts);
      bus.bundleTick(now);
    }
    long nameCalls = sources.lookups.stream().filter(l -> l.startsWith("names:")).count();
    assertThat(nameCalls).isEqualTo(1); // 18 is a cached miss for 5 s
    List<JsonNode> frames = c.drain();
    assertThat(frames.get(0).get("dict").get("service").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("17");
    assertThat(frames.get(1).get("dict").get("service").isEmpty()).isTrue();
  }

  @Test
  @DisplayName("T-SV-020d the started bus pushes a published point within its 100 ms rounds")
  void startedBusPushes() throws Exception {
    AtomicInteger wakeUps = new AtomicInteger();
    CountDownLatch pushed = new CountDownLatch(1);
    SubscriberOutbox outbox =
        new SubscriberOutbox(
            () -> {
              wakeUps.incrementAndGet();
              pushed.countDown();
            },
            n -> {});
    bus.subscribe("s1", xlog(), outbox);
    bus.start();
    try {
      bus.publishXlog(
          session(APP, ENV, 12, clock.millis()), List.of(point(77, clock.millis(), 5, true, 17)));
      assertThat(pushed.await(5, TimeUnit.SECONDS)).isTrue();
      JsonNode push = BusFixture.JSON.readTree(outbox.poll());
      assertThat(push.get("data").get("tx_id").get(0).asString()).isEqualTo("77");
      assertThat(push.get("data").get("status").get(0).asInt()).isEqualTo(2);
    } finally {
      bus.stop();
    }
    assertThat(bus.isRunning()).isFalse();
  }

  private static JsonNode byId(List<JsonNode> frames, String id) {
    return frames.stream()
        .filter(f -> id.equals(f.get("id").asString()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no push for subscription " + id + ": " + frames));
  }
}
