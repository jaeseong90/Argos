/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.ws.WsTestClient;
import io.argos.server.testing.ws.WsTestClient.Frame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * The JVM values of the {@code counter} push end to end (ADR-0010 C-212 (1)): two fake agents send
 * metric batches over TLS gRPC, the ingest path stores them and hands the heap, GC pause and CPU
 * samples of rows with {@code late = 0} to the realtime bus, and a real WebSocket client of a
 * logged-in viewer receives a {@code counter} push whose {@code heap_used} (sum over the
 * instances), {@code gc_pause_ms} (summed increase in ms) and {@code cpu} (mean over the instances)
 * are the values sent; a late sample (90 s old, {@code late = 1}) never reaches a push.
 */
@Tag("integration")
@Tag("req:F-S03")
@Tag("req:D-23")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CounterMetricsIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String HEAP = "jvm_memory_used_bytes{area=\"heap\"}";
  private static final String NONHEAP = "jvm_memory_used_bytes{area=\"nonheap\"}";
  private static final String GC = "jvm_gc_pause_seconds_total{gc=\"G1 Young Generation\"}";
  private static final String CPU = "process_cpu_usage_ratio";

  private RealtimeStack stack;
  private AgentTokens.IssuedToken token;
  private final List<FakeAgent> agents = new ArrayList<>();
  private WsTestClient ws;

  @BeforeAll
  void start() throws Exception {
    stack = RealtimeStack.start();
    String app = "counter-metrics-" + Long.toHexString(System.nanoTime());
    token = stack.app(app);
    for (int i = 1; i <= 2; i++) {
      FakeAgent agent = stack.agent(token, app, app + "-" + i + ":8080", Duration.ZERO);
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      assertThat(agent.heartbeatRound(Duration.ofSeconds(10))).isPresent();
      agents.add(agent);
    }
    ApiTestClient viewer = stack.login(Role.VIEWER);
    ws = WsTestClient.open(viewer);
    ws.subscribe("c", "counter", "{\"app\":" + token.appId() + "}");
    ws.expect("ack", "c", WAIT);
  }

  @AfterAll
  void stop() {
    try {
      if (ws != null) {
        ws.close();
      }
      for (FakeAgent a : agents) {
        a.close();
      }
    } finally {
      if (stack != null) {
        stack.close();
      }
    }
  }

  private static void send(FakeAgent agent, List<MetricSeries> series, List<MetricSample> samples) {
    CallResult r = agent.sendMetrics(agent.batches().metrics(series, samples));
    assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
  }

  private static boolean counterPush(Frame f) {
    return f.op().equals("push") && "c".equals(f.json().path("id").asString());
  }

  @Test
  @DisplayName(
      "T-SV-020g FakeAgent MetricBatch -> counter push: heap_used = sum of the instances' heap,"
          + " gc_pause_ms = summed increase x 1000, cpu = mean of the instances, all non-null and"
          + " equal to what was sent; a 90 s late sample is never pushed")
  void counterCarriesJvmValues() throws Exception {
    FakeAgent a = agents.get(0);
    FakeAgent b = agents.get(1);
    // Leave at least 3 s before the window closes (its end + 2 s), so both batches are in time.
    long now = a.clock().nowMs();
    if (now - Records.grid5s(now) > 2_000) {
      Thread.sleep(5_000 - (now - Records.grid5s(now)) + 50);
      now = a.clock().nowMs();
    }
    long grid = Records.grid5s(now);
    List<MetricSeries> series =
        List.of(
            Records.series(0, HEAP, MetricKind.GAUGE),
            Records.series(1, GC, MetricKind.COUNTER),
            Records.series(2, CPU, MetricKind.GAUGE),
            Records.series(3, NONHEAP, MetricKind.GAUGE));
    // Instance 1: heap 300 MiB, GC +15 ms (cumulative 2.5 s), CPU 0.2, a nonheap value (ignored),
    // and a heap sample 90 s old (late = 1: stored, never published).
    send(
        a,
        series,
        List.of(
            Records.sample(0, grid, 314_572_800, 0),
            Records.sample(1, grid, 2.5, 0.015),
            Records.sample(2, grid, 0.2, 0),
            Records.sample(3, grid, 77_000_000, 0),
            Records.sample(0, Records.grid5s(now - 90_000), 999_999_999, 0)));
    // Instance 2: heap 100 MiB, CPU 0.6, no GC sample.
    send(
        b,
        series,
        List.of(Records.sample(0, grid, 104_857_600, 0), Records.sample(2, grid, 0.6, 0)));

    Frame push =
        ws.await(
                f ->
                    counterPush(f)
                        && !f.json().path("data").path("heap_used").isNull()
                        && !f.json().path("data").path("cpu").isNull()
                        && f.json().path("data").path("cpu").asDouble() > 0.3,
                WAIT)
            .orElseThrow(
                () -> new AssertionError("no counter push with JVM values: " + ws.frames()));
    JsonNode data = push.json().path("data");
    assertThat(data.path("heap_used").asDouble()).isEqualTo(314_572_800.0 + 104_857_600.0);
    assertThat(data.path("gc_pause_ms").isNull()).isFalse();
    assertThat(data.path("gc_pause_ms").asDouble()).isEqualTo(15.0);
    assertThat(data.path("cpu").asDouble()).isEqualTo(0.4);

    // The following windows carry no samples: null again (D-23), and no push ever shows the late
    // sample's heap value.
    Frame empty =
        ws.await(
                f ->
                    counterPush(f)
                        && f.receivedNanos() > push.receivedNanos()
                        && f.json().path("data").path("heap_used").isNull(),
                WAIT)
            .orElseThrow(() -> new AssertionError("no later counter push: " + ws.frames()));
    assertThat(empty.json().path("data").path("gc_pause_ms").isNull()).isTrue();
    assertThat(empty.json().path("data").path("cpu").isNull()).isTrue();
    for (Frame f : ws.frames()) {
      if (counterPush(f) && !f.json().path("data").path("heap_used").isNull()) {
        assertThat(f.json().path("data").path("heap_used").asDouble())
            .as("only the fresh samples reach a push")
            .isEqualTo(314_572_800.0 + 104_857_600.0);
      }
    }
  }
}
