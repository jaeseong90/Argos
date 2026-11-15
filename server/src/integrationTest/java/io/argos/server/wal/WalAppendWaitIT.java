/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.AgentServiceImpl;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.ThrottleAdvisor;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.batch.QuotaTrimmer;
import io.argos.server.collector.batch.RequiredEnumFilter;
import io.argos.server.collector.dedup.InMemoryDedupWindow;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.collector.limits.CollectorLimits;
import io.argos.server.collector.limits.InstanceQuotas;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AppRef;
import io.argos.server.session.ClockSync;
import io.argos.server.session.SessionStream;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 11 §10 "UNAVAILABLE: WAL fsync delay > 1 s" in real time (the unit tests scale the wait down,
 * ADR-0010 C-146 (2)): the collector over the real {@link FileWal} with its default append wait and
 * a disk that does not finish the fsync. The executor thread is released after about 1 s, nothing
 * is acked before the fsync, and the seq is stored exactly once (D-13, C-3).
 */
@Tag("slow")
@Tag("req:D-13")
@Tag("req:DSN-12-01")
@Tag("req:C-3")
class WalAppendWaitIT {

  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-SV-010r an fsync stuck past 1 s answers UNAVAILABLE after about 1 s with retry-after; a"
          + " resend meanwhile is UNAVAILABLE; once durable a resend is ACK_DUPLICATE and the WAL"
          + " holds the seq once")
  void slowFsyncInRealTime() throws Exception {
    assertThat(FileWal.DATA_APPEND_WAIT).isEqualTo(Duration.ofSeconds(1));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    FileWal wal = new FileWal(dir, 1 << 20, 1 << 24, 10, 0.8, meters, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    CollectorMetrics metrics = new CollectorMetrics(meters);
    InMemoryDedupWindow dedup = new InMemoryDedupWindow();
    AgentServiceImpl service =
        new AgentServiceImpl(
            new LimitsChecker(CollectorLimits.defaults()),
            dedup,
            new RequiredEnumFilter(metrics),
            new QuotaTrimmer(
                new InstanceQuotas(
                    new ArgosProperties.Collector.Quota(2_000, 20_000, 1_000, 5_000),
                    System::nanoTime)),
            wal,
            new NoopPipeline(),
            instanceId -> OptionalLong.empty(),
            appId -> 500,
            new ThrottleAdvisor(wal, () -> 0, 0.8),
            metrics,
            Clock.systemUTC());
    long now = System.currentTimeMillis();
    AgentSession session =
        new AgentSession(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-prefix",
            new AppRef(3, "order-api", "prod"),
            41,
            "host-a:8080",
            99L,
            IpMode.MASKED,
            UserIdMode.OFF,
            "1.0.0",
            "2.3.4",
            true,
            null,
            List.of(),
            now,
            new ClockSync(now, now),
            new SessionStream(new NoopObserver()));

    CountDownLatch release = new CountDownLatch(1);
    wal.beforeFsyncForTest(
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      long start = System.nanoTime();
      Result first = send(service, session, tx(1));
      long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(first.ack).as("no ack before the fsync").isNull();
      assertThat(first.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(Status.trailersFromThrowable(first.error).get(CollectorCalls.RETRY_AFTER_MS))
          .isEqualTo("1000");
      assertThat(heldMs)
          .as("the executor thread is held for the 1 s append wait, then released")
          .isGreaterThanOrEqualTo(1_000)
          .isLessThan(5_000);

      Result resend = send(service, session, tx(1));
      assertThat(resend.ack).isNull();
      assertThat(resend.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(meters.get("argos.collector.rejected").tag("reason", "wal_slow").counter().count())
          .isEqualTo(1);
      assertThat(
              meters
                  .get("argos.collector.rejected")
                  .tag("reason", "dedup_in_flight")
                  .counter()
                  .count())
          .isEqualTo(1);
    } finally {
      release.countDown();
      wal.beforeFsyncForTest(null);
    }

    DedupKey key = new DedupKey(41, 99, WalRecordType.TX, 1);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!dedup.seen(key) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(dedup.seen(key)).as("marked once the queued record is durable").isTrue();
    Result afterFsync = send(service, session, tx(1));
    assertThat(afterFsync.ack.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(afterFsync.ack.getLastSeq()).isEqualTo(1);
    Result next = send(service, session, tx(2));
    assertThat(next.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    wal.close();

    List<WalRecord> records = readAll(WalDirectory.open(dir));
    assertThat(records)
        .filteredOn(r -> r.type() == WalRecordType.TX)
        .extracting(WalRecord::seq)
        .as("seq 1 stored once")
        .containsExactly(1L, 2L);
  }

  private static TransactionBatch tx(long seq) {
    TransactionRecord r =
        TransactionRecord.newBuilder()
            .setTraceId(ByteString.copyFrom(new byte[16]))
            .setKind(TransactionKind.HTTP)
            .setServiceId(5)
            .build();
    return TransactionBatch.newBuilder()
        .setHeader(BatchHeader.newBuilder().setInstanceId(41).setAgentEpoch(99).setSeq(seq))
        .addRecords(r)
        .build();
  }

  private static Result send(AgentServiceImpl service, AgentSession session, TransactionBatch b) {
    Result result = new Result();
    Context.current()
        .withValue(CollectorCalls.SESSION, session)
        .run(() -> service.sendTransactions(Inbound.of(b), result));
    return result;
  }

  /** Every record of every segment of {@code dir}, in WAL order. */
  private static List<WalRecord> readAll(WalDirectory dir) throws IOException {
    List<WalRecord> records = new ArrayList<>();
    for (int id : dir.segmentIds()) {
      try (WalSegmentReader reader = WalSegmentReader.open(dir.segmentFile(id), id)) {
        reader.scan(WalFormat.SEGMENT_HEADER_BYTES, Long.MAX_VALUE, records::add);
      }
    }
    return records;
  }

  /** The outcome of one call. */
  private static final class Result implements StreamObserver<Ack> {
    Ack ack;
    Status status;
    Throwable error;

    @Override
    public void onNext(Ack value) {
      ack = value;
    }

    @Override
    public void onError(Throwable t) {
      error = t;
      status = Status.fromThrowable(t);
    }

    @Override
    public void onCompleted() {
      status = Status.OK;
    }
  }

  /** The pipeline is not under test: the WAL is read back directly. */
  private static final class NoopPipeline implements IngestPipeline {
    @Override
    public void onTransactions(SessionContext s, WalPosition p, TransactionBatch b) {}

    @Override
    public void onProfiles(SessionContext s, WalPosition p, ProfileBatch b) {}

    @Override
    public void onMetrics(SessionContext s, WalPosition p, MetricBatch b) {}

    @Override
    public void onStats(SessionContext s, WalPosition p, StatBatch b) {}

    @Override
    public void onActive(SessionContext s, ActiveTransactionSnapshot snap) {}
  }

  /** A session stream that accepts everything. */
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
