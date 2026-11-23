/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AppRef;
import io.argos.server.session.ClockSync;
import io.argos.server.session.InMemorySessionRegistry;
import io.argos.server.session.SessionStream;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.grpc.stub.ServerCallStreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every alert metric of 12 §5.3 (F-S31) is computable by its provider (12 §5.2) from the inputs the
 * server receives, or is explicitly without value: {@code instance_state} is event driven (the
 * instance state transitions, T-SV-023), {@code error_group_new} is S, {@code
 * jvm_old_gen_after_gc_ratio} has no agent series, and the server disk, certificate and token
 * meters belong to WO-28 and WO-26 (unavailable until they exist, a value once they do).
 */
@Tag("req:F-S31")
class MetricProvidersTest {

  private static final long NOW = 1_800_000_000_000L;
  private static final int APP = 7;
  private static final int INSTANCE = 11;
  private static final long SERVICE = 4242L;
  private static final long JOB = 777L;
  private static final long HOST = 99L;

  private static final Set<AlertMetric> WITHOUT_VALUE =
      Set.of(
          AlertMetric.INSTANCE_STATE,
          AlertMetric.ERROR_GROUP_NEW,
          AlertMetric.JVM_OLD_GEN_AFTER_GC_RATIO,
          AlertMetric.ARGOS_SERVER_DISK_USAGE_RATIO,
          AlertMetric.ARGOS_SERVER_TLS_CERT_EXPIRY_DAYS,
          AlertMetric.AGENT_TOKEN_EXPIRY_DAYS);

  @Test
  @DisplayName(
      "T-SV-036 every F-S31 metric yields a value from its provider or an explicit no value"
          + " (instance_state event driven, error_group_new S, old gen after GC without series,"
          + " WO-28/WO-26 meters absent)")
  void everyMetricComputable() {
    Fixture f = new Fixture(false);
    Map<AlertMetric, MetricValue> values = new EnumMap<>(AlertMetric.class);
    for (AlertMetric m : AlertMetric.values()) {
      values.put(m, f.router.read(f.request(m)));
    }
    for (Map.Entry<AlertMetric, MetricValue> e : values.entrySet()) {
      if (WITHOUT_VALUE.contains(e.getKey())) {
        assertThat(e.getValue().status())
            .as(e.getKey().wire())
            .isEqualTo(MetricValue.Status.UNAVAILABLE);
      } else {
        assertThat(e.getValue().present()).as(e.getKey().wire() + " " + e.getValue()).isTrue();
      }
    }
    assertThat(values.get(AlertMetric.ERROR_RATE).value()).isEqualTo(0.25);
    assertThat(values.get(AlertMetric.TPS).value()).isEqualTo(40.0 / 60);
    assertThat(values.get(AlertMetric.RESPONSE_TIME_P95).value()).isBetween(1980.0, 2020.0);
    assertThat(values.get(AlertMetric.APDEX).value())
        .as("15 satisfied (100 ms, not an error), none tolerating")
        .isEqualTo(15.0 / 40);
    assertThat(values.get(AlertMetric.JVM_HEAP_USED_RATIO).value()).isEqualTo(0.5);
    assertThat(values.get(AlertMetric.JVM_GC_PAUSE_RATIO).value()).isEqualTo(6.0 / 60);
    assertThat(values.get(AlertMetric.TOMCAT_THREADS_USAGE_RATIO).value()).isEqualTo(0.75);
    assertThat(values.get(AlertMetric.JDBC_POOL_WAITING).value()).isEqualTo(3.0);
    assertThat(values.get(AlertMetric.JDBC_POOL_USAGE_RATIO).value()).isEqualTo(1.0);
    assertThat(values.get(AlertMetric.SYSTEM_DISK_USAGE_RATIO).value()).isEqualTo(0.9);
    assertThat(values.get(AlertMetric.ARGOS_AGENT_DROPPED_TOTAL).value()).isEqualTo(12.0);
    assertThat(values.get(AlertMetric.ACTIVE_TRANSACTIONS).value()).isEqualTo(30.0);
    assertThat(values.get(AlertMetric.ACTIVE_RATIO_TO_MAX_THREADS).value()).isEqualTo(0.15);
    assertThat(values.get(AlertMetric.EXT_CALL_TIMEOUT_RATIO).value()).isEqualTo(0.3);
    assertThat(values.get(AlertMetric.EXT_CALL_ERROR_RATIO).value()).isEqualTo(0.1);
    assertThat(values.get(AlertMetric.BATCH_ELAPSED_SECONDS).value())
        .as("the running job (2400 s) beats the finished run (60 s)")
        .isEqualTo(2400.0);
    assertThat(values.get(AlertMetric.ARGOS_SERVER_INGEST_LAG_SECONDS).value()).isGreaterThan(30.0);
    assertThat(values.get(AlertMetric.ARGOS_SERVER_DROPPED_TOTAL).value()).isEqualTo(5.0);
    assertThat(values.get(AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL).value())
        .as("no failure counter yet: nothing failed")
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName(
      "T-SV-036 the server disk, certificate expiry and token expiry metrics read their gauges once"
          + " WO-28/WO-26 register them (largest ratio, fewest days)")
  void selfGaugesWhenPresent() {
    Fixture f = new Fixture(true);
    assertThat(f.router.read(f.request(AlertMetric.ARGOS_SERVER_DISK_USAGE_RATIO)).value())
        .isEqualTo(0.85);
    assertThat(f.router.read(f.request(AlertMetric.ARGOS_SERVER_TLS_CERT_EXPIRY_DAYS)).value())
        .isEqualTo(20.0);
    assertThat(f.router.read(f.request(AlertMetric.AGENT_TOKEN_EXPIRY_DAYS)).value())
        .isEqualTo(6.0);
  }

  @Test
  @DisplayName(
      "T-SV-036 counter increases over the window: notification failures and server drops count"
          + " only what happened inside the window")
  void selfIncreases() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SelfProvider self = new SelfProvider(meters, Duration.ofMinutes(15).toMillis());
    Counter failures = meters.counter(SelfProvider.NOTIFICATION_FAILURES_COUNTER, "channel", "a");
    failures.increment(4);
    self.sample(NOW - 120_000);
    failures.increment(2);
    self.sample(NOW - 30_000);
    failures.increment(1);
    MetricRequest oneMinute =
        new MetricRequest(
            AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL,
            null,
            Kind.HTTP,
            TargetType.SERVER,
            null,
            Duration.ofMinutes(1),
            NOW,
            Set.of());
    assertThat(self.read(oneMinute).value()).isEqualTo(1.0);
    MetricRequest fiveMinutes =
        new MetricRequest(
            AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL,
            null,
            Kind.HTTP,
            TargetType.SERVER,
            null,
            Duration.ofMinutes(5),
            NOW,
            Set.of());
    assertThat(self.read(fiveMinutes).value()).isEqualTo(3.0);
  }

