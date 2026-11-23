/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.AgentServerStack.assertAckOk;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.api.query.QuerySources;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * T-ACC-16 server side (D-16, DSN-13-04, 13 §4.6): metric kinds through the whole server on real
 * ClickHouse. A fake agent sends 5 s samples of a counter ({@code jvm_gc_pause_seconds_total}) and
 * a gauge ({@code jvm_memory_used_bytes{area="heap"}}) over three minutes of the previous hour and
 * is restarted (Bye, new agent epoch, same instance name) in the middle minute: the counter's
 * cumulative value drops and the restarted agent sends the reset-corrected delta (the current
 * value, 13 §4.6). {@code metric_1m} holds per minute {@code increase} = Σ delta (not last - first,
 * which is negative in the reset minute), {@code last_v}, and for the gauge {@code avg_sum /
 * sample_count}, {@code min_v}, {@code max_v} with {@code increase} 0; {@code sample_count} counts
 * the samples of the minute. The dashboard API's {@code series.gc_pause_ms} is the minute's
 * increase in ms and {@code heap_used} its average, read from the minute table and from the raw
 * table (ADMIN debug source). The agent half (delta computation, 501st series dropped and counted)
 * is the agent's {@code MetricRegistryTest}.
 */
@Tag("integration")
@Tag("req:D-16")
@Tag("req:DSN-13-04")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricKindsIT {

  private static final String GC_PAUSE = "jvm_gc_pause_seconds_total{gc=\"G1 Young Generation\"}";
  private static final String HEAP = "jvm_memory_used_bytes{area=\"heap\"}";
  private static final int SAMPLES_PER_MINUTE = 12;
  private static final int MINUTES = 3;

  /** Samples sent before the restart: the first minute and half of the second. */
  private static final int BEFORE_RESTART = SAMPLES_PER_MINUTE + SAMPLES_PER_MINUTE / 2;

  private static final double STEP_BEFORE = 0.004;
  private static final double STEP_AFTER = 0.003;
  private static final Offset<Double> EXACT = Offset.offset(1e-9);

  private AgentServerStack stack;

  @BeforeAll
  void start() {
    stack = AgentServerStack.start();
  }

  @AfterAll
  void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  /** One sent sample: its index on the 5 s grid, counter value and delta, gauge value. */
  private record Sent(int k, long tsMs, double counter, double delta, double heap) {}

  @Test
  @DisplayName(
      "T-ACC-16 counter reset (restart) -> metric_1m increase = Σ agent delta, last_v; gauge"
          + " avg/min/max and sample_count, increase 0; dashboard gc_pause_ms = increase x 1000 and"
          + " heap_used = minute average from metric_1m and from raw")
  void counterResetAndGaugeRollup() throws Exception {
    String appName = "it-metric-kinds";
    String instance = appName + "-1:8080";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    long hour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L - 3_600_000L;

    List<Sent> sent = new ArrayList<>();
    double cumulative = 0;
    for (int k = 0; k < SAMPLES_PER_MINUTE * MINUTES; k++) {
      double delta;
      if (k < BEFORE_RESTART) {
        cumulative += STEP_BEFORE;
        delta = STEP_BEFORE;
      } else if (k == BEFORE_RESTART) {
        // The restarted JVM's counter starts again: smaller than the previous sample, so the
        // agent's increase is the current value (13 §4.6).
        cumulative = STEP_AFTER;
        delta = STEP_AFTER;
      } else {
        cumulative += STEP_AFTER;
        delta = STEP_AFTER;
      }
      double heap = 100_000_000d + ((k * 7_919) % 13) * 1_048_576d;
      sent.add(new Sent(k, hour + k * 5_000L, cumulative, delta, heap));
    }

    int firstInstance;
    try (FakeAgent agent = stack.connect(token, appName, instance, 41)) {
      firstInstance = agent.instanceId();
      sendSamples(agent, sent.subList(0, SAMPLES_PER_MINUTE));
      sendSamples(agent, sent.subList(SAMPLES_PER_MINUTE, BEFORE_RESTART));
      agent.sendBye(ByeReason.SHUTDOWN);
      agent.cancelSession();
    }
    int secondInstance;
    try (FakeAgent restarted = stack.connect(token, appName, instance, 42)) {
      secondInstance = restarted.instanceId();
      sendSamples(restarted, sent.subList(BEFORE_RESTART, 2 * SAMPLES_PER_MINUTE));
      sendSamples(restarted, sent.subList(2 * SAMPLES_PER_MINUTE, sent.size()));
    }
    assertThat(secondInstance)
        .as("the restarted JVM continues the instance")
        .isEqualTo(firstInstance);

    String rawCount = "SELECT count() FROM argos.metric WHERE app_id = " + appId;
    assertThat(stack.awaitScalar(rawCount, 2L * sent.size(), 120)).isEqualTo(2L * sent.size());
    String minuteSamples = "SELECT sum(sample_count) FROM argos.metric_1m WHERE app_id = " + appId;
    assertThat(stack.awaitScalar(minuteSamples, 2L * sent.size(), 60)).isEqualTo(2L * sent.size());

    // --- metric_1m per minute ------------------------------------------------------------------
    List<List<String>> rows =
        stack
            .reader()
            .rows(
                "SELECT toUnixTimestamp(minute), name, toString(kind), sum(sample_count),"
                    + " sum(avg_sum), min(min_v), max(max_v), anyLast(last_v), sum(increase)"
                    + " FROM argos.metric_1m WHERE app_id = "
                    + appId
                    + " GROUP BY minute, name, kind ORDER BY minute, name");
    assertThat(rows).hasSize(2 * MINUTES);
    for (int m = 0; m < MINUTES; m++) {
      List<Sent> minute = sent.subList(m * SAMPLES_PER_MINUTE, (m + 1) * SAMPLES_PER_MINUTE);
      long minuteStart = (hour + m * 60_000L) / 1_000;
      double increase = minute.stream().mapToDouble(Sent::delta).sum();
      double naive = minute.get(minute.size() - 1).counter() - minute.get(0).counter();
      // ORDER BY name: jvm_gc_pause_seconds_total sorts before jvm_memory_used_bytes.
      List<String> counter = rows.get(2 * m);
      List<String> gauge = rows.get(2 * m + 1);
      String at = "minute " + Instant.ofEpochSecond(minuteStart);

      assertThat(counter.subList(0, 4))
          .as(at)
          .containsExactly(
              Long.toString(minuteStart),
              GC_PAUSE,
              "counter",
              Integer.toString(SAMPLES_PER_MINUTE));
      assertThat(Double.parseDouble(counter.get(8)))
          .as(at + ": increase = Σ delta (reset-corrected by the agent)")
          .isCloseTo(increase, EXACT);
      if (m != 1) {
        // A minute written by one insert: last_v is its last sample. The reset minute spans two
        // inserts (parts) and anyLast over parts has no order, so last_v is not asserted there.
        assertThat(Double.parseDouble(counter.get(7)))
            .as(at + ": last_v = last cumulative value")
            .isCloseTo(minute.get(minute.size() - 1).counter(), EXACT);
      } else {
        assertThat(naive).as("the reset minute: last - first would be negative").isNegative();
        assertThat(increase).isCloseTo(6 * STEP_BEFORE + 6 * STEP_AFTER, EXACT);
      }

      double sum = minute.stream().mapToDouble(Sent::heap).sum();
      assertThat(gauge.subList(0, 4))
          .as(at)
          .containsExactly(
              Long.toString(minuteStart), HEAP, "gauge", Integer.toString(SAMPLES_PER_MINUTE));
      assertThat(Double.parseDouble(gauge.get(4)) / SAMPLES_PER_MINUTE)
          .as(at + ": avg = avg_sum / sample_count")
          .isCloseTo(sum / SAMPLES_PER_MINUTE, EXACT);
      assertThat(Double.parseDouble(gauge.get(5)))
          .as(at + ": min")
          .isEqualTo(minute.stream().mapToDouble(Sent::heap).min().orElseThrow());
      assertThat(Double.parseDouble(gauge.get(6)))
          .as(at + ": max")
          .isEqualTo(minute.stream().mapToDouble(Sent::heap).max().orElseThrow());
      assertThat(Double.parseDouble(gauge.get(8))).as(at + ": gauge increase").isZero();
    }

    // --- dashboard API: minute table and raw table give the same series ------------------------
    ApiTestClient admin = stack.login(Role.ADMIN);
    String path =
        "/api/v1/apps/"
            + appId
            + "/dashboard?from="
            + Instant.ofEpochMilli(hour)
            + "&to="
            + Instant.ofEpochMilli(hour + MINUTES * 60_000L)
            + "&step=1m";
    for (String level : List.of("1m", "raw")) {
      ApiResponse r = admin.send("GET", path, null, Map.of(QuerySources.DEBUG_HEADER, level));
      assertThat(r.status()).as(level + " " + r.body()).isEqualTo(200);
      JsonNode body = r.json();
      assertThat(body.path("meta").path("source").asString()).isEqualTo(level);
      JsonNode series = body.path("data").path("series");
      assertThat(series.path("ts").size()).as(level + " buckets").isEqualTo(MINUTES);
      for (int m = 0; m < MINUTES; m++) {
        List<Sent> minute = sent.subList(m * SAMPLES_PER_MINUTE, (m + 1) * SAMPLES_PER_MINUTE);
        assertThat(Instant.parse(series.path("ts").path(m).asString()))
            .isEqualTo(Instant.ofEpochMilli(hour + m * 60_000L));
        assertThat(series.path("gc_pause_ms").path(m).asDouble())
            .as(level + " gc_pause_ms minute " + m)
            .isCloseTo(
                Math.round(minute.stream().mapToDouble(Sent::delta).sum() * 1_000_000d) / 1_000d,
                Offset.offset(1e-6));
        assertThat(series.path("heap_used").path(m).asDouble())
            .as(level + " heap_used minute " + m)
            .isEqualTo(
                (double)
                    Math.round(minute.stream().mapToDouble(Sent::heap).average().orElseThrow()));
      }
    }
  }

  /** One metric batch with the two series and the samples of {@code samples}. */
  private static void sendSamples(FakeAgent agent, List<Sent> samples) {
    List<MetricSeries> series =
        List.of(
            Records.series(0, GC_PAUSE, MetricKind.COUNTER),
            Records.series(1, HEAP, MetricKind.GAUGE));
    List<MetricSample> out = new ArrayList<>();
    for (Sent s : samples) {
      out.add(Records.sample(0, Records.grid5s(s.tsMs()), s.counter(), s.delta()));
      out.add(Records.sample(1, Records.grid5s(s.tsMs()), s.heap(), 0));
    }
    assertAckOk(agent.sendMetrics(agent.batches().metrics(series, out)));
  }
}
