/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.selfmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.ops.management.MeterNames;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.collector.StubWalWriter;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The self-metric recorder (DSN-12-14, 12 §9.4; T-SV-047 unit part): one WAL metric record of the
 * server's virtual application per 5 s grid point, Prometheus names with sorted labels, counter
 * deltas with the start and reset rules, the drop reasons as 0 before their counter exists, the
 * series cap and the failure counter.
 */
@Tag("req:F-S20")
class SelfMetricsRecorderTest {

  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final StubWalWriter wal = new StubWalWriter();

  private SelfMetricsRecorder recorder() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("meters", registry);
    beans.addBean("wal", wal);
    SelfMetricsRecorder r =
        new SelfMetricsRecorder(
            beans.getBeanProvider(MeterRegistry.class),
            beans.getBeanProvider(WalWriter.class),
            beans.getBeanProvider(StartupSequence.class),
            clock);
    return r;
  }

  private record Written(
      WalDataPayload payload,
      MetricBatch batch,
      Map<String, MetricSample> byName,
      Map<String, MetricKind> kinds) {}

  private Written last() throws Exception {
    List<StubWalWriter.Appended> all = wal.appended(WalRecordType.METRIC);
    assertThat(all).isNotEmpty();
    StubWalWriter.Appended a = all.get(all.size() - 1);
    WalDataPayload payload = WalDataPayload.decode(a.payload().toByteArray());
    MetricBatch batch = MetricBatch.parseFrom(payload.batch());
    Map<Integer, MetricSeries> series = new HashMap<>();
    batch.getSeriesList().forEach(s -> series.put(s.getIndex(), s));
    Map<String, MetricSample> byName = new LinkedHashMap<>();
    Map<String, MetricKind> kinds = new LinkedHashMap<>();
    for (MetricSample s : batch.getSamplesList()) {
      MetricSeries m = series.get(s.getSeriesIndex());
      byName.put(m.getName(), s);
      kinds.put(m.getName(), m.getKind());
    }
    return new Written(payload, batch, byName, kinds);
  }

  @Test
  @DisplayName(
      "T-SV-047 one METRIC WAL record of app 0, instance 0, env '' on the 5 s grid with server seq;"
          + " Prometheus names with sorted labels; the ingest lag as its p99 gauge")
  void writesServerRecord() throws Exception {
    Counter.builder("argos.collector.dropped")
        .tag("reason", "quota")
        .register(registry)
        .increment(3);
    Counter.builder("argos.collector.received").tag("type", "tx").register(registry).increment(10);
    AtomicInteger sessions = new AtomicInteger(2);
    Gauge.builder("argos.sessions.active", sessions, AtomicInteger::get).register(registry);
    Timer lag = Timer.builder("argos.ingest.lag").publishPercentiles(0.99).register(registry);
    lag.record(Duration.ofMillis(800));
    Gauge.builder("jvm.not.allowed", () -> 1).register(registry);
    Gauge.builder(MeterNames.TLS_CERT_EXPIRY_SECONDS, () -> 604_800)
        .tag("listener", "grpc")
        .register(registry);

    SelfMetricsRecorder r = recorder();
    assertThat(r.recordOnce()).isTrue();
    StubWalWriter.Appended a = wal.appended(WalRecordType.METRIC).get(0);
    assertThat(a.instanceId()).isZero();
    assertThat(a.seq()).as("server seq bit").isNegative();
    Written w = last();
    assertThat(w.payload().appId()).isEqualTo(SelfMetricsRecorder.SERVER_APP_ID);
    assertThat(w.payload().env()).isEmpty();
    assertThat(w.byName().keySet())
        .containsExactly(
            "argos_collector_dropped_total{reason=\"dead_letter\"}",
            "argos_collector_dropped_total{reason=\"quota\"}",
            "argos_collector_dropped_total{reason=\"wal_full\"}",
            "argos_collector_received_total{type=\"tx\"}",
            "argos_ingest_lag_seconds",
            "argos_server_tls_cert_expiry_seconds{listener=\"grpc\"}",
            "argos_sessions_active");
    long ts = w.batch().getSamples(0).getTsMs();
    assertThat(ts % SelfMetricsRecorder.GRID_MS).isZero();
    assertThat(ts).isBetween(clock.millis() - SelfMetricsRecorder.GRID_MS, clock.millis());
    assertThat(w.batch().getSamplesList()).allSatisfy(s -> assertThat(s.getTsMs()).isEqualTo(ts));
    assertThat(w.kinds().get("argos_ingest_lag_seconds")).isEqualTo(MetricKind.GAUGE);
    assertThat(w.byName().get("argos_ingest_lag_seconds").getValue()).isBetween(0.7, 0.9);
    assertThat(w.kinds().get("argos_sessions_active")).isEqualTo(MetricKind.GAUGE);
    assertThat(w.byName().get("argos_sessions_active").getDelta()).isZero();
    assertThat(w.kinds().get("argos_collector_dropped_total{reason=\"quota\"}"))
        .isEqualTo(MetricKind.COUNTER);

    // Same grid point: nothing new.
    assertThat(r.recordOnce()).isFalse();
    assertThat(wal.appended(WalRecordType.METRIC)).hasSize(1);
  }

  @Test
  @DisplayName(
      "T-SV-047 counter delta: the first sample counts from 0, then the increase, a lower value is"
          + " a reset; drop reasons without a counter are 0")
  void counterDeltas() throws Exception {
    Counter dropped =
        Counter.builder("argos.collector.dropped").tag("reason", "quota").register(registry);
    dropped.increment(5);
    SelfMetricsRecorder r = recorder();
    assertThat(r.recordOnce()).isTrue();
    Written first = last();
    MetricSample quota = first.byName().get("argos_collector_dropped_total{reason=\"quota\"}");
    assertThat(quota.getValue()).isEqualTo(5);
    assertThat(quota.getDelta()).isEqualTo(5);
    MetricSample walFull = first.byName().get("argos_collector_dropped_total{reason=\"wal_full\"}");
    assertThat(walFull.getValue()).isZero();
    assertThat(walFull.getDelta()).isZero();

    clock.advance(Duration.ofSeconds(5));
    dropped.increment(2);
    assertThat(r.recordOnce()).isTrue();
    MetricSample second = last().byName().get("argos_collector_dropped_total{reason=\"quota\"}");
    assertThat(second.getValue()).isEqualTo(7);
    assertThat(second.getDelta()).isEqualTo(2);

    // A reset (a meter registered again from 0): the delta is the new value.
    registry.remove(dropped);
    Counter.builder("argos.collector.dropped")
        .tag("reason", "quota")
        .register(registry)
        .increment(1);
    clock.advance(Duration.ofSeconds(5));
    assertThat(r.recordOnce()).isTrue();
    MetricSample third = last().byName().get("argos_collector_dropped_total{reason=\"quota\"}");
    assertThat(third.getValue()).isEqualTo(1);
    assertThat(third.getDelta()).isEqualTo(1);
  }

  @Test
  @DisplayName("T-SV-047 at most 200 series, the first by name")
  void seriesCap() {
    for (int i = 0; i < 250; i++) {
      Counter.builder("argos.collector.rejected")
          .tag("reason", String.format("r%03d", i))
          .register(registry)
          .increment();
    }
    List<SelfMetricsRecorder.Sample> samples = recorder().sample(registry);
    assertThat(samples).hasSize(SelfMetricsRecorder.MAX_SERIES);
    assertThat(samples.get(0).name())
        .isEqualTo("argos_collector_dropped_total{reason=\"dead_letter\"}");
  }

  @Test
  @DisplayName(
      "T-SV-047 a batch the WAL does not take is dropped and counted in"
          + " argos_self_metrics_write_failures_total; the next grid point writes again")
  void failuresCounted() throws Exception {
    SelfMetricsRecorder r = recorder();
    wal.full(true);
    assertThat(r.recordOnce()).isFalse();
    assertThat(registry.get(MeterNames.SELF_METRICS_WRITE_FAILURES).counter().count()).isEqualTo(1);
    wal.full(false);
    clock.advance(Duration.ofSeconds(5));
    assertThat(r.recordOnce()).isTrue();
    assertThat(last().byName()).isNotEmpty();
  }

  @Test
  @DisplayName("T-SV-047 label values are escaped as in the exposition format")
  void escapes() {
    assertThat(
            SelfMetricsRecorder.seriesName(
                "argos.collector.rejected",
                io.micrometer.core.instrument.Meter.Type.COUNTER,
                List.of(
                    io.micrometer.core.instrument.Tag.of("z", "a\"b\\c"),
                    io.micrometer.core.instrument.Tag.of("a", "x"))))
        .isEqualTo("argos_collector_rejected_total{a=\"x\",z=\"a\\\"b\\\\c\"}");
  }
}
