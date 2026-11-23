/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.ws.WsTestClient;
import io.argos.server.testing.ws.WsTestClient.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * End to end through the real server: a protocol-level fake agent sends over TLS gRPC, the ingest
 * path stores and publishes, the realtime bus bundles, and a real WebSocket client of a logged-in
 * viewer receives (T-SV-020 WebSocket half, ADR-0010 C-128, C-167 (3)); a late row is stored but
 * never pushed (T-SV-015 bus half); {@code counter} and {@code active} pushes arrive with the
 * instance state of the state machine, and a state change reaches {@code
 * counter.per_instance[].state} (T-SV-022). While the test runs, JFR records {@code
 * jdk.VirtualThreadPinned} (12 open item 4): the report is {@code
 * build/reports/realtime/vt-pinning.md}, a reference on a shared machine.
 */
@Tag("integration")
@Tag("req:N-15")
@Tag("req:F-S03")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealtimeE2eIT {

  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final String SERVICE = "GET /api/realtime/{id}";

  private RealtimeStack stack;
  private AgentTokens.IssuedToken token;
  private String appName;
  private FakeAgent agent;
  private ApiTestClient viewer;
  private WsTestClient ws;
  private RecordingStream jfr;
  private java.util.concurrent.ScheduledExecutorService heartbeats;
  private final List<RecordedEvent> pinned = new CopyOnWriteArrayList<>();

  @BeforeAll
  void start() throws Exception {
    jfr = new RecordingStream();
    jfr.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1)).withStackTrace();
    jfr.onEvent("jdk.VirtualThreadPinned", pinned::add);
    jfr.startAsync();
    stack = RealtimeStack.start();
    appName = "realtime-e2e-" + Long.toHexString(System.nanoTime());
    token = stack.app(appName);
    agent = stack.agent(token, appName, appName + "-1:8080", Duration.ZERO);
    HelloResult hello = agent.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    assertThat(agent.heartbeatRound(Duration.ofSeconds(10))).isPresent();
    // Heartbeats every 5 s keep the instance UP until the state test stops them.
    heartbeats = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    var unused = heartbeats.scheduleWithFixedDelay(() -> agent.heartbeat(), 5, 5, TimeUnit.SECONDS);
    viewer = stack.login(Role.VIEWER);
    ws = WsTestClient.open(viewer);
    ws.subscribe("x", "xlog", "{\"app\":" + token.appId() + ",\"kind\":\"ALL\"}");
    ws.expect("ack", "x", WAIT);
    ws.subscribe("c", "counter", "{\"app\":" + token.appId() + "}");
    ws.expect("ack", "c", WAIT);
    ws.subscribe("a", "active", "{\"app\":" + token.appId() + "}");
    ws.expect("ack", "a", WAIT);
  }

  @AfterAll
  void stop() throws IOException {
    try {
      if (heartbeats != null) {
        heartbeats.shutdownNow();
      }
      if (ws != null) {
        ws.close();
      }
      if (agent != null) {
        agent.close();
      }
      if (stack != null) {
        stack.close();
      }
    } finally {
      if (jfr != null) {
        jfr.close();
        writePinningReport();
      }
    }
  }

  /** Sends one transaction that ended now; returns its tx id. */
  private long sendOne(long startOffsetMs) {
    long start = agent.clock().nowMs() + startOffsetMs;
    TransactionRecord tx = agent.records().http(SERVICE, start, 30_000).build();
    agent.sendNewDictionaryEntries();
    CallResult r = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
    assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
    return tx.getTxId();
  }

  private static boolean pushHas(Frame f, String id, String txId) {
    if (!f.op().equals("push") || !id.equals(f.json().path("id").asString())) {
      return false;
    }
    for (JsonNode n : f.json().path("data").path("tx_id")) {
      if (n.asString().equals(txId)) {
        return true;
      }
    }
    return false;
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-020 FakeAgent batch -> xlog push with that tx_id within 1 s of the send (so within 1 s"
          + " of receipt); 200 transactions: p50/p99/max reported (reference on a shared machine)")
  void xlogWithinOneSecond() throws Exception {
    // Warm-up: the first push of the path.
    String warm = Long.toUnsignedString(sendOne(-40));
    assertThat(ws.await(f -> pushHas(f, "x", warm), WAIT)).as("warm-up push").isPresent();

    long sent = System.nanoTime();
    String txId = Long.toUnsignedString(sendOne(-40));
    Frame push =
        ws.await(f -> pushHas(f, "x", txId), WAIT)
            .orElseThrow(() -> new AssertionError("no xlog push with " + txId));
    long ms = TimeUnit.NANOSECONDS.toMillis(push.receivedNanos() - sent);
    assertThat(ms).as("send -> xlog push (ms)").isLessThanOrEqualTo(1_000);
    JsonNode data = push.json().path("data");
    int i = indexOf(data.path("tx_id"), txId);
    assertThat(data.path("instance_id").get(i).asInt()).isEqualTo(agent.instanceId());
    assertThat(data.path("elapsed_ms").get(i).asInt()).isEqualTo(30);
    assertThat(data.path("status").get(i).asInt()).isZero();
    assertThat(data.path("kind").get(i).asInt()).isEqualTo(1);

    // 200 transactions, one batch each: latency per tx id.
    Map<String, Long> sentAt = new HashMap<>();
    Map<String, Long> latency = new HashMap<>();
    for (int n = 0; n < 200; n++) {
      long t0 = System.nanoTime();
      String id = Long.toUnsignedString(sendOne(-40));
      sentAt.put(id, t0);
    }
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (latency.size() < sentAt.size() && System.nanoTime() < deadline) {
      for (Frame f : ws.frames()) {
        if (f.op().equals("push") && "x".equals(f.json().path("id").asString())) {
          for (JsonNode n : f.json().path("data").path("tx_id")) {
            Long t0 = sentAt.get(n.asString());
            if (t0 != null) {
              latency.putIfAbsent(n.asString(), f.receivedNanos() - t0);
            }
          }
        }
      }
      Thread.sleep(100);
    }
    assertThat(latency).as("every one of the 200 tx ids pushed").hasSize(200);
    List<Long> sorted = new ArrayList<>(latency.values());
    Collections.sort(sorted);
    long p50 = TimeUnit.NANOSECONDS.toMillis(sorted.get(99));
    long p99 = TimeUnit.NANOSECONDS.toMillis(sorted.get(197));
    long max = TimeUnit.NANOSECONDS.toMillis(sorted.get(199));
    System.out.printf(
        "[wo22-ws-latency] single=%dms p50=%dms p99=%dms max=%dms n=200 (shared machine: INVALID"
            + " reference)%n",
        ms, p50, p99, max);
    writeReport(
        "ws-latency.md",
        "T-SV-020 send -> xlog push (ms), shared machine: INVALID"
            + " reference\n\n| single | p50 | p99 | max | n |\n|---|---|---|---|---|\n| "
            + ms
            + " | "
            + p50
            + " | "
            + p99
            + " | "
            + max
            + " | 200 |\n");
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-015 a row 90 s late is stored but never pushed; a fresh row of the same moment is")
  void lateRowNotPushed() throws Exception {
    String late = Long.toUnsignedString(sendOne(-90_000));
    String fresh = Long.toUnsignedString(sendOne(-40));
    assertThat(ws.await(f -> pushHas(f, "x", fresh), WAIT)).as("fresh tx pushed").isPresent();
    Thread.sleep(2_000);
    assertThat(ws.frames()).noneMatch(f -> pushHas(f, "x", late));
    ApiResponse stored = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      stored = viewer.get("/api/v1/transactions/" + late);
      if (stored.status() == 200) {
        break;
      }
      Thread.sleep(500);
    }
    assertThat(stored).isNotNull();
    assertThat(stored.status()).as("the late row is stored: " + stored.body()).isEqualTo(200);
    assertThat(ws.frames()).noneMatch(f -> pushHas(f, "x", late));
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-020 counter and active pushes arrive with the application's instance and its state"
          + " (UP), the active count of the snapshot")
  void counterAndActive() {
    CallResult r = agent.sendActive(agent.batches().active(List.of(), 7));
    assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
    sendOne(-40);
    Frame counter =
        ws.await(
                f ->
                    f.op().equals("push")
                        && "c".equals(f.json().path("id").asString())
                        && instanceRow(f.json().path("data"), agent.instanceId()) != null
                        && !instanceRow(f.json().path("data"), agent.instanceId())
                            .path("active")
                            .isNull(),
                WAIT)
            .orElseThrow(() -> new AssertionError("no counter push: " + ws.frames()));
    JsonNode row = instanceRow(counter.json().path("data"), agent.instanceId());
    assertThat(row.path("state").asString()).isEqualTo("UP");
    assertThat(row.path("active").asLong()).isEqualTo(7);
    assertThat(counter.json().path("data").has("tps")).isTrue();
    Frame active =
        ws.await(
                f ->
                    f.op().equals("push")
                        && "a".equals(f.json().path("id").asString())
                        && f.json().path("data").path("per_instance").size() > 0,
                WAIT)
            .orElseThrow(() -> new AssertionError("no active push"));
    JsonNode inst = active.json().path("data").path("per_instance").get(0);
    assertThat(inst.path("instance_id").asInt()).isEqualTo(agent.instanceId());
    assertThat(inst.path("count").asLong()).isEqualTo(7);
    assertThat(inst.path("state").asString()).isEqualTo("UP");
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-022 a state change of the state machine reaches counter.per_instance[].state: no"
          + " heartbeat for more than 60 s (server clock) -> STALE in a counter push")
  void stateChangeInCounter() throws InterruptedException {
    heartbeats.shutdownNow();
    assertThat(heartbeats.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    // Two steps with a ping between: the viewer's connection must not reach the 60 s client
    // timeout of the WebSocket (14 §5.3) while the agent stays silent for 65 s.
    stack.clock.advance(Duration.ofSeconds(35));
    ws.ping(35);
    ws.expect("pong", null, WAIT);
    stack.clock.advance(Duration.ofSeconds(30));
    ws.ping(65);
    Frame stale =
        ws.await(
                f ->
                    f.op().equals("push")
                        && "c".equals(f.json().path("id").asString())
                        && instanceRow(f.json().path("data"), agent.instanceId()) != null
                        && !"UP"
                            .equals(
                                instanceRow(f.json().path("data"), agent.instanceId())
                                    .path("state")
                                    .asString()),
                Duration.ofSeconds(45))
            .orElseThrow(() -> new AssertionError("no state change in counter: " + ws.frames()));
    assertThat(instanceRow(stale.json().path("data"), agent.instanceId()).path("state").asString())
        .isEqualTo("STALE");
  }

  private static JsonNode instanceRow(JsonNode data, int instanceId) {
    for (JsonNode r : data.path("per_instance")) {
      if (r.path("instance_id").asInt() == instanceId) {
        return r;
      }
    }
    return null;
  }

  private static int indexOf(JsonNode array, String value) {
    for (int i = 0; i < array.size(); i++) {
      if (array.get(i).asString().equals(value)) {
        return i;
      }
    }
    throw new AssertionError(value + " not in " + array);
  }

  private static void writeReport(String name, String text) throws IOException {
    Path dir = Path.of("build", "reports", "realtime");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
  }

  /** 12 open item 4: pinned virtual threads seen while the test ran, by top frames. */
  private void writePinningReport() throws IOException {
    Map<String, Integer> byStack = new LinkedHashMap<>();
    Map<String, Long> maxMs = new HashMap<>();
    for (RecordedEvent e : pinned) {
      StringBuilder key = new StringBuilder();
      if (e.getStackTrace() != null) {
        int n = 0;
        for (RecordedFrame f : e.getStackTrace().getFrames()) {
          String m = f.getMethod().getType().getName() + "." + f.getMethod().getName();
          if (m.startsWith("java.") || m.startsWith("jdk.") || m.startsWith("sun.")) {
            continue;
          }
          key.append(n == 0 ? "" : " <- ").append(m);
          if (++n == 4) {
            break;
          }
        }
      }
      String k = key.isEmpty() ? "(JDK frames only)" : key.toString();
      byStack.merge(k, 1, Integer::sum);
      maxMs.merge(k, e.getDuration().toMillis(), Math::max);
    }
    StringBuilder out =
        new StringBuilder(
            "# jdk.VirtualThreadPinned during RealtimeE2eIT (threshold 1 ms)\n\n"
                + "Shared machine: reference only. Events: "
                + pinned.size()
                + "\n\n| count | max ms | top non-JDK frames |\n|---|---|---|\n");
    byStack.entrySet().stream()
        .sorted((a, b) -> b.getValue() - a.getValue())
        .forEach(
            en ->
                out.append("| ")
                    .append(en.getValue())
                    .append(" | ")
                    .append(maxMs.get(en.getKey()))
                    .append(" | ")
                    .append(en.getKey().replace("|", "/"))
                    .append(" |\n"));
    writeReport("vt-pinning.md", out.toString());
    System.out.println("[wo22-vt-pinning] events=" + pinned.size() + " " + byStack);
  }
}
