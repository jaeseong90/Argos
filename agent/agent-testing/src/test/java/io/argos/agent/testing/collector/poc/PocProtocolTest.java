/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector.poc;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.transportpoc.proto.AgentHello;
import io.argos.agent.transportpoc.proto.BatchHeader;
import io.argos.agent.transportpoc.proto.CounterDelta;
import io.argos.agent.transportpoc.proto.MetricBatch;
import io.argos.agent.transportpoc.proto.MetricKind;
import io.argos.agent.transportpoc.proto.MetricSample;
import io.argos.agent.transportpoc.proto.MetricSeries;
import io.argos.agent.transportpoc.proto.ProfileBatch;
import io.argos.agent.transportpoc.proto.ProfileStep;
import io.argos.agent.transportpoc.proto.SqlStat;
import io.argos.agent.transportpoc.proto.StatBatch;
import io.argos.agent.transportpoc.proto.StepType;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.proto.TransactionKind;
import io.argos.agent.transportpoc.proto.TransactionProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Conversion of PoC protocol batches into the records the assertion DSL reads. */
class PocProtocolTest {
  static ReceivedBatch batch(BatchKind kind, com.google.protobuf.Message body, long seq) {
    return new ReceivedBatch(
        kind, 7, 1, seq, Instant.EPOCH, body, body.getSerializedSize(), ReceivedBatch.ACKED);
  }

  @Test
  @DisplayName(
      "transactions: dictionary names, steps from profiles, env/version from the hello, optional"
          + " CPU stays missing")
  void transactions() {
    BatchHeader header = BatchHeader.newBuilder().setInstanceId(7).setSeq(1).build();
    TransactionBatch transactions =
        TransactionBatch.newBuilder()
            .setHeader(header)
            .addRecords(
                io.argos.agent.transportpoc.proto.TransactionRecord.newBuilder()
                    .setTxId(11)
                    .setTraceId(ByteString.copyFrom(new byte[] {1, 2}))
                    .setKind(TransactionKind.HTTP)
                    .setServiceId(100)
                    .setElapsedUs(2500)
                    .setHttpStatus(200)
                    .setHttpMethod("GET")
                    .setUrlPath("/api/orders/1")
                    .setRemoteIp(ByteString.copyFrom(new byte[] {127, 0, 0, 0}))
                    .setThreadNameId(101)
                    .setAllocBytes(0)
                    .setStreaming(true))
            .addRecords(
                io.argos.agent.transportpoc.proto.TransactionRecord.newBuilder()
                    .setTxId(12)
                    .setServiceId(999)
                    .setCpuTimeUs(1500))
            .build();
    ProfileBatch profiles =
        ProfileBatch.newBuilder()
            .setHeader(header)
            .addProfiles(
                TransactionProfile.newBuilder()
                    .setTxId(11)
                    .addSteps(
                        ProfileStep.newBuilder()
                            .setSeq(1)
                            .setType(StepType.METHOD)
                            .setNameId(102)
                            .setElapsedUs(2000))
                    .addSteps(
                        ProfileStep.newBuilder()
                            .setSeq(2)
                            .setParentSeq(1)
                            .setType(StepType.HTTP_CALL)
                            .setNameId(103)
                            .setStatus(500)
                            .setError(true)))
            .build();
    SessionInfo session =
        new SessionInfo(
            1,
            7,
            true,
            "shop",
            "test",
            "1.2.3",
            "0.0.0",
            Instant.EPOCH,
            AgentHello.getDefaultInstance());
    List<TransactionRecord> records =
        new PocProtocol()
            .transactions(
                List.of(
                    batch(BatchKind.TRANSACTIONS, transactions, 1),
                    batch(BatchKind.PROFILES, profiles, 1)),
                Map.of(
                    100L, "GET /api/orders/{id}", 101L, "http-nio-1", 102L, "handle", 103L, "ext"),
                Map.of(7L, session));
    assertThat(records).hasSize(2);
    TransactionRecord first = records.get(0);
    assertThat(first.source()).isEqualTo(PocProtocol.SOURCE);
    assertThat(first.field("trace_id")).isEqualTo("0102");
    assertThat(first.remoteIp()).isEqualTo("127.0.0.0");
    assertThat(first.flags()).containsExactly("streaming");
    assertThat(first)
        .hasKind("HTTP")
        .hasService("GET /api/orders/{id}")
        .hasStatus(200)
        .hasEnv("test")
        .hasVersion("1.2.3")
        .elapsedMs()
        .isEqualTo(2.5)
        .cpuTimeMs()
        .isMissing()
        .allocBytes()
        .isZero()
        .hasStepTree("METHOD handle", "  HTTP ext")
        .steps()
        .ofType("HTTP")
        .single()
        .isError()
        .hasParent(1);
    assertThat(first.thread()).isEqualTo("http-nio-1");
    assertThat(records.get(1).service()).isEqualTo("#999");
    assertThat(records.get(1)).cpuTimeMs().isEqualTo(1.5).steps().isEmpty();
  }

  @Test
  @DisplayName("statistics, counters and metric series (labels in the PoC series name)")
  void statsAndMetrics() {
    StatBatch stats =
        StatBatch.newBuilder()
            .addSql(SqlStat.newBuilder().setSqlId(5).setCount(3).setErrorCount(1).setSumUs(1500))
            .addSql(SqlStat.newBuilder().setSqlId(5).setCount(2).setSumUs(500))
            .addSql(SqlStat.newBuilder().setSqlId(6).setCount(1))
            .addCounters(CounterDelta.newBuilder().setName("orphan_steps_total").setDelta(2))
            .addCounters(CounterDelta.newBuilder().setName("orphan_steps_total").setDelta(1))
            .build();
    MetricBatch metrics =
        MetricBatch.newBuilder()
            .addSeries(
                MetricSeries.newBuilder()
                    .setIndex(1)
                    .setName("jdbc_pool_active{pool=\"HikariPool-1\"}")
                    .setKind(MetricKind.GAUGE))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(1).setTsMs(10).setValue(4))
            .build();
    PocProtocol protocol = new PocProtocol();
    List<ReceivedBatch> batches =
        List.of(batch(BatchKind.STATS, stats, 1), batch(BatchKind.METRICS, metrics, 1));
    assertThat(protocol.sqlStats(batches, Map.of(5L, "select ?")))
        .containsExactly(
            new Collected.SqlStat("select ?", 5, 1, 2.0), new Collected.SqlStat("#6", 1, 0, 0.0));
    assertThat(protocol.counters(batches)).containsEntry("orphan_steps_total", 3L);
    assertThat(protocol.metrics(batches))
        .containsExactly(
            new Collected.MetricPoint(
                "jdbc_pool_active", Map.of("pool", "HikariPool-1"), "GAUGE", 4, 10));
    assertThat(PocProtocol.stepType("HTTP_CALL")).isEqualTo("HTTP");
    assertThat(PocProtocol.stepType("MESSAGE_STEP")).isEqualTo("MESSAGE");
    assertThat(PocProtocol.labels("x")).isEmpty();
  }
}
