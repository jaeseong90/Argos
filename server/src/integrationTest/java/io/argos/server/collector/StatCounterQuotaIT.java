/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.StatBatch;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.collector.limits.QuotaKind;
import io.argos.server.config.ArgosProperties;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-013 for the window counters of {@code StatBatch} (SEC-12, 12 §3.6): each {@code
 * CounterDelta} becomes a {@code metric} row, so the counters are charged to the instance's metric
 * quota. Over the whole server (gRPC, WAL, ingest, the real ClickHouse), with a metric quota whose
 * bucket (200) is below the 500 counters one batch may carry (ADR-0010 C-157 (2)): a full batch is
 * {@code ACK_PARTIAL_DROP} with the removed count, at most the metric bucket's capacity of rows in
 * {@code metric} for that instance, a batch above 500 counters is refused as a whole, and another
 * instance's counters still load in full.
 */
@Tag("integration")
@Tag("req:SEC-12")
@Tag("req:D-06")
class StatCounterQuotaIT {

  /** The most counters one StatBatch may carry (C-157 (2)). */
  private static final int ATTACK_COUNTERS = LimitsChecker.MAX_STAT_COUNTERS_PER_BATCH;

  /**
   * {@code argos.collector.quota.metrics-per-5s} of this server: bucket 200, so that one batch of
   * the most counters allowed exceeds it.
   */
  private static final int METRICS_PER_5S = 100;

