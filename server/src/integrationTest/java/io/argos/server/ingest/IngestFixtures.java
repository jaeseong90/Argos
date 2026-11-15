/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.ExtStat;
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
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.session.api.ClockOffsetProvider;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.api.Table;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Batches, sessions and ClickHouse helpers of the ingest integration tests. */
final class IngestFixtures {

  private IngestFixtures() {}

  /** A session context as the collector stamps it at receipt. */
  static SessionContext session(
      int appId, int instanceId, long receivedMs, long correctionMs, int apdexTMs, String version) {
    return new SessionContext(
        appId,
        instanceId,
        "prod",
        UUID.nameUUIDFromBytes(
            ("session-" + instanceId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        1000L + instanceId,
        IpMode.MASKED,
        UserIdMode.HASHED,
        apdexTMs,
        receivedMs,
        version,
        correctionMs);
  }

  /** A transaction id whose time prefix is {@code startMs} (DSN-02-09: start = tx_id >> 23). */
  static long txId(long startMs, int n) {
    return (startMs << 23) | (n & 0x7FFFFF);
  }

  static byte[] traceId(long txId) {
    return ByteBuffer.allocate(16).putLong(txId).putLong(~txId).array();
  }

  /** One HTTP transaction. */
  static TransactionRecord.Builder tx(
      long txId, long startMs, long elapsedUs, int sqlCount, int extCount) {
    return TransactionRecord.newBuilder()
        .setTxId(txId)
        .setTraceId(ByteString.copyFrom(traceId(txId)))
        .setSpanId(txId ^ 0x5555)
        .setKind(TransactionKind.HTTP)
        .setServiceId(0x1111)
        .setStartTsMs(startMs)
        .setElapsedUs(elapsedUs)
        .setHttpMethod("GET")
        .setHttpStatus(200)
        .setUrlPath("/api/orders")
        .setRemoteIp(ByteString.copyFrom(new byte[] {10, 1, 2, 0}))
        .setUserId("0123456789abcdef")
        .setSqlCount(sqlCount)
        .setSqlTimeMs(sqlCount * 2)
        .setExtCount(extCount)
        .setExtTimeMs(extCount * 3)
        .setThreadNameId(0x2222);
  }

  static TransactionBatch txBatch(List<TransactionRecord> records) {
    return TransactionBatch.newBuilder().addAllRecords(records).build();
  }

  static TransactionBatch txBatch(TransactionRecord.Builder... records) {
    TransactionBatch.Builder b = TransactionBatch.newBuilder();
    for (TransactionRecord.Builder r : records) {
      b.addRecords(r);
    }
    return b.build();
  }

  /** A profile of one transaction with one SQL step. */
  static ProfileBatch profile(long txId, long startMs) {
    return ProfileBatch.newBuilder()
        .addProfiles(
            TransactionProfile.newBuilder()
                .setTxId(txId)
                .setStartTsMs(startMs)
                .setKind(TransactionKind.HTTP)
                .setTotalSteps(1)
                .addSteps(
                    ProfileStep.newBuilder()
                        .setSeq(1)
                        .setType(StepType.SQL)
                        .setElapsedUs(800)
                        .setFetchUs(100)
                        .setNameId(0x3333)
                        .setTargetId(0x4444)))
        .build();
  }

  /** A statistics window with one SQL and one outgoing call statistic. */
  static StatBatch stats(long windowStartMs, int sqlCount, int extCount) {
    List<Integer> buckets = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      buckets.add(i == 0 ? sqlCount : 0);
    }
    StatBatch.Builder b = StatBatch.newBuilder().setWindowStartMs(windowStartMs).setWindowLenS(10);
    if (sqlCount > 0) {
      b.addSql(
          SqlStat.newBuilder()
              .setSqlId(0x3333)
              .setDbId(0x4444)
              .setKind(TransactionKind.HTTP)
              .setCount(sqlCount)
              .setSumUs(sqlCount * 700L)
              .setMaxUs(900)
              .addAllBuckets(buckets));
    }
    if (extCount > 0) {
      List<Integer> ext = new ArrayList<>(buckets);
      ext.set(0, extCount);
      b.addExt(
          ExtStat.newBuilder()
              .setHostId(0x5555)
              .setKind(TransactionKind.HTTP)
              .setCount(extCount)
              .setSumUs(extCount * 1_000L)
              .setMaxUs(1_000)
              .addAllBuckets(ext));
    }
    return b.build();
  }

  /** One gauge sample. */
  static MetricBatch metric(long tsMs, double value) {
    return MetricBatch.newBuilder()
        .addSeries(
            MetricSeries.newBuilder()
                .setIndex(0)
                .setName("jvm_threads_live")
                .setKind(MetricKind.GAUGE))
        .addSamples(MetricSample.newBuilder().setSeriesIndex(0).setTsMs(tsMs).setValue(value))
        .build();
  }

  /** The five raw tables the writer feeds (12 §3.5, §3.6). */
  static final List<Table> RAW_TABLES =
      List.of(Table.XLOG, Table.PROFILE_STEP, Table.SQL_STAT_RAW, Table.EXT_STAT_RAW, Table.METRIC);

  /**
   * One instance whose agent clock is {@code skewMs} ahead (ADR-0009 C-84, ADR-0010 C-129) and what
   * it sends, in true (server) time: a transaction of 1 s from {@code trueStartMs} with its
   * profile, received at {@code txReceivedMs}; a 10 s statistics window from {@code trueWindowMs}
   * (one SQL and one outgoing-call statistic) and a gauge sample 5 s into it, received at {@code
   * statReceivedMs}. The agent stamps every time with its clock (true + skew); the server's median
   * offset is {@code -skewMs} (11 §9: server minus agent), which decides the correction.
   */
  record SkewedRun(
      int instanceId,
      long skewMs,
      long trueStartMs,
      long txReceivedMs,
      long trueWindowMs,
      long statReceivedMs) {

    /** Elapsed time of the transaction (1 s). */
    static final long ELAPSED_US = 1_000_000;

    /**
     * The transaction ends 0.5 s before the agent's midnight {@code m} (agent clock) and is
     * received 0.5 s after its end; the window ends at that midnight and is received 11.5 s after
     * it.
     */
    static SkewedRun beforeAgentMidnight(int instanceId, long skewMs, long m) {
      long trueStart = m - 1_500 - skewMs;
      long txReceived = trueStart + 2_000;
      return new SkewedRun(
          instanceId, skewMs, trueStart, txReceived, m - 10_000 - skewMs, txReceived + 11_000);
    }

    /**
     * Everything happens just before the true UTC midnight {@code m}: the transaction ends at m - 3
     * s and is received at m - 2.5 s, the window is [m - 10 s, m) and is received at m + 1 s. With
     * the clamp alone (before C-84) the window and the sample would land on m's day, the
     * transaction on the day before.
     */
    static SkewedRun beforeTrueMidnight(int instanceId, long skewMs, long m) {
      return new SkewedRun(instanceId, skewMs, m - 4_000, m - 2_500, m - 10_000, m + 1_000);
    }

    /** The correction the collector decides at receipt (C-129 (1), (2)). */
    long correctionMs() {
      return ClockOffsetProvider.correctionMs(OptionalLong.of(-skewMs));
    }

    long txId() {
      return IngestFixtures.txId(trueStartMs, instanceId);
    }

    long trueMetricMs() {
      return trueWindowMs + 5_000;
    }

    long agentStartMs() {
      return trueStartMs + skewMs;
    }

    long agentWindowMs() {
      return trueWindowMs + skewMs;
    }

    long agentMetricMs() {
      return trueMetricMs() + skewMs;
    }
  }

  /**
   * Appends what {@code run} sends (TX, PROFILE, STAT, METRIC records, seq 1 each) as the collector
   * does after its checks: the session's correction goes to the record headers.
   */
  static void sendSkewed(IngestStack stack, int appId, SkewedRun run) throws Exception {
    long correction = run.correctionMs();
    SessionContext s = session(appId, run.instanceId(), run.txReceivedMs(), correction, 500, "");
    stack.append(
        WalRecordType.TX,
        s,
        txBatch(tx(run.txId(), run.agentStartMs(), SkewedRun.ELAPSED_US, 3, 1)),
        1);
    stack.append(WalRecordType.PROFILE, s, profile(run.txId(), run.agentStartMs()), 1);
    SessionContext later =
        session(appId, run.instanceId(), run.statReceivedMs(), correction, 500, "");
    stack.append(WalRecordType.STAT, later, stats(run.agentWindowMs(), 3, 1), 1);
    stack.append(WalRecordType.METRIC, later, metric(run.agentMetricMs(), 7), 1);
  }

  static ClickHouseHttp reader(ServerTestEnvironment env) {
    return env.clickHouse(Account.READER);
  }

  static ClickHouseHttp migrator(ServerTestEnvironment env) {
    return env.clickHouse(Account.MIGRATOR);
  }

  /** Polls {@code condition} for up to {@code seconds}. */
  static boolean eventually(int seconds, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(200);
    }
    return condition.getAsBoolean();
  }
}
