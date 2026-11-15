/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector.v1;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.proto.TransactionFlags;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Conversion of argos.v1 batches into the records the assertion DSL reads (18 §5.5). */
@Tag("req:DSN-18-04")
class V1ProtocolTest {
  static ReceivedBatch batch(BatchKind kind, com.google.protobuf.Message body, long seq) {
    return new ReceivedBatch(
        kind, 7, 1, seq, Instant.EPOCH, body, body.getSerializedSize(), ReceivedBatch.ACKED);
  }

  static ReceivedBatch refused(BatchKind kind, com.google.protobuf.Message body, String outcome) {
    return new ReceivedBatch(kind, 7, 1, 9, Instant.EPOCH, body, body.getSerializedSize(), outcome);
  }

  @Test
  @DisplayName(
      "transactions: dictionary names, steps and truncation from profiles, env/version from the"
          + " hello, C-69 flags, optional CPU stays missing (C-4), duplicates and refused batches"
          + " are not transactions")
  void transactions() {
    BatchHeader header = BatchHeader.newBuilder().setInstanceId(7).setSeq(1).build();
    TransactionBatch transactions =
        TransactionBatch.newBuilder()
            .setHeader(header)
            .addRecords(
                io.argos.proto.v1.TransactionRecord.newBuilder()
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
                    .setStreaming(true)
                    .setFlags(TransactionFlags.VIRTUAL_THREAD | TransactionFlags.SUMMARIZED)
                    .setProfileCaptured(true)
                    .setDbcWaitMs(4)
                    .addSqlIds(5)
                    .addHeaders(KeyValue.newBuilder().setKey("x-tenant").setValue("a"))
                    .setRequestId("req-1"))
            .addRecords(
                io.argos.proto.v1.TransactionRecord.newBuilder()
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
                    .setTruncated(true)
                    .setTotalSteps(1200)
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
                            .setFailure(FailureKind.HTTP_5XX)
                            .setRepeatCount(3)
                            .setError(true))
                    .addSteps(
                        ProfileStep.newBuilder()
                            .setSeq(3)
                            .setType(StepType.SUMMARY)
                            .setNameId(102)
                            .setStatus(StepType.HTTP_CALL_VALUE)
                            .setRepeatCount(40)
                            .setElapsedUs(9_000)
                            .setFetchUs(1_500)
                            .setRows(2)
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
        new V1Protocol()
            .transactions(
                List.of(
                    batch(BatchKind.TRANSACTIONS, transactions, 1),
                    batch(BatchKind.PROFILES, profiles, 1),
                    refused(BatchKind.TRANSACTIONS, transactions, ReceivedBatch.DUPLICATE),
                    refused(BatchKind.TRANSACTIONS, transactions, ReceivedBatch.SESSION_REQUIRED)),
                Map.of(
                    100L, "GET /api/orders/{id}", 101L, "http-nio-1", 102L, "handle", 103L, "ext"),
                Map.of(7L, session));
    assertThat(records).hasSize(2);
    TransactionRecord first = records.get(0);
    assertThat(first.source()).isEqualTo(V1Protocol.SOURCE);
    assertThat(first.field("trace_id")).isEqualTo("0102");
    assertThat(first.remoteIp()).isEqualTo("127.0.0.0");
    assertThat(first.flags()).containsExactly("virtual_thread", "summarized", "streaming");
    assertThat(first.xlogFlags()).isEqualTo(5);
    assertThat(first)
        .hasKind("HTTP")
        .hasService("GET /api/orders/{id}")
        .hasStatus(200)
        .hasEnv("test")
        .hasVersion("1.2.3")
        .hasFlag("virtual_thread")
        .isProfileCaptured()
        .elapsedMs()
        .isEqualTo(2.5)
        .cpuTimeMs()
        .isMissing()
        .allocBytes()
        .isZero()
        .totalSteps()
        .isEqualTo(1200L)
        .truncated()
        .isEqualTo(true)
        .field("dbc_wait_ms")
        .isEqualTo(4L)
        .field("request_id")
        .isEqualTo("req-1")
        .hasStepTree("METHOD handle", "  HTTP ext")
        .steps()
        .ofType("HTTP")
        .single()
        .isError()
        .hasParent(0);
    // The JSONL numbering (wire seq - 1, root -1), so a test reads the same on both sources.
    assertThat(first.steps()).extracting(step -> step.seq()).containsExactly(0, 1);
    assertThat(first.steps()).extracting(step -> step.parent()).containsExactly(-1, 0);
    // SUMMARY steps are summary[] entries, as in the JSONL (agent encoding of BatchAssembler).
    assertThat(first.summary())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo("HTTP");
              assertThat(entry.name()).isEqualTo("handle");
              assertThat(entry.count()).isEqualTo(40);
              assertThat(entry.totalMs()).isEqualTo(9.0);
              assertThat(entry.maxMs()).isEqualTo(1.5);
              assertThat(entry.errors()).isEqualTo(2);
            });
    assertThat(records.get(1).summary()).isEmpty();
    assertThat(first.field("headers")).isEqualTo(Map.of("x-tenant", "a"));
    assertThat(first.field("sql_ids")).isEqualTo(List.of("5"));
    assertThat(first.steps().get(1).fields()).containsEntry("failure", "HTTP_5XX");
    assertThat(first.steps().get(1).fields()).containsEntry("repeat_count", 3L);
    assertThat(first.thread()).isEqualTo("http-nio-1");
    assertThat(first.threadName()).isEqualTo("http-nio-1");
    assertThat(records.get(1).service()).isEqualTo("#999");
    assertThat(records.get(1)).cpuTimeMs().isEqualTo(1.5).steps().isEmpty();
    assertThat(records.get(1).has("total_steps")).isFalse();
  }

  /** A sampled transaction with these dictionary ids, optionally with its profile batch. */
  private static boolean complete(boolean profileArrived, Map<Long, String> dictionary) {
    BatchHeader header = BatchHeader.newBuilder().setInstanceId(7).setSeq(1).build();
    TransactionBatch transactions =
        TransactionBatch.newBuilder()
            .setHeader(header)
            .addRecords(
                io.argos.proto.v1.TransactionRecord.newBuilder()
                    .setTxId(11)
                    .setServiceId(100)
                    .setProfileCaptured(true))
            .build();
    ProfileBatch profiles =
        ProfileBatch.newBuilder()
            .setHeader(header)
            .addProfiles(
                TransactionProfile.newBuilder()
                    .setTxId(11)
                    .setTotalSteps(1)
                    .addSteps(
                        ProfileStep.newBuilder().setSeq(1).setType(StepType.SQL).setNameId(102)))
            .build();
    List<ReceivedBatch> batches =
        profileArrived
            ? List.of(
                batch(BatchKind.TRANSACTIONS, transactions, 1),
                batch(BatchKind.PROFILES, profiles, 1))
            : List.of(batch(BatchKind.TRANSACTIONS, transactions, 1));
    V1Protocol protocol = new V1Protocol();
    return protocol.complete(protocol.transactions(batches, dictionary, Map.of()).get(0));
  }

  @Test
  @DisplayName(
      "complete(): a sampled transaction waits for its profile batch (11 §3) and for every"
          + " dictionary text it names (11 §7: up to 100 ms after the data batch)")
  void complete() {
    Map<Long, String> names = Map.of(100L, "GET /a", 102L, "select ?");
    assertThat(complete(false, names)).as("no profile yet").isFalse();
    assertThat(complete(true, Map.of(102L, "select ?"))).as("no service text yet").isFalse();
    assertThat(complete(true, Map.of(100L, "GET /a"))).as("no step text yet").isFalse();
    assertThat(complete(true, names)).isTrue();
  }

  @Test
  @DisplayName("statistics, counters and metric series (labels in the OpenMetrics series name)")
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
    V1Protocol protocol = new V1Protocol();
    List<ReceivedBatch> batches =
        List.of(
            batch(BatchKind.STATS, stats, 1),
            batch(BatchKind.METRICS, metrics, 1),
            refused(BatchKind.STATS, stats, ReceivedBatch.DUPLICATE));
    assertThat(protocol.sqlStats(batches, Map.of(5L, "select ?")))
        .containsExactly(
            new Collected.SqlStat("select ?", 5, 1, 2.0), new Collected.SqlStat("#6", 1, 0, 0.0));
    assertThat(protocol.counters(batches)).containsEntry("orphan_steps_total", 3L);
    assertThat(protocol.metrics(batches))
        .containsExactly(
            new Collected.MetricPoint(
                "jdbc_pool_active", Map.of("pool", "HikariPool-1"), "GAUGE", 4, 10));
    assertThat(V1Protocol.stepType("HTTP_CALL")).isEqualTo("HTTP");
    assertThat(V1Protocol.stepType("MESSAGE_STEP")).isEqualTo("MESSAGE");
    assertThat(V1Protocol.stepType("SUMMARY")).isEqualTo("SUMMARY");
    assertThat(V1Protocol.stepType("STEP_TYPE_UNSPECIFIED")).isNull();
    assertThat(V1Protocol.labels("x")).isEmpty();
    assertThat(V1Protocol.baseName("a{b=\"c\"}")).isEqualTo("a");
  }

  @Test
  @DisplayName(
      "C-1: every argos.v1 StepType maps to the agent's StepType name of the same number (the"
          + " bootstrap names of 02 §7: SQL=1 ... SUMMARY=11)")
  void stepTypeNames() {
    List<String> agentNames =
        List.of(
            "SQL",
            "DBC",
            "COMMIT",
            "ROLLBACK",
            "HTTP",
            "METHOD",
            "ASYNC",
            "ERROR",
            "STACK",
            "MESSAGE",
            "SUMMARY");
    for (StepType type : StepType.values()) {
      if (type == StepType.UNRECOGNIZED || type == StepType.STEP_TYPE_UNSPECIFIED) {
        continue;
      }
      assertThat(V1Protocol.stepType(type.name()))
          .as(type.name())
          .isEqualTo(agentNames.get(type.getNumber() - 1));
    }
  }
}
