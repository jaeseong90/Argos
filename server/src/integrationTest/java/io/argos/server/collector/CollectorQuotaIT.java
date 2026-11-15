/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-013: an instance above its quota gets {@code ACK_PARTIAL_DROP} with {@code dropped_items},
 * the dropped items are the last ones of the batch and are never written, {@code
 * argos_collector_dropped_total{reason="quota"}} counts them, and a second instance's acks and
 * latency are not affected (DSN-12-04, ADR-0010 C-130 (1)). Real time on a shared laptop: the
 * latency bound is generous and tagged slow (C-146 (2)).
 */
@Tag("integration")
@Tag("slow")
@Tag("req:SEC-12")
@Tag("req:D-06")
class CollectorQuotaIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness collector;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector = CollectorHarness.start(env);
  }

  @AfterAll
  static void stop() {
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static TransactionBatch batch(FakeAgent agent, int records) {
    List<TransactionRecord> list = new ArrayList<>(records);
    for (int i = 0; i < records; i++) {
      list.add(agent.records().transaction().setRequestId("r" + i).build());
    }
    return agent.batches().transactions(list);
  }

  @Test
  @DisplayName(
      "T-SV-013 quota on one instance: ACK_PARTIAL_DROP from the end, reason=quota counted; another instance unaffected")
  void quotaIsolation() throws Exception {
    String app = "quota-" + UUID.randomUUID().toString().substring(0, 8);
    String token = collector.token(app, "prod").token();
    try (FakeAgent noisy = collector.agent(token).appName(app).instanceName("noisy").build();
        FakeAgent quiet = collector.agent(token).appName(app).instanceName("quiet").build()) {
      assertThat(noisy.connect().accepted()).isTrue();
      assertThat(quiet.connect().accepted()).isTrue();

      // Baseline latency of the quiet instance.
      List<Long> before = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        CallResult r = quiet.sendTransactions(batch(quiet, 10));
        assertThat(r.ackStatus()).isEqualTo(AckStatus.ACK_OK);
        before.add(r.latencyNanos());
      }

      AtomicBoolean flooding = new AtomicBoolean(true);
      CompletableFuture<List<CallResult>> flood =
          CompletableFuture.supplyAsync(
              () -> {
                List<CallResult> results = new ArrayList<>();
                while (flooding.get() && results.size() < 30) {
                  results.add(noisy.sendTransactions(batch(noisy, 3_000)));
                }
                return results;
              });
      List<CallResult> during = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        during.add(quiet.sendTransactions(batch(quiet, 10)));
      }
      flooding.set(false);
      List<CallResult> noisyResults = flood.get(120, TimeUnit.SECONDS);

      assertThat(during).allSatisfy(r -> assertThat(r.ackStatus()).isEqualTo(AckStatus.ACK_OK));
      assertThat(during).allSatisfy(r -> assertThat(r.ack().getDroppedItems()).isZero());
      long maxDuringMs =
          during.stream().mapToLong(CallResult::latencyNanos).max().orElseThrow() / 1_000_000;
      assertThat(maxDuringMs)
          .as("the quiet instance's slowest ack during the flood")
          .isLessThan(2_000);

      long dropped =
          noisyResults.stream()
              .filter(r -> r.ackStatus() == AckStatus.ACK_PARTIAL_DROP)
              .mapToLong(r -> r.ack().getDroppedItems())
              .sum();
      assertThat(dropped).as("dropped by the quota").isPositive();
      MeterRegistry meters = collector.bean(MeterRegistry.class);
      assertThat(meters.get("argos.collector.dropped").tag("reason", "quota").counter().count())
          .isEqualTo((double) dropped);

      // The kept records are the first ones of each batch (DSN-12-04).
      CallResult partial =
          noisyResults.stream()
              .filter(r -> r.ackStatus() == AckStatus.ACK_PARTIAL_DROP)
              .findFirst()
              .orElseThrow();
      int kept = 3_000 - partial.ack().getDroppedItems();
      var record =
          collector.wal().appended(WalRecordType.TX).stream()
              .filter(
                  a ->
                      a.seq() == partial.ack().getLastSeq() && a.instanceId() == noisy.instanceId())
              .findFirst()
              .orElseThrow();
      TransactionBatch stored =
          TransactionBatch.parseFrom(WalDataPayload.decode(record.payload().toByteArray()).batch());
      assertThat(stored.getRecordsCount()).isEqualTo(kept);
      if (kept > 0) {
        assertThat(stored.getRecords(kept - 1).getRequestId()).isEqualTo("r" + (kept - 1));
      }
      System.out.printf(
          "T-SV-013 reference (shared laptop): quiet p_max before %d ms, during %d ms; noisy calls %d,"
              + " dropped %d%n",
          before.stream().mapToLong(Long::longValue).max().orElseThrow() / 1_000_000,
          maxDuringMs,
          noisyResults.size(),
          dropped);
    }
  }
}
