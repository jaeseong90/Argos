/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static io.argos.server.realtime.BusFixture.point;
import static io.argos.server.realtime.BusFixture.session;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionKind;
import io.argos.server.admin.command.CommandKind;
import io.argos.server.admin.command.CommandRouter;
import io.argos.server.admin.configchange.ConfigChangeService;
import io.argos.server.alert.engine.AlertPushEvent;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.alert.template.DeepLinks;
import io.argos.server.auth.Role;
import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.MetricPoint.Series;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.realtime.BusFixture.Collector;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.argos.server.realtime.WsFixture.RecordingTransport;
import io.argos.server.session.AgentSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed WebSocket frame vectors ({@value #RESOURCE}): every kind of server frame as the real
 * code writes it (the subscription logic, the bus, the outbox and {@link ServerFrames}: {@code
 * ack}, {@code push} of {@code xlog}, {@code counter}, {@code active}, {@code overview} and {@code
 * alert} (with its {@code reason}, C-228 (2)), {@code error} with each of the four codes, {@code
 * pong}, {@code notice backpressure}, {@code reauth}), and the client frames the server accepts.
 * The UI checks them with its own parsers ({@code ui/e2e/fixtures/wsServerFrames.test.ts}); this
 * test keeps the committed file equal to what the code writes now. After a deliberate change, copy
 * {@code build/ws-frame-vectors.generated.json} over the resource.
 */
@Tag("req:SEC-44")
@Tag("req:F-S03")
class WsFrameVectorsTest {

  static final String RESOURCE = "ws-frame-vectors.json";

  private static final int APP = 3;
  private static final JsonMapper PRETTY =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  /** One named frame. */
  private record Vector(String name, String frame) {}

  @Test
  @DisplayName(
      "T-API-007d the committed frame vectors equal the frames the server writes; every frame kind,"
          + " push topic and error code is present")
  void vectorsMatch() throws IOException {
    String generated = render(generate(), clientFrames());
    Path out = Path.of("build", "ws-frame-vectors.generated.json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, generated, StandardCharsets.UTF_8);
    String committed;
    try (InputStream in = WsFrameVectorsTest.class.getResourceAsStream(RESOURCE)) {
      assertThat(in).as(RESOURCE + " on the test class path").isNotNull();
      committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(committed.replace("\r\n", "\n"))
        .as("committed vectors = generated (" + out.toAbsolutePath() + ")")
        .isEqualTo(generated);

    JsonNode server = BusFixture.JSON.readTree(generated).get("server");
    List<String> ops = new ArrayList<>();
    List<String> pushTopics = new ArrayList<>();
    List<String> codes = new ArrayList<>();
    for (JsonNode v : server) {
      JsonNode f = BusFixture.JSON.readTree(v.get("frame").asString());
      String op = f.get("op").asString();
      ops.add(op);
      if (op.equals("push")) {
        pushTopics.add(f.get("topic").asString());
      } else if (op.equals("error")) {
        codes.add(f.get("code").asString());
      }
    }
    assertThat(ops).contains("ack", "push", "error", "pong", "notice", "reauth");
    assertThat(pushTopics).contains("xlog", "counter", "active", "overview", "alert", "admin");
    assertThat(codes)
        .contains(
            ServerFrames.FORBIDDEN,
            ServerFrames.LIMIT_EXCEEDED,
            ServerFrames.INVALID_PARAMS,
            ServerFrames.TOPIC_UNKNOWN);
  }

  @Test
  @DisplayName("T-API-007d the generated vectors do not change between runs (deterministic)")
  void deterministic() {
    assertThat(render(generate(), clientFrames())).isEqualTo(render(generate(), clientFrames()));
  }

  /** The client frames of the vectors: what the server accepts (each answered with ack or pong). */
  private static List<Vector> clientFrames() {
    return List.of(
        new Vector(
            "subscribe xlog",
            "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":\"xlog\",\"params\":{\"app\":3,"
                + "\"env\":\"prod\",\"kind\":\"HTTP\",\"service\":[\"17\"],\"instance\":[12]}}"),
        new Vector(
            "subscribe counter",
            "{\"op\":\"subscribe\",\"id\":\"s2\",\"topic\":\"counter\",\"params\":{\"app\":3}}"),
        new Vector(
            "subscribe active",
            "{\"op\":\"subscribe\",\"id\":\"s3\",\"topic\":\"active\",\"params\":{\"app\":3}}"),
        new Vector(
            "subscribe alert",
            "{\"op\":\"subscribe\",\"id\":\"s4\",\"topic\":\"alert\",\"params\":{\"env\":\"prod\"}}"),
        new Vector(
            "subscribe overview",
            "{\"op\":\"subscribe\",\"id\":\"s5\",\"topic\":\"overview\","
                + "\"params\":{\"env\":\"prod\"}}"),
        new Vector("unsubscribe", "{\"op\":\"unsubscribe\",\"id\":\"s1\"}"),
        new Vector("ping", "{\"op\":\"ping\",\"t\":1795305600123}"));
  }

  private static String render(List<Vector> server, List<Vector> client) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "description",
        "WebSocket frames of /ws (docs/14 §5.2) as the server writes them;"
            + " generated by WsFrameVectorsTest, do not edit");
    root.put("server", server.stream().map(WsFrameVectorsTest::entry).toList());
    root.put("client", client.stream().map(WsFrameVectorsTest::entry).toList());
    return PRETTY.writeValueAsString(root).replace("\r\n", "\n") + "\n";
  }

  private static Map<String, String> entry(Vector v) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("name", v.name());
    m.put("frame", v.frame());
    return m;
  }

  /** Runs the real subscription logic and bus on fixed data and a fixed clock. */
  private static List<Vector> generate() {
    TestClock clock = new TestClock(T0 + 3_000);
    Sources sources =
        new Sources()
            .app(APP, "prod", 1_000)
            .name(APP, 17, "GET /api/orders/{id}")
            .name(APP, -2L, "POST /api/pay");
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RealtimeBus bus = BusFixture.bus(sources, clock, meters);
    SubscriptionManager manager = new SubscriptionManager(bus, clock, meters);
    RecordingTransport transport = new RecordingTransport();
    WsConnection viewer =
        new WsConnection(
            "c1",
            new UUID(1, 1),
            new UUID(2, 2),
            Role.VIEWER,
            transport,
            Runnable::run,
            bus::outbox,
            WsFixture.unlimited(),
            clock.millis());
    List<Vector> out = new ArrayList<>();

    manager.onFrame(viewer, clientFrames().get(0).frame());
    out.add(new Vector("ack xlog", last(transport)));
    manager.onFrame(viewer, clientFrames().get(1).frame());
    out.add(new Vector("ack counter", last(transport)));
    manager.onFrame(viewer, clientFrames().get(2).frame());
    out.add(new Vector("ack active", last(transport)));
    manager.onFrame(viewer, clientFrames().get(4).frame());
    out.add(new Vector("ack overview", last(transport)));
    manager.onFrame(
        viewer,
        "{\"op\":\"subscribe\",\"id\":\"s6\",\"topic\":\"xlog\",\"params\":{\"app\":3,"
            + "\"kind\":\"ALL\"}}");

    // Instances 12 (UP) and 13 (WARN) of the application, with active snapshots.
    AgentSession up = BusFixture.agentSession(APP, "prod", 12, clock.millis());
    AgentSession warn = BusFixture.agentSession(APP, "prod", 13, clock.millis());
    sources.sessions.put(APP, List.of(up, warn));
    sources.states.put(13, "WARN");

    // xlog: normal, slow and error points; a u64 tx id above 2^63; services 17 and 2^64-2.
    long now = clock.millis();
    List<XlogPoint> points =
        List.of(
            point(TxIdsForVectors.TX1, now - 40, 35, false, 17),
            point(TxIdsForVectors.TX2, now - 30, 1_500, false, 17),
            point(-1L, now - 20, 220, true, -2L),
            point(TxIdsForVectors.TX3, now - 10, 4_000, TransactionKind.BATCH, 17));
    bus.publishXlog(session(APP, "prod", 12, now), points);
    bus.bundleTick(now);
    out.add(new Vector("push xlog (kind HTTP, service 17, instance 12)", find(transport, "s1")));
    out.add(new Vector("push xlog (kind ALL)", find(transport, "s6")));

    // counter, active, overview: three 5-second windows, the last push of each. The counter's JVM
    // values come from metric samples of both instances (ADR-0010 C-212 (1)).
    for (int w = 0; w < 3; w++) {
      BusFixture.snapshot(up, clock.millis(), 4 + w);
      BusFixture.snapshot(warn, clock.millis(), 2);
      long t = clock.millis();
      bus.publishMetrics(
          session(APP, "prod", 12, t),
          List.of(
              new MetricPoint(Series.HEAP_USED, t - 15, 268_435_456 + w),
              new MetricPoint(Series.GC_PAUSE, t - 15, 0.0042),
              new MetricPoint(Series.CPU, t - 15, 0.25)));
      bus.publishMetrics(
          session(APP, "prod", 13, t),
          List.of(
              new MetricPoint(Series.HEAP_USED, t - 15, 134_217_728),
              new MetricPoint(Series.CPU, t - 15, 0.5)));
      bus.publishXlog(
          session(APP, "prod", 12, t),
          List.of(
              point(TxIdsForVectors.TX1 + 10 + w, t - 15, 80 + w, false, 17),
              point(TxIdsForVectors.TX1 + 20 + w, t - 5, 300, w == 1, 17)));
      bus.bundleTick(t);
      clock.advance(5_000);
      bus.periodicTick(clock.millis());
    }
    out.add(new Vector("push counter", find(transport, "s2")));
    out.add(new Vector("push active", find(transport, "s3")));
    out.add(new Vector("push overview", find(transport, "s5")));

    // alert: the push of one stored transition as the alert engine writes it (AlertPushEvent),
    // with the transition's reason, the value of its alert_events row (ADR-0010 C-228 (2)).
    manager.onFrame(viewer, clientFrames().get(3).frame());
    out.add(new Vector("ack alert", last(transport)));
    long firedAt = clock.millis();
    UUID eventId = new UUID(0x0193_5a2b_7c00_7000L, 0x8000_0000_0000_0001L);
    bus.publishAlert(
        APP,
        "prod",
        firedAt,
        new AlertPushEvent(
                eventId,
                firedAt,
                APP,
                "shop",
                "prod",
                TargetType.INSTANCE.wire(),
                12L,
                "host-a:8080",
                new UUID(0x0193_5a2b_7c00_7000L, 0x8000_0000_0000_0002L),
                "Error rate high",
                "error_rate",
                "critical",
                "FIRING",
                0.12,
                0.05,
                0,
                "condition",
                DeepLinks.alert(
                    "https://argos.example.com",
                    APP,
                    "prod",
                    TargetType.INSTANCE,
                    12L,
                    Instant.ofEpochMilli(firedAt - 60_000),
                    eventId))
            .toJson());
    out.add(new Vector("push alert (FIRING, reason condition)", find(transport, "s4")));

    // admin (WO-26): an administrator's subscription to one command and the push of its state
    // transition as CommandRouter writes it (no result, no argument values).
    RecordingTransport adminTransport = new RecordingTransport();
    WsConnection admin =
        new WsConnection(
            "c2",
            new UUID(3, 3),
            new UUID(4, 4),
            Role.ADMIN,
            adminTransport,
            Runnable::run,
            bus::outbox,
            WsFixture.unlimited(),
            clock.millis());
    UUID commandId = UUID.fromString("0b9d6f4e-3c1a-4f2b-9a7e-5d8c2e1f0a11");
    manager.onFrame(
        admin,
        "{\"op\":\"subscribe\",\"id\":\"s6\",\"topic\":\"admin\",\"params\":"
            + "{\"command_id\":\""
            + commandId
            + "\"}}");
    out.add(new Vector("ack admin", last(adminTransport)));
    bus.publishAdmin(
        null,
        commandId.toString(),
        clock.millis(),
        CommandRouter.pushData(commandId, 12, CommandKind.THREAD_DUMP, "done", null));
    out.add(new Vector("push admin (command done)", find(adminTransport, "s6")));

    // admin (WO-26): a subscription to one configuration change, then the two pushes
    // ConfigChangeService writes: one instance's result and the end of the change with its counts.
    UUID changeId = UUID.fromString("5f2c8a1e-7d4b-4c3e-8f6a-1b2d3e4f5a60");
    manager.onFrame(
        admin,
        "{\"op\":\"subscribe\",\"id\":\"s10\",\"topic\":\"admin\",\"params\":"
            + "{\"change_id\":\""
            + changeId
            + "\"}}");
    out.add(new Vector("ack admin (config change)", last(adminTransport)));
    bus.publishAdmin(
        changeId.toString(),
        null,
        clock.millis(),
        ConfigChangeService.resultPushData(
            changeId, 7, "config", 12, "partial", List.of("sampling.profile.normal-rate")));
    out.add(new Vector("push admin (config change instance result)", find(adminTransport, "s10")));
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("rejected", 1);
    counts.put("applied", 2);
    counts.put("partial", 1);
    counts.put("offline", 1);
    bus.publishAdmin(
        changeId.toString(),
        null,
        clock.millis(),
        ConfigChangeService.donePushData(changeId, 7, "config", counts));
    out.add(new Vector("push admin (config change done)", find(adminTransport, "s10")));

    // Errors: each code once, from the subscription logic.
    manager.onFrame(
        viewer, "{\"op\":\"subscribe\",\"id\":\"s7\",\"topic\":\"events\",\"params\":{}}");
    out.add(new Vector("error topic_unknown", last(transport)));
    manager.onFrame(
        viewer, "{\"op\":\"subscribe\",\"id\":\"s8\",\"topic\":\"xlog\",\"params\":{\"app\":0}}");
    out.add(new Vector("error invalid_params", last(transport)));
    manager.onFrame(
        viewer,
        "{\"op\":\"subscribe\",\"id\":\"s9\",\"topic\":\"admin\",\"params\":"
            + "{\"change_id\":\"c-1\"}}");
    out.add(new Vector("error forbidden", last(transport)));
    for (int i = 0; i < WsConnection.MAX_SUBSCRIPTIONS; i++) {
      manager.onFrame(
          viewer,
          "{\"op\":\"subscribe\",\"id\":\"f"
              + i
              + "\",\"topic\":\"alert\",\"params\":{\"env\":\"prod\"}}");
    }
    out.add(new Vector("error limit_exceeded", last(transport)));
    manager.onFrame(viewer, "{\"op\":\"bogus\"}");
    out.add(new Vector("error invalid_params without id", last(transport)));

    manager.onFrame(viewer, clientFrames().get(6).frame());
    out.add(new Vector("pong", last(transport)));

    // Backpressure: a small budget drops the older of two pushes.
    Collector slow = new Collector(1_200);
    var sub =
        bus.subscribe(
            "s1",
            new TopicRequest.Xlog(
                APP,
                null,
                io.argos.server.api.query.QueryLimits.TxKind.HTTP,
                java.util.Set.of(),
                java.util.Set.of()),
            slow.outbox);
    for (int r = 0; r < 2; r++) {
      long t = clock.millis();
      List<XlogPoint> batch = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        batch.add(point(TxIdsForVectors.TX1 + 100 * (r + 1) + i, t - 5, 40 + i, false, 17));
      }
      bus.publishXlog(session(APP, "prod", 12, t), batch);
      bus.bundleTick(t);
      clock.advance(RealtimeBus.BUNDLE_MS);
    }
    String notice = slow.outbox.poll();
    String afterDrop = slow.outbox.poll();
    sub.cancel();
    out.add(new Vector("notice backpressure", notice));
    out.add(new Vector("push xlog after a drop (dropped > 0)", afterDrop));

    out.add(new Vector("reauth", ServerFrames.reauth()));
    for (Vector v : out) {
      assertThat(v.frame()).as(v.name()).isNotNull();
    }
    return out;
  }

  private static String last(RecordingTransport t) {
    return t.sent.get(t.sent.size() - 1);
  }

  /** The newest push of a subscription. */
  private static String find(RecordingTransport t, String id) {
    String found = null;
    for (String s : t.sent) {
      JsonNode f = BusFixture.JSON.readTree(s);
      if (f.get("op").asString().equals("push") && f.get("id").asString().equals(id)) {
        found = s;
      }
    }
    return found;
  }

  /** Fixed tx ids of 2026-11 (18 digits, C-121 epoch). */
  private static final class TxIdsForVectors {
    static final long TX1 = 255_183_728_640_000_000L;
    static final long TX2 = TX1 + 1;
    static final long TX3 = TX1 + 2;

    private TxIdsForVectors() {}
  }
}
