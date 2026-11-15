/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.batch.BindValueRedactor;
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
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link AgentServiceImpl} with a fake WAL and pipeline: the 12 §3.2 order, the durability contract
 * (D-13, DSN-12-01: no ack before the WAL append returned, except the WAL-full answer of
 * DSN-11-09), deduplication, quota and enum drops, the C-129 correction decided at receipt, the
 * throttle hint and the volatile active snapshot.
 */
@Tag("req:D-13")
@Tag("req:DSN-12-01")
class AgentServiceImplTest {

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CollectorMetrics metrics = new CollectorMetrics(meters);
  private final InMemoryDedupWindow dedup = new InMemoryDedupWindow();
  private final FakeWal wal = new FakeWal();
  private final RecordingPipeline pipeline = new RecordingPipeline();
  private final AtomicLong correction = new AtomicLong();
  private final AtomicLong nanos = new AtomicLong();
  private final AgentServiceImpl service =
      service(new ArgosProperties.Collector.Quota(2_000, 20_000, 1_000, 5_000));

  /** The service over this test's fakes with its own quota buckets ({@code quota}). */
  private AgentServiceImpl service(ArgosProperties.Collector.Quota quota) {
    return new AgentServiceImpl(
        new LimitsChecker(CollectorLimits.defaults()),
        dedup,
        new RequiredEnumFilter(metrics),
        new QuotaTrimmer(new InstanceQuotas(quota, nanos::get)),
        wal,
        pipeline,
        instanceId ->
            correction.get() == 0
                ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(-correction.get()),
        appId -> 750,
        new ThrottleAdvisor(wal, () -> 0, 0.8),
        metrics,
        clock);
  }

  private final AgentSession session =
      new AgentSession(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "argos_AbCdEfGh",
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
          clock.millis(),
          new ClockSync(clock.millis(), clock.millis()),
          new SessionStream(new NoopObserver()));

  private static TransactionBatch tx(long seq, int records) {
    TransactionRecord r =
        TransactionRecord.newBuilder()
            .setTraceId(ByteString.copyFrom(new byte[16]))
            .setKind(TransactionKind.HTTP)
            .setServiceId(5)
            .build();
    return TransactionBatch.newBuilder()
        .setHeader(BatchHeader.newBuilder().setInstanceId(41).setAgentEpoch(99).setSeq(seq))
        .addAllRecords(Collections.nCopies(records, r))
        .build();
  }

  private Result send(TransactionBatch batch) {
    Result result = new Result(events);
    Context.current()
        .withValue(CollectorCalls.SESSION, session)
        .run(() -> service.sendTransactions(Inbound.of(batch), result));
    return result;
  }