  /** Providers fed with one application's samples. */
  private static final class Fixture {
    final MetricRouter router;

    Fixture(boolean selfGauges) {
      Duration retention = Duration.ofMinutes(15);
      WindowProvider windows = new WindowProvider(retention);
      for (int i = 0; i < 40; i++) {
        boolean error = i % 4 == 0;
        int elapsed = i < 20 ? 100 : 2000;
        windows.recordTransaction(
            APP, INSTANCE, Kind.HTTP, SERVICE, elapsed, error, i < 20 ? 500 : 0, NOW - i * 1_000L);
      }
      windows.recordTransaction(APP, INSTANCE, Kind.BATCH, JOB, 60_000, false, 0, NOW - 2_000);
      List<Integer> buckets = new ArrayList<>();
      for (int b = 0; b < 15; b++) {
        buckets.add(b == 5 ? 10 : 0);
      }
      windows.recordExt(APP, HOST, 10, 1, 3, buckets, 120, NOW - 5_000);

      CounterProvider counters = new CounterProvider(retention);
      long t = NOW - 1_000;
      counters.record(APP, INSTANCE, "jvm_memory_used_bytes{area=\"heap\"}", 512, 0, t);
      counters.record(APP, INSTANCE, "jvm_memory_max_bytes{area=\"heap\"}", 1024, 0, t);
      counters.record(APP, INSTANCE, "jvm_memory_used_bytes{area=\"nonheap\"}", 999, 0, t);
      counters.record(APP, INSTANCE, "jvm_gc_pause_seconds_total{gc=\"g1\"}", 100, 6, t);
      counters.record(APP, INSTANCE, "jvm_threads_live", 120, 0, t);
      counters.record(APP, INSTANCE, "tomcat_threads_busy{connector=\"http-nio-8080\"}", 150, 0, t);
      counters.record(APP, INSTANCE, "tomcat_threads_max{connector=\"http-nio-8080\"}", 200, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_waiting{pool=\"main\"}", 3, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_waiting{pool=\"aux\"}", 1, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_active{pool=\"main\"}", 10, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_max{pool=\"main\"}", 10, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_active{pool=\"aux\"}", 1, 0, t);
      counters.record(APP, INSTANCE, "jdbc_pool_max{pool=\"aux\"}", 5, 0, t);
      counters.record(APP, INSTANCE, "system_cpu_usage_ratio", 0.4, 0, t);
      counters.record(APP, INSTANCE, "system_memory_used_bytes", 3, 0, t);
      counters.record(APP, INSTANCE, "system_memory_total_bytes", 4, 0, t);
      counters.record(APP, INSTANCE, "system_disk_used_bytes{mount=\"/\"}", 90, 0, t);
      counters.record(APP, INSTANCE, "system_disk_total_bytes{mount=\"/\"}", 100, 0, t);
      counters.record(APP, INSTANCE, "system_disk_used_bytes{mount=\"/data\"}", 10, 0, t);
      counters.record(APP, INSTANCE, "system_disk_total_bytes{mount=\"/data\"}", 100, 0, t);
      counters.record(APP, INSTANCE, "argos_agent_dropped_total{queue=\"tx\"}", 50, 12, t);

      InMemorySessionRegistry sessions = new InMemorySessionRegistry();
      AgentSession s = session(NOW - 600_000);
      sessions.register(s);
      s.replaceSnapshot(
          new AgentSession.ActiveSnapshot(
              NOW - 2_000,
              ActiveTransactionSnapshot.newBuilder()
                  .setTotalActive(30)
                  .addItems(
                      ActiveTransaction.newBuilder()
                          .setKind(TransactionKind.BATCH)
                          .setServiceId(JOB)
                          .setElapsedMs(2_400_000))
                  .build()));
      RegistryProvider registry = new RegistryProvider(sessions, counters);

      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      Timer lag =
          Timer.builder(SelfProvider.LAG_TIMER).publishPercentiles(0.5, 0.99).register(meters);
      for (int i = 0; i < 100; i++) {
        lag.record(40, TimeUnit.SECONDS);
      }
      Counter dropped = meters.counter(SelfProvider.DROPPED_COUNTER, "reason", "quota");
      SelfProvider self = new SelfProvider(meters, retention.toMillis());
      self.sample(NOW - 60_000);
      dropped.increment(5);
      if (selfGauges) {
        Gauge.builder(SelfProvider.DISK_GAUGE, () -> 0.85).tag("volume", "wal").register(meters);
        Gauge.builder(SelfProvider.DISK_GAUGE, () -> 0.5).tag("volume", "ch").register(meters);
        Gauge.builder(SelfProvider.CERT_GAUGE, () -> 20.0).register(meters);
        Gauge.builder(SelfProvider.TOKEN_GAUGE, () -> 6.0).tag("app", "1").register(meters);
        Gauge.builder(SelfProvider.TOKEN_GAUGE, () -> 90.0).tag("app", "2").register(meters);
      }
      router = new MetricRouter(windows, counters, registry, null, self, retention);
    }

    MetricRequest request(AlertMetric m) {
      TargetType type = m.targets().iterator().next();
      if (m.targets().contains(TargetType.APP)) {
        type = TargetType.APP;
      }
      String id =
          switch (type) {
            case EXT_HOST -> Long.toString(HOST);
            case SERVICE -> Long.toString(m == AlertMetric.BATCH_ELAPSED_SECONDS ? JOB : SERVICE);
            case INSTANCE -> Integer.toString(INSTANCE);
            default -> null;
          };
      return new MetricRequest(
          m,
          type == TargetType.SERVER ? null : APP,
          m == AlertMetric.BATCH_ELAPSED_SECONDS ? Kind.BATCH : Kind.HTTP,
          type,
          id,
          Duration.ofMinutes(1),
          NOW,
          Set.of());
    }

    private static AgentSession session(long acceptedAtMs) {
      return new AgentSession(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "argos_AbCdEfGh",
          new AppRef(APP, "shop", "prod", 0),
          INSTANCE,
          "host-a:8080",
          1L,
          IpMode.MASKED,
          UserIdMode.OFF,
          "1.0.0",
          "",
          false,
          null,
          List.of(),
          acceptedAtMs,
          new ClockSync(acceptedAtMs, acceptedAtMs),
          new SessionStream(new NoopObserver()));
    }
  }

  /** A stream observer that drops everything. */
  private static final class NoopObserver extends ServerCallStreamObserver<ServerMessage> {
    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void setOnCancelHandler(Runnable handler) {}

    @Override
    public void setCompression(String compression) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable handler) {}

    @Override
    public void disableAutoInboundFlowControl() {}

    @Override
    public void request(int count) {}

    @Override
    public void setMessageCompression(boolean enable) {}

    @Override
    public void onNext(ServerMessage value) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onCompleted() {}
  }
}