  /** The attack size this test used before C-157 (2), now above the cap: refused as a whole. */
  private static final int OVERSIZED_COUNTERS = 200_000;

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "T-SV-013 SEC-12 a StatBatch with 500 counters against a metric bucket of 200:"
          + " ACK_PARTIAL_DROP from the end, metric rows of the instance <= the bucket capacity,"
          + " 200,000 counters refused as a whole (C-157 (2)), another instance loads in full")
  void countersChargedToMetricQuota() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server =
            ArgosTestServer.start(
                env, Map.of("argos.collector.quota.metrics-per-5s", METRICS_PER_5S))) {
      AgentTokens.IssuedToken token;
      try (Connection c = env.connect(PgAccount.APP)) {
        token = AgentTokens.issue(c, AgentTokens.ensureApp(c, "counter-app", "prod"));
      }
      ArgosProperties.Collector.Quota quota =
          server.bean(ArgosProperties.class).collector().quota();
      long capacity = (long) QuotaKind.METRIC_SAMPLES.capacity(quota);
      assertThat(capacity).isEqualTo(2L * METRICS_PER_5S).isLessThan(ATTACK_COUNTERS);
      MeterRegistry meters = server.bean(MeterRegistry.class);
      double quotaDropsBefore = counter(meters);
      ClickHouseHttp reader = env.clickHouse(Account.READER);

      try (FakeAgent noisy = agent(env, server, token, "noisy");
          FakeAgent quiet = agent(env, server, token, "quiet")) {
        assertThat(noisy.connect().accepted()).isTrue();
        assertThat(quiet.connect().accepted()).isTrue();

        List<CounterDelta> counters = new ArrayList<>(ATTACK_COUNTERS);
        for (int i = 0; i < ATTACK_COUNTERS; i++) {
          counters.add(CounterDelta.newBuilder().setName("n" + i).setDelta(1).build());
        }
        StatBatch attack = noisy.batches().stats(window(noisy), List.of(), List.of(), counters);
        CallResult r = noisy.sendStats(attack);
        assertThat(r.ok()).as(r.toString()).isTrue();
        assertThat(r.ackStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
        long dropped = r.ack().getDroppedItems();
        assertThat(dropped)
            .as("a new bucket starts full: everything beyond its capacity is dropped")
            .isEqualTo(ATTACK_COUNTERS - capacity);
        assertThat(counter(meters) - quotaDropsBefore).isEqualTo((double) dropped);

        // Above the per-batch cap the batch is refused as a whole, before the quota (C-157 (2)).
        List<CounterDelta> oversized = new ArrayList<>(OVERSIZED_COUNTERS);
        for (int i = 0; i < OVERSIZED_COUNTERS; i++) {
          oversized.add(CounterDelta.newBuilder().setName("x" + i).setDelta(1).build());
        }
        CallResult refused =
            noisy.sendStats(noisy.batches().stats(window(noisy), List.of(), List.of(), oversized));
        assertThat(refused.code()).as(refused.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(refused.rejectReason()).isEqualTo("batch_items");
        assertThat(counter(meters) - quotaDropsBefore)
            .as("a refused batch is no quota drop")
            .isEqualTo((double) dropped);

        // Another instance of the same app keeps loading its counters in full.
        int quietBatches = 10;
        for (int b = 0; b < quietBatches; b++) {
          List<CounterDelta> few = new ArrayList<>();
          for (int i = 0; i < 3; i++) {
            few.add(CounterDelta.newBuilder().setName("q" + b + "_" + i).setDelta(1).build());
          }
          CallResult q =
              quiet.sendStats(quiet.batches().stats(window(quiet), List.of(), List.of(), few));
          assertThat(q.ackStatus()).as(q.toString()).isEqualTo(AckStatus.ACK_OK);
          assertThat(q.ack().getDroppedItems()).isZero();
        }

        long quietRows = awaitRows(reader, token.appId(), quiet.instanceId(), quietBatches * 3L);
        long noisyRows =
            awaitRows(reader, token.appId(), noisy.instanceId(), ATTACK_COUNTERS - dropped);
        String last =
            reader.scalar(
                "SELECT max(toUInt32(substring(name, length('argos_agent_n') + 1)))"
                    + " FROM argos.metric WHERE app_id = "
                    + token.appId()
                    + " AND instance_id = "
                    + noisy.instanceId());
        System.out.printf(
            Locale.ROOT,
            "T-SV-013 counters (reference, shared laptop): sent %d, dropped %d, noisy metric rows"
                + " %d (capacity %d, last kept n%s), quiet rows %d%n",
            ATTACK_COUNTERS,
            dropped,
            noisyRows,
            capacity,
            last,
            quietRows);
        assertThat(noisyRows).isEqualTo(ATTACK_COUNTERS - dropped).isLessThanOrEqualTo(capacity);
        assertThat(Long.parseLong(last))
            .as("kept from the start of the batch, removed from its end")
            .isEqualTo(capacity - 1);
        assertThat(quietRows).isEqualTo(quietBatches * 3L);
      }
    }
  }

  private static FakeAgent agent(
      ServerTestEnvironment env,
      ArgosTestServer server,
      AgentTokens.IssuedToken token,
      String instance) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName("counter-app")
        .instanceName(instance)
        .callDeadline(Duration.ofSeconds(60))
        .build();
  }

  /** The current 10 s window start of the agent's clock. */
  private static long window(FakeAgent agent) {
    long now = agent.clock().nowMs();
    return now - Math.floorMod(now, 10_000L);
  }

  /** Waits until {@code expected} metric rows of the instance are visible (at most 60 s). */
  private static long awaitRows(ClickHouseHttp reader, int appId, int instanceId, long expected)
      throws InterruptedException {
    String sql =
        "SELECT count() FROM argos.metric WHERE app_id = "
            + appId
            + " AND instance_id = "
            + instanceId;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    long rows = reader.scalarLong(sql);
    while (rows < expected && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(250);
      rows = reader.scalarLong(sql);
    }
    // A little longer: nothing beyond the expected rows may arrive.
    TimeUnit.SECONDS.sleep(2);
    return reader.scalarLong(sql);
  }

  private static double counter(MeterRegistry meters) {
    Counter c = meters.find("argos.collector.dropped").tag("reason", "quota").counter();
    return c == null ? 0 : c.count();
  }
}