  @Test
  @DisplayName(
      "T-SV-010n the ack is sent only after the WAL append returned, then the pipeline is fed")
  void ackAfterAppend() {
    Result r = send(tx(1, 3));
    assertThat(events).containsExactly("wal-append", "pipeline", "ack");
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack.getLastSeq()).isEqualTo(1);
    assertThat(r.ack.getDroppedItems()).isZero();
    assertThat(r.ack.hasThrottle()).isFalse();
    FakeWal.Appended rec = wal.records.get(0);
    assertThat(rec.type).isEqualTo(WalRecordType.TX);
    assertThat(rec.instanceId).isEqualTo(41);
    assertThat(rec.epoch).isEqualTo(99);
    assertThat(rec.seq).isEqualTo(1);
    assertThat(rec.receivedMs).isEqualTo(clock.millis());
    WalDataPayload payload = WalDataPayload.decode(rec.payload.toByteArray());
    assertThat(payload.apdexTMs()).isEqualTo(750);
    assertThat(payload.appVersion()).isEqualTo("2.3.4");
    SessionContext ctx = pipeline.contexts.get(0);
    assertThat(ctx).isEqualTo(payload.context(41, 99, rec.receivedMs, rec.tsCorrectionMs));
    assertThat(pipeline.positions.get(0)).isEqualTo(rec.position);
    assertThat(meters.get("argos.collector.received").tag("type", "transactions").counter().count())
        .isEqualTo(3);
  }

  @Test
  @Tag("req:C-3")
  @DisplayName("T-ACC-13a a written seq is ACK_DUPLICATE with last_seq, nothing written again")
  void duplicate() {
    send(tx(1, 1));
    send(tx(2, 1));
    events.clear();
    Result r = send(tx(1, 1));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(r.ack.getLastSeq()).isEqualTo(2);
    assertThat(events).containsExactly("ack");
    assertThat(wal.records).hasSize(2);
  }

  @Test
  @DisplayName(
      "DSN-11-09 a full WAL answers ACK_OK with every item dropped, writes nothing, and the seq stays new")
  void walFull() {
    wal.full = true;
    Result r = send(tx(1, 3));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack.getDroppedItems()).isEqualTo(3);
    assertThat(r.ack.getLastSeq()).isZero();
    assertThat(pipeline.contexts).isEmpty();
    assertThat(meters.get("argos.collector.dropped").tag("reason", "wal_full").counter().count())
        .isEqualTo(3);
    wal.full = false;
    Result resend = send(tx(1, 3));
    assertThat(resend.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack.getLastSeq()).isEqualTo(1);
    assertThat(resend.ack.getDroppedItems()).isZero();
  }

  @Test
  @DisplayName(
      "D-13 an undurable WAL write is UNAVAILABLE without an ack, and the retry is written")
  void walFailure() {
    wal.failing = true;
    Result r = send(tx(1, 1));
    assertThat(r.ack).isNull();
    assertThat(r.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(pipeline.contexts).isEmpty();
    assertThat(dedup.seen(new DedupKey(41, 99, WalRecordType.TX, 1))).isFalse();
    wal.failing = false;
    assertThat(send(tx(1, 1)).ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
  }

  @Test
  @DisplayName(
      "T-SV-012 unit: a limit violation rejects the batch with the reason trailer; nothing written")
  void limitViolation() {
    Result r = send(tx(1, 5_001));
    assertThat(r.status.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(Status.trailersFromThrowable(r.error).get(CollectorCalls.REJECT_REASON))
        .isEqualTo("batch_items");
    assertThat(wal.records).isEmpty();
    assertThat(
            meters.get("argos.collector.rejected").tag("reason", "batch_items").counter().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-SV-013 unit: items beyond the quota are removed from the end: ACK_PARTIAL_DROP, only the kept items in the WAL")
  void quota() throws Exception {
    send(tx(1, 4_000));
    Result r = send(tx(2, 300));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(r.ack.getDroppedItems()).isEqualTo(300);
    assertThat(r.ack.getLastSeq()).isEqualTo(2);
    TransactionBatch stored =
        TransactionBatch.parseFrom(
            WalDataPayload.decode(wal.records.get(1).payload.toByteArray()).batch());
    assertThat(stored.getRecordsCount()).isZero();
    assertThat(meters.get("argos.collector.dropped").tag("reason", "quota").counter().count())
        .isEqualTo(300);
  }

  @Test
  @DisplayName(
      "C-143 (1) unit: an item with an unknown kind is dropped: ACK_PARTIAL_DROP with the count")
  void unknownEnum() throws Exception {
    TransactionBatch batch =
        tx(1, 2).toBuilder()
            .addRecords(tx(1, 1).getRecords(0).toBuilder().setKindValue(77))
            .build();
    Result r = send(batch);
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(r.ack.getDroppedItems()).isEqualTo(1);
    TransactionBatch stored =
        TransactionBatch.parseFrom(
            WalDataPayload.decode(wal.records.get(0).payload.toByteArray()).batch());
    assertThat(stored.getRecordsCount()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "C-129 (3) the correction is decided at receipt and written to the WAL header with the received time")
  void correctionAtReceipt() {
    correction.set(60_000);
    send(tx(1, 1));
    assertThat(wal.records.get(0).tsCorrectionMs).isEqualTo(60_000);
    assertThat(pipeline.contexts.get(0).tsCorrectionMs()).isEqualTo(60_000);
    correction.set(3_000);
    send(tx(2, 1));
    assertThat(wal.records.get(1).tsCorrectionMs).as("a 3 s lead is not corrected").isZero();
  }

  @Test
  @DisplayName("12 §3.2 above the WAL warn ratio the ack carries a Throttle send_backoff_ms hint")
  void throttle() {
    wal.usage = 0.9;
    Result r = send(tx(1, 1));
    assertThat(r.ack.hasThrottle()).isTrue();
    assertThat(r.ack.getThrottle().getSendBackoffMs()).isBetween(250, 5_000);
  }

  @Test
  @DisplayName(
      "12 §3.2 a resend while the first call waits for its fsync is UNAVAILABLE and not written twice")
  void inFlight() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    wal.hold =
        () -> {
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
        };
    CompletableFuture<Result> first = CompletableFuture.supplyAsync(() -> send(tx(1, 1)));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    wal.hold = null;
    Result second = send(tx(1, 1));
    assertThat(second.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS).ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(wal.records).hasSize(1);
    assertThat(send(tx(1, 1)).ack.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
  }

  @Test
  @Tag("req:C-3")
  @DisplayName(
      "T-SV-010p 11 §10 an fsync slower than the append wait is UNAVAILABLE with retry-after; the"
          + " seq stays claimed until the record is durable, then a resend is ACK_DUPLICATE"
          + " (one record)")
  void slowFsyncThenDurable() {
    DedupKey key = new DedupKey(41, 99, WalRecordType.TX, 1);
    CompletableFuture<WalPosition> fsync = new CompletableFuture<>();
    wal.slow = fsync;
    Result first = send(tx(1, 2));
    wal.slow = null;
    assertThat(first.ack).isNull();
    assertThat(first.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(Status.trailersFromThrowable(first.error).get(CollectorCalls.RETRY_AFTER_MS))
        .isEqualTo(Long.toString(AgentServiceImpl.WAL_SLOW_RETRY_AFTER_MS));
    assertThat(meters.get("argos.collector.rejected").tag("reason", "wal_slow").counter().count())
        .isEqualTo(1);
    assertThat(dedup.seen(key)).as("not durable yet").isFalse();
    assertThat(events).as("no ack, no pipeline before the fsync").containsExactly("wal-queued");

    Result whilePending = send(tx(1, 2));
    assertThat(whilePending.ack).isNull();
    assertThat(whilePending.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(
            meters
                .get("argos.collector.rejected")
                .tag("reason", "dedup_in_flight")
                .counter()
                .count())
        .isEqualTo(1);

    WalPosition position = new WalPosition(1, 0, 100);
    fsync.complete(position);
    assertThat(dedup.seen(key)).as("marked once durable").isTrue();
    assertThat(pipeline.positions).containsExactly(position);
    assertThat(events).containsExactly("wal-queued", "pipeline");

    Result after = send(tx(1, 2));
    assertThat(after.ack.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(after.ack.getLastSeq()).isEqualTo(1);
    assertThat(wal.records).as("appended once").hasSize(1);
  }

  @Test
  @Tag("req:C-3")
  @DisplayName(
      "T-SV-010q 11 §10 a slow record that is then not written releases its seq: the resend is"
          + " written and acked")
  void slowFsyncThenFailed() {
    DedupKey key = new DedupKey(41, 99, WalRecordType.TX, 1);
    CompletableFuture<WalPosition> fsync = new CompletableFuture<>();
    wal.slow = fsync;
    Result first = send(tx(1, 1));
    wal.slow = null;
    assertThat(first.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(send(tx(1, 1)).status.getCode())
        .as("still in flight")
        .isEqualTo(Status.Code.UNAVAILABLE);

    fsync.completeExceptionally(new WalWriteException("disk error", null));
    assertThat(dedup.seen(key)).isFalse();
    assertThat(pipeline.contexts).isEmpty();

    Result resend = send(tx(1, 1));
    assertThat(resend.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack.getLastSeq()).isEqualTo(1);
    assertThat(wal.records).as("the failed append and the written resend").hasSize(2);
    assertThat(dedup.seen(key)).isTrue();
  }

  @Test
  @Tag("req:C-3")
  @Tag("req:SEC-11")
  @DisplayName(
      "T-SV-010t 11 §10 a batch the WAL refuses as busy (fsync stuck past the append wait, or the"
          + " waiting bytes at their limit) is UNAVAILABLE with retry-after and no ack, counted as"
          + " wal_slow, and its resend is new")
  void walBusy() {
    DedupKey key = new DedupKey(41, 99, WalRecordType.TX, 1);
    wal.busy = true;
    Result first = send(tx(1, 2));
    assertThat(first.ack).as("no ack").isNull();
    assertThat(first.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(Status.trailersFromThrowable(first.error).get(CollectorCalls.RETRY_AFTER_MS))
        .isEqualTo(Long.toString(AgentServiceImpl.WAL_SLOW_RETRY_AFTER_MS))
        .isEqualTo("1000");
    assertThat(meters.get("argos.collector.rejected").tag("reason", "wal_slow").counter().count())
        .isEqualTo(1);
    assertThat(dedup.seen(key)).as("nothing durable").isFalse();
    assertThat(wal.records).as("nothing queued or written").isEmpty();
    assertThat(pipeline.contexts).isEmpty();
    assertThat(events).containsExactly("wal-busy");

    // The claim was released: the resend of the same seq is NEW (not IN_FLIGHT, not a duplicate).
    wal.busy = false;
    events.clear();
    Result resend = send(tx(1, 2));
    assertThat(resend.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack.getLastSeq()).isEqualTo(1);
    assertThat(resend.ack.getDroppedItems()).isZero();
    assertThat(events).containsExactly("wal-append", "pipeline", "ack");
    assertThat(wal.records).hasSize(1);
    assertThat(dedup.seen(key)).isTrue();
    assertThat(meters.get("argos.collector.rejected").tag("reason", "wal_slow").counter().count())
        .as("counted once")
        .isEqualTo(1);
    assertThat(meters.find("argos.collector.rejected").tag("reason", "dedup_in_flight").counter())
        .as("the resend was not in flight")
        .isNull();
  }

  /** A way the WAL leaves a batch unwritten after its quota was taken (N-1). */
  enum Unwritten {
    /** {@link WalBusyException}: nothing queued. */
    BUSY,
    /** {@link WalWriteException}: not durable. */
    WRITE_FAILED,
    /** {@link WalFullException}: ACK_OK with every item dropped (DSN-11-09). */
    FULL,
    /** A {@link RuntimeException} after the quota was taken: INTERNAL. */
    INTERNAL
  }

  private void breakWal(Unwritten how) {
    switch (how) {
      case BUSY -> wal.busy = true;
      case WRITE_FAILED -> wal.failing = true;
      case FULL -> wal.full = true;
      case INTERNAL -> wal.crash = new IllegalStateException("internal error in the test WAL");
    }
  }

  private void repairWal() {
    wal.busy = false;
    wal.failing = false;
    wal.full = false;
    wal.crash = null;
  }

  private double quotaDrops() {
    io.micrometer.core.instrument.Counter c =
        meters.find("argos.collector.dropped").tag("reason", "quota").counter();
    return c == null ? 0 : c.count();
  }

  private static void assertNotWritten(Unwritten how, Result r, int items) {
    if (how == Unwritten.FULL) {
      assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
      assertThat(r.ack.getDroppedItems()).isEqualTo(items);
    } else {
      assertThat(r.ack).as("no ack").isNull();
      assertThat(r.status.getCode())
          .isEqualTo(how == Unwritten.INTERNAL ? Status.Code.INTERNAL : Status.Code.UNAVAILABLE);
    }
  }

  @ParameterizedTest(
      name =
          "T-SV-013a a bucket-sized batch not written 3 times ({0}), then written: ACK_OK,"
              + " nothing dropped, no quota drop counted")
  @EnumSource(Unwritten.class)
  @Tag("req:DSN-12-04")
  @Tag("req:D-06")
  void unwrittenBatchGivesItsQuotaBack(Unwritten how) {
    // Transaction bucket: capacity 4,000 (twice 2,000/s); the bucket clock never moves here.
    breakWal(how);
    for (int i = 0; i < 3; i++) {
      assertNotWritten(how, send(tx(1, 4_000)), 4_000);
    }
    assertThat(wal.records).as("nothing written").isEmpty();
    repairWal();
    Result resend = send(tx(1, 4_000));
    assertThat(resend.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack.getLastSeq()).isEqualTo(1);
    assertThat(resend.ack.getDroppedItems()).isZero();
    assertThat(quotaDrops()).isZero();
    TransactionBatch stored = storedTransactions(0);
    assertThat(stored.getRecordsCount()).isEqualTo(4_000);
  }

  @ParameterizedTest(
      name = "T-SV-013a the tokens of an unwritten batch ({0}) come back exactly once per send")
  @EnumSource(Unwritten.class)
  @Tag("req:DSN-12-04")
  @Tag("req:D-06")
  void quotaGivenBackExactlyOnce(Unwritten how) {
    assertThat(send(tx(1, 3_000)).ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    // 1,000 tokens left. Each unwritten send of 1,000 records takes them and gives them back.
    breakWal(how);
    for (int i = 0; i < 3; i++) {
      assertNotWritten(how, send(tx(2, 1_000)), 1_000);
    }
    repairWal();
    Result r = send(tx(2, 2_000));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(r.ack.getDroppedItems())
        .as("1,000 tokens: a second give-back would have left 2,000, a missing one 0")
        .isEqualTo(1_000);
    assertThat(quotaDrops()).isEqualTo(1_000);
  }

  @Test
  @Tag("req:DSN-12-04")
  @Tag("req:D-06")
  @Tag("req:C-3")
  @DisplayName(
      "T-SV-013a a slow record that is then not written gives its tokens back when it settles; a"
          + " slow record that becomes durable keeps them")
  void slowRecordSettlement() {
    CompletableFuture<WalPosition> failed = new CompletableFuture<>();
    wal.slow = failed;
    assertThat(send(tx(1, 4_000)).status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    wal.slow = null;
    failed.completeExceptionally(new WalWriteException("disk error", null));
    Result resend = send(tx(1, 4_000));
    assertThat(resend.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resend.ack.getDroppedItems()).as("the tokens came back on settlement").isZero();

    // The bucket is empty now; a slow record that becomes durable is not given back.
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
    CompletableFuture<WalPosition> durable = new CompletableFuture<>();
    wal.slow = durable;
    assertThat(send(tx(2, 4_000)).status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    wal.slow = null;
    durable.complete(new WalPosition(1, 0, 100));
    Result next = send(tx(3, 1));
    assertThat(next.ack.getStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(next.ack.getDroppedItems()).as("a durable record keeps its tokens").isEqualTo(1);
    assertThat(send(tx(2, 4_000)).ack.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
  }

  @Test
  @Tag("req:DSN-12-04")
  @Tag("req:D-06")
  @DisplayName(
      "T-SV-013a a statistics batch that is not written gives back both buckets: its rows"
          + " (STAT_ROWS) and its counters (METRIC_SAMPLES)")
  void statsBatchGivesBothBucketsBack() throws Exception {
    // Statistics bucket 6,000 rows, metric bucket 500: one full batch empties both.
    AgentServiceImpl small =
        service(new ArgosProperties.Collector.Quota(2_000, 20_000, 250, 3_000));
    StatBatch.Builder b =
        StatBatch.newBuilder()
            .setHeader(BatchHeader.newBuilder().setInstanceId(41).setAgentEpoch(99).setSeq(1));
    for (int i = 1; i <= 5_000; i++) {
      b.addSql(
          SqlStat.newBuilder()
              .setSqlId(i)
              .setKind(TransactionKind.HTTP)
              .addAllBuckets(Collections.nCopies(15, 0)));
    }
    for (int i = 1; i <= 1_000; i++) {
      b.addExt(
          ExtStat.newBuilder()
              .setHostId(i)
              .setKind(TransactionKind.HTTP)
              .addAllBuckets(Collections.nCopies(15, 0)));
    }
    for (int i = 0; i < 500; i++) {
      b.addCounters(CounterDelta.newBuilder().setName("c" + i).setDelta(1));
    }
    StatBatch batch = b.build();
    wal.busy = true;
    for (int i = 0; i < 3; i++) {
      Result r = sendStats(small, batch);
      assertThat(r.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }
    wal.busy = false;
    Result r = sendStats(small, batch);
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack.getDroppedItems()).isZero();
    StatBatch stored =
        StatBatch.parseFrom(
            WalDataPayload.decode(wal.records.get(0).payload.toByteArray()).batch());
    assertThat(stored.getSqlCount()).isEqualTo(5_000);
    assertThat(stored.getExtCount()).isEqualTo(1_000);
    assertThat(stored.getCountersCount()).isEqualTo(500);
    assertThat(quotaDrops()).isZero();
  }

  private Result sendStats(AgentServiceImpl svc, StatBatch batch) {
    Result result = new Result(events);
    Context.current()
        .withValue(CollectorCalls.SESSION, session)
        .run(() -> svc.sendStats(Inbound.of(batch), result));
    return result;
  }

  private TransactionBatch storedTransactions(int index) {
    try {
      return TransactionBatch.parseFrom(
          WalDataPayload.decode(wal.records.get(index).payload.toByteArray()).batch());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  // --- ADR-0010 C-148: bind values of a SQL_PARAM_FULL session ---------------------------------

  private static final String SENTINEL = "SENTINEL-bind-4111111111111111";

  private AgentSession sessionWith(SqlParamMode mode) {
    return new AgentSession(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "argos_AbCdEfGh",
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
        mode,
        clock.millis(),
        new ClockSync(clock.millis(), clock.millis()),
        new SessionStream(new NoopObserver()));
  }

  private static ProfileBatch profiles(long seq) {
    TransactionProfile profile =
        TransactionProfile.newBuilder()
            .setTxId(7)
            .setStartTsMs(1_000)
            .setKind(TransactionKind.HTTP)
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(1)
                    .setType(StepType.SQL)
                    .setDetail("[" + SENTINEL + ", 42]")
                    .setRepeatCount(1))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(2)
                    .setType(StepType.HTTP_CALL)
                    .setDetail("GET stock/api/items")
                    .setRepeatCount(1))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(3)
                    .setType(StepType.SQL)
                    .setDetail("[" + SENTINEL + "]")
                    .setRepeatCount(1))
            .addSteps(ProfileStep.newBuilder().setSeq(4).setType(StepType.SQL).setRepeatCount(1))
            .build();
    return ProfileBatch.newBuilder()
        .setHeader(BatchHeader.newBuilder().setInstanceId(41).setAgentEpoch(99).setSeq(seq))
        .addProfiles(profile)
        .build();
  }

  private Result sendProfiles(AgentSession s, ProfileBatch batch) {
    Result result = new Result(events);
    Context.current()
        .withValue(CollectorCalls.SESSION, s)
        .run(() -> service.sendProfiles(Inbound.of(batch), result));
    return result;
  }

  private double redacted() {
    io.micrometer.core.instrument.Counter c =
        meters
            .find("argos.collector.redacted")
            .tag("reason", BindValueRedactor.FULL_DETAIL_UNENCRYPTED)
            .counter();
    return c == null ? 0 : c.count();
  }

  @Test
  @Tag("req:C-148")
  @DisplayName(
      "C-148 a SQL_PARAM_FULL session: the detail of its SQL steps is cleared before the WAL append"
          + " (no bind value in the record or the pipeline), other details stay, the ack is"
          + " unchanged, the cleared steps are counted, one WARN per session")
  void fullModeBindValuesNeverReachTheWal() throws Exception {
    AgentSession full = sessionWith(SqlParamMode.SQL_PARAM_FULL);
    Result r = sendProfiles(full, profiles(1));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack.getDroppedItems()).isZero();
    byte[] payload = wal.records.get(0).payload.toByteArray();
    assertThat(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(SENTINEL);
    ProfileBatch stored = ProfileBatch.parseFrom(WalDataPayload.decode(payload).batch());
    List<ProfileStep> steps = stored.getProfiles(0).getStepsList();
    assertThat(steps).hasSize(4);
    assertThat(steps.get(0).getDetail()).isEmpty();
    assertThat(steps.get(1).getDetail())
        .as("an HTTP call keeps its detail")
        .isEqualTo("GET stock/api/items");
    assertThat(steps.get(2).getDetail()).isEmpty();
    assertThat(redacted()).as("steps whose detail was cleared").isEqualTo(2);
    assertThat(full.firstBindValueWarning()).as("the session's one WARN was logged").isFalse();

    assertThat(sendProfiles(full, profiles(2)).ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(redacted()).isEqualTo(4);
  }

  @Test
  @Tag("req:C-148")
  @DisplayName(
      "C-148 masked and partial sessions keep the detail of their SQL steps (nothing counted)")
  void maskedModeDetailKept() throws Exception {
    for (SqlParamMode mode :
        List.of(SqlParamMode.SQL_PARAM_MASKED, SqlParamMode.SQL_PARAM_PARTIAL)) {
      AgentSession s = sessionWith(mode);
      wal.records.clear();
      Result r = sendProfiles(s, profiles(mode.getNumber()));
      assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
      ProfileBatch stored =
          ProfileBatch.parseFrom(
              WalDataPayload.decode(wal.records.get(0).payload.toByteArray()).batch());
      assertThat(stored.getProfiles(0).getSteps(0).getDetail()).contains(SENTINEL);
      assertThat(s.firstBindValueWarning()).as("no WARN for %s", mode).isTrue();
    }
    assertThat(redacted()).isZero();
  }

  @Test
  @Tag("req:C-148")
  @DisplayName(
      "C-148 an unspecified or unknown sql_param_mode fails closed: SQL step detail is cleared as in"
          + " full mode")
  void unknownModeFailsClosed() throws Exception {
    int expected = 0;
    int seq = 10;
    for (SqlParamMode mode :
        List.of(SqlParamMode.SQL_PARAM_MODE_UNSPECIFIED, SqlParamMode.UNRECOGNIZED)) {
      AgentSession s = sessionWith(mode);
      wal.records.clear();
      Result r = sendProfiles(s, profiles(seq++));
      assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
      byte[] payload = wal.records.get(0).payload.toByteArray();
      assertThat(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1))
          .as("no bind value in the WAL record for %s", mode)
          .doesNotContain(SENTINEL);
      expected += 2;
      assertThat(redacted()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName(
      "12 §3.2 the active snapshot is not written: it replaces the session's latest one and goes to the pipeline")
  void activeSnapshot() {
    ActiveTransactionSnapshot snap =
        ActiveTransactionSnapshot.newBuilder()
            .setHeader(BatchHeader.newBuilder().setInstanceId(41).setAgentEpoch(99).setSeq(3))
            .setTotalActive(0)
            .build();
    Result r = new Result(events);
    Context.current()
        .withValue(CollectorCalls.SESSION, session)
        .run(() -> service.sendActiveSnapshot(Inbound.of(snap), r));
    assertThat(r.ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack.getLastSeq()).isEqualTo(3);
    assertThat(wal.records).isEmpty();
    assertThat(session.latestSnapshot().snapshot()).isEqualTo(snap);
    assertThat(pipeline.actives).containsExactly(snap);
  }

  /** The outcome of one call. */
  static final class Result implements StreamObserver<Ack> {
    private final List<String> events;
    Ack ack;
    Status status;
    Throwable error;

    Result(List<String> events) {
      this.events = events;
    }

    @Override
    public void onNext(Ack value) {
      events.add("ack");
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

  /** A waiting step of the fake WAL. */
  @FunctionalInterface
  interface Hold {
    void run() throws InterruptedException;
  }

  /** A WAL in memory. */
  final class FakeWal implements WalWriter {
    final List<Appended> records = Collections.synchronizedList(new ArrayList<>());
    volatile boolean full;
    volatile boolean failing;
    volatile double usage;
    volatile Hold hold;

    /** When set, an append is refused at once without queuing ({@link WalBusyException}). */
    volatile boolean busy;

    /** When set, an append queues the record and outlasts the append wait with this outcome. */
    volatile CompletableFuture<WalPosition> slow;

    /** When set, an append throws it (an internal error after the quota was taken). */
    volatile RuntimeException crash;

    private long offset;

    record Appended(
        WalRecordType type,
        int instanceId,
        long epoch,
        long seq,
        long receivedMs,
        long tsCorrectionMs,
        ByteString payload,
        WalPosition position) {}

    @Override
    public WalPosition append(
        WalRecordType type,
        int instanceId,
        long agentEpoch,
        long seq,
        long receivedMs,
        long tsCorrectionMs,
        byte[] payload)
        throws WalFullException, WalBusyException, WalWriteException, WalAppendPendingException {
      RuntimeException failure = crash;
      if (failure != null) {
        throw failure;
      }
      if (busy) {
        events.add("wal-busy");
        throw new WalBusyException("busy");
      }
      CompletableFuture<WalPosition> pending = slow;
      if (pending != null) {
        records.add(
            new Appended(
                type,
                instanceId,
                agentEpoch,
                seq,
                receivedMs,
                tsCorrectionMs,
                ByteString.copyFrom(payload),
                null));
        events.add("wal-queued");
        throw new WalAppendPendingException("slow fsync", pending);
      }
      Hold h = hold;
      if (h != null) {
        try {
          h.run();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new WalWriteException("interrupted", e);
        }
      }
      if (full) {
        throw new WalFullException(10, 10);
      }
      if (failing) {
        throw new WalWriteException("disk error", null);
      }
      WalPosition position;
      synchronized (this) {
        position = new WalPosition(1, offset, payload.length + 45);
        offset += payload.length + 45;
      }
      records.add(
          new Appended(
              type,
              instanceId,
              agentEpoch,
              seq,
              receivedMs,
              tsCorrectionMs,
              ByteString.copyFrom(payload),
              position));
      events.add("wal-append");
      return position;
    }

    @Override
    public WalPosition appendBlockIntent(byte[] payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WalPosition appendBlockCommit(byte[] payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WalIdentity identity() {
      return new WalIdentity("test", 1);
    }

    @Override
    public long bytesInUse() {
      return (long) (usage * 1_000);
    }

    @Override
    public long maxBytes() {
      return 1_000;
    }
  }

  /** Records pipeline calls. */
  final class RecordingPipeline implements IngestPipeline {
    final List<SessionContext> contexts = Collections.synchronizedList(new ArrayList<>());
    final List<WalPosition> positions = Collections.synchronizedList(new ArrayList<>());
    final List<ActiveTransactionSnapshot> actives = Collections.synchronizedList(new ArrayList<>());

    private void record(SessionContext s, WalPosition p) {
      events.add("pipeline");
      contexts.add(s);
      positions.add(p);
    }

    @Override
    public void onTransactions(SessionContext s, WalPosition p, TransactionBatch b) {
      record(s, p);
    }

    @Override
    public void onProfiles(SessionContext s, WalPosition p, ProfileBatch b) {
      record(s, p);
    }

    @Override
    public void onMetrics(SessionContext s, WalPosition p, MetricBatch b) {
      record(s, p);
    }

    @Override
    public void onStats(SessionContext s, WalPosition p, StatBatch b) {
      record(s, p);
    }

    @Override
    public void onActive(SessionContext s, ActiveTransactionSnapshot snap) {
      actives.add(snap);
    }
  }

  /** A server stream observer that accepts everything. */
  static final class NoopObserver extends ServerCallStreamObserver<ServerMessage> {
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

  @Test
  @DisplayName("time does not matter for the order: a clock that moves keeps receivedAt per call")
  void receivedAtPerCall() {
    send(tx(1, 1));
    clock.advance(Duration.ofSeconds(5));
    send(tx(2, 1));
    assertThat(wal.records.get(1).receivedMs - wal.records.get(0).receivedMs).isEqualTo(5_000);
  }
}
