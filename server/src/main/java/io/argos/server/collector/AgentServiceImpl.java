/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import com.google.protobuf.Message;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.api.DedupWindow;
import io.argos.server.collector.batch.AckItems;
import io.argos.server.collector.batch.BindValueRedactor;
import io.argos.server.collector.batch.QuotaTrimmer;
import io.argos.server.collector.batch.RequiredEnumFilter;
import io.argos.server.collector.batch.Shaped;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.collector.limits.Violation;
import io.argos.server.ingest.api.ApdexProvider;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.session.AgentSession;
import io.argos.server.session.api.ClockOffsetProvider;
import io.argos.server.session.api.SessionContext;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The five data RPCs of {@code AgentService} (12 §3.2, 11 §6). Per batch, in this order:
 *
 * <ol>
 *   <li>decode result: a message above the decompressed size limit or malformed is rejected ({@link
 *       Inbound});
 *   <li>limits ({@link LimitsChecker}, 11 §4.4, 12 §3.3): a violation rejects the whole batch
 *       (ADR-0010 C-130 (2)) with {@code RESOURCE_EXHAUSTED} or {@code INVALID_ARGUMENT} and the
 *       trailer {@code argos-reject-reason};
 *   <li>deduplication ({@link DedupWindow#claim}, C-3): a written key answers {@code ACK_DUPLICATE}
 *       with {@code last_seq} and writes nothing (D-13); a key whose first call still waits for its
 *       fsync answers {@code UNAVAILABLE} (the agent's retry is then a duplicate);
 *   <li>items with an unknown required enum removed (ADR-0010 C-143 (1));
 *   <li>quota: items beyond the instance's bucket removed from the end (DSN-12-04, C-130 (1)). When
 *       the batch is then not written to the WAL (busy, full or failed WAL, an fsync outcome that
 *       is not durable, an internal error), the tokens it took are given back exactly once ({@link
 *       QuotaTrimmer} {@code refund}, WO-21 follow-up N-1), so that a stalled WAL and the agent's
 *       resends never turn into {@code quota} drops of data that was never stored;
 *   <li>profiles of a {@code SQL_PARAM_FULL} session: the bind values in the {@code detail} of SQL
 *       steps are cleared ({@link BindValueRedactor}, ADR-0010 C-148), counted in {@code
 *       argos_collector_redacted_total{reason="full_detail_unencrypted"}} with one WARN per
 *       session; the ack does not change;
 *   <li>{@link WalWriter#append} of the remaining batch with the receipt context ({@code
 *       received_ms}, the C-129 correction decided now, the app's Apdex T now); it returns after
 *       the group fsync. A full WAL answers {@code ACK_OK} with {@code dropped_items} = every item
 *       and writes nothing (DSN-11-09, the only ack without an fsync); an undurable write answers
 *       {@code UNAVAILABLE} and never acks. An fsync slower than the append wait (1 s, 11 §10,
 *       {@link WalAppendPendingException}) answers {@code UNAVAILABLE} with {@code
 *       argos-retry-after-ms} ({@code argos_collector_rejected_total{reason="wal_slow"}}) and keeps
 *       the key claimed until the queued record's outcome is known: durable → {@link
 *       DedupWindow#mark} and the pipeline (a later resend is {@code ACK_DUPLICATE}), not written →
 *       {@link DedupWindow#release} (a later resend is new), so the seq is never stored twice
 *       (D-13). A WAL that is not keeping up refuses the record without queuing it ({@link
 *       WalBusyException}: a group fsync already running longer than the append wait, or the
 *       waiting batches at their byte limit): the same {@code UNAVAILABLE} with {@code
 *       argos-retry-after-ms} and {@code wal_slow} count, at once, and the key is released (a later
 *       resend is new);
 *   <li>{@link DedupWindow#mark}, then {@link IngestPipeline} (non-blocking; the ack does not wait
 *       for decoding or ClickHouse, DSN-12-01);
 *   <li>{@code ACK_OK}, or {@code ACK_PARTIAL_DROP} with the removed count, {@code last_seq} = the
 *       batch's seq, and a {@code Throttle} hint above the WAL or writer warn ratio.
 * </ol>
 *
 * {@code SendActiveSnapshot} is volatile (12 §3.2): limits, then the session's latest snapshot is
 * replaced and handed to {@link IngestPipeline#onActive}; no WAL, no deduplication.
 *
 * <p>Runs on the {@code argos-grpc-exec} threads; the only wait is the WAL append (normally at most
 * 10 ms of grouping, never much longer than the 1 s append wait, 12 §11).
 */
public final class AgentServiceImpl {

  /**
   * {@code argos-retry-after-ms} of a batch whose WAL fsync outlasted the append wait, or that the
   * WAL refused as busy (11 §10).
   */
  static final long WAL_SLOW_RETRY_AFTER_MS = 1_000;

  /** Minimum time between two WARN lines about a slow WAL fsync. */
  private static final long WAL_SLOW_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private static final long NEVER = Long.MIN_VALUE;

  private static final Logger LOG = LoggerFactory.getLogger(AgentServiceImpl.class);

  private final AtomicLong lastWalSlowWarnNanos = new AtomicLong(NEVER);

  private final LimitsChecker limits;
  private final DedupWindow dedup;
  private final RequiredEnumFilter enums;
  private final QuotaTrimmer quotas;
  private final WalWriter wal;
  private final IngestPipeline pipeline;
  private final ClockOffsetProvider offsets;
  private final ApdexProvider apdex;
  private final ThrottleAdvisor throttle;
  private final CollectorMetrics metrics;
  private final Clock clock;

  private final Kind<TransactionBatch> transactions;
  private final Kind<ProfileBatch> profiles;
  private final Kind<MetricBatch> metricBatches;
  private final Kind<StatBatch> stats;

  /** The service with all its collaborators. */
  public AgentServiceImpl(
      LimitsChecker limits,
      DedupWindow dedup,
      RequiredEnumFilter enums,
      QuotaTrimmer quotas,
      WalWriter wal,
      IngestPipeline pipeline,
      ClockOffsetProvider offsets,
      ApdexProvider apdex,
      ThrottleAdvisor throttle,
      CollectorMetrics metrics,
      Clock clock) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.dedup = Objects.requireNonNull(dedup, "dedup");
    this.enums = Objects.requireNonNull(enums, "enums");
    this.quotas = Objects.requireNonNull(quotas, "quotas");
    this.wal = Objects.requireNonNull(wal, "wal");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.offsets = Objects.requireNonNull(offsets, "offsets");
    this.apdex = Objects.requireNonNull(apdex, "apdex");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactions =
        new Kind<>(
            "transactions",
            WalRecordType.TX,
            TransactionBatch::getHeader,
            AckItems::of,
            limits::check,
            this.enums::filter,
            this.quotas::trim,
            this.quotas::refund,
            (session, b) -> b,
            this.pipeline::onTransactions);
    this.profiles =
        new Kind<>(
            "profiles",
            WalRecordType.PROFILE,
            ProfileBatch::getHeader,
            AckItems::of,
            limits::check,
            this.enums::filter,
            this.quotas::trim,
            this.quotas::refund,
            this::redactBindValues,
            this.pipeline::onProfiles);
    this.metricBatches =
        new Kind<>(
            "metrics",
            WalRecordType.METRIC,
            MetricBatch::getHeader,
            AckItems::of,
            limits::check,
            this.enums::filter,
            this.quotas::trim,
            this.quotas::refund,
            (session, b) -> b,
            this.pipeline::onMetrics);
    this.stats =
        new Kind<>(
            "stats",
            WalRecordType.STAT,
            StatBatch::getHeader,
            AckItems::of,
            limits::check,
            this.enums::filter,
            this.quotas::trim,
            this.quotas::refund,
            (session, b) -> b,
            this.pipeline::onStats);
  }

  /** {@code SendTransactions}. */
  public void sendTransactions(Inbound<TransactionBatch> in, StreamObserver<Ack> out) {
    handle(transactions, in, out);
  }

  /** {@code SendProfiles}. */
  public void sendProfiles(Inbound<ProfileBatch> in, StreamObserver<Ack> out) {
    handle(profiles, in, out);
  }

  /** {@code SendMetrics}. */
  public void sendMetrics(Inbound<MetricBatch> in, StreamObserver<Ack> out) {
    handle(metricBatches, in, out);
  }

  /** {@code SendStats}. */
  public void sendStats(Inbound<StatBatch> in, StreamObserver<Ack> out) {
    handle(stats, in, out);
  }

  /** {@code SendActiveSnapshot} (volatile: no WAL, no deduplication, 12 §3.2). */
  public void sendActiveSnapshot(Inbound<ActiveTransactionSnapshot> in, StreamObserver<Ack> out) {
    long receivedAt = clock.millis();
    AgentSession session = CollectorCalls.SESSION.get();
    if (in.rejected()) {
      reject(out, in.violation());
      return;
    }
    ActiveTransactionSnapshot snapshot = in.message();
    metrics.received("active", 1);
    Violation v = limits.check(snapshot, session.instanceId());
    if (v != null) {
      reject(out, v);
      return;
    }
    session.replaceSnapshot(new AgentSession.ActiveSnapshot(receivedAt, snapshot));
    try {
      pipeline.onActive(context(session, receivedAt), snapshot);
    } catch (RuntimeException e) {
      LOG.error("The ingest pipeline failed on an active snapshot of {}", session, e);
    }
    complete(
        out,
        Ack.newBuilder()
            .setStatus(AckStatus.ACK_OK)
            .setLastSeq(snapshot.getHeader().getSeq())
            .build());
  }

  private <B extends Message> void handle(Kind<B> kind, Inbound<B> in, StreamObserver<Ack> out) {
    long receivedAt = clock.millis();
    AgentSession session = CollectorCalls.SESSION.get();
    if (in.rejected()) {
      reject(out, in.violation());
      return;
    }
    B batch = in.message();
    int items = kind.items().applyAsInt(batch);
    metrics.received(kind.type(), items);
    Violation v = kind.limits().apply(batch, session.instanceId());
    if (v != null) {
      reject(out, v);
      return;
    }
    BatchHeader header = kind.header().apply(batch);
    DedupKey key = DedupKey.of(session.instanceId(), kind.walType(), header);
    switch (dedup.claim(key)) {
      case DUPLICATE -> {
        complete(
            out,
            Ack.newBuilder()
                .setStatus(AckStatus.ACK_DUPLICATE)
                .setLastSeq(dedup.lastSeq(key))
                .build());
        return;
      }
      case IN_FLIGHT -> {
        metrics.rejected("dedup_in_flight");
        out.onError(
            Status.UNAVAILABLE
                .withDescription("A batch with this seq is still being written; retry")
                .asRuntimeException());
        return;
      }
      case NEW -> {
        // the key is ours until mark or release
      }
    }
    boolean claimed = true;
    // The batch whose items took quota tokens, until it is written to the WAL or its tokens are
    // given back (N-1): every path that ends without a WAL record gives them back exactly once.
    B charged = null;
    try {
      Shaped<B> filtered = kind.enumFilter().apply(batch);
      Shaped<B> trimmed = kind.quota().apply(session.instanceId(), filtered.batch());
      charged = trimmed.batch();
      B kept = kind.redact().apply(session, charged);
      SessionContext context = context(session, receivedAt);
      byte[] payload = WalDataPayload.of(context, kept.toByteString()).encode();
      WalPosition position;
      try {
        position =
            wal.append(
                kind.walType(),
                session.instanceId(),
                header.getAgentEpoch(),
                header.getSeq(),
                receivedAt,
                context.tsCorrectionMs(),
                payload);
      } catch (WalFullException e) {
        refund(kind, session, charged);
        charged = null;
        dedup.release(key);
        claimed = false;
        metrics.dropped("wal_full", items);
        complete(
            out,
            Ack.newBuilder()
                .setStatus(AckStatus.ACK_OK)
                .setLastSeq(dedup.lastSeq(key))
                .setDroppedItems(items)
                .build());
        return;
      } catch (WalBusyException e) {
        // 11 §10: an fsync is already stuck past the append wait, or the batches waiting for it
        // reached their byte limit. Nothing was queued, so the seq stays new: the resend is NEW.
        refund(kind, session, charged);
        charged = null;
        dedup.release(key);
        claimed = false;
        metrics.rejected("wal_slow");
        if (walSlowWarnDue()) {
          LOG.warn(
              "WAL not keeping up: batches are answered UNAVAILABLE without being queued until it"
                  + " catches up (counted as argos_collector_rejected_total reason wal_slow): {}",
              e.getMessage());
        }
        out.onError(
            CollectorCalls.error(
                Status.UNAVAILABLE.withDescription("The collector's WAL is slow; retry"),
                null,
                WAL_SLOW_RETRY_AFTER_MS));
        return;
      } catch (WalWriteException e) {
        refund(kind, session, charged);
        charged = null;
        dedup.release(key);
        claimed = false;
        metrics.rejected("wal_unavailable");
        LOG.warn("A batch of {} could not be made durable; answered UNAVAILABLE", session, e);
        out.onError(
            Status.UNAVAILABLE
                .withDescription("The collector could not store the batch; retry")
                .asRuntimeException());
        return;
      } catch (WalAppendPendingException e) {
        // 11 §10: the fsync outlasted the append wait. The record is queued and may still become
        // durable, so the claim stays (a resend meanwhile is IN_FLIGHT) until its outcome is known.
        // The refund, if the record turns out not written, is the settlement's.
        claimed = false;
        B pendingCharge = charged;
        charged = null;
        settleWhenKnown(
            kind, key, e.pending(), context, kept, pendingCharge, trimmed.droppedItems(), session);
        metrics.rejected("wal_slow");
        if (walSlowWarnDue()) {
          LOG.warn(
              "WAL fsync slower than the append wait: batches are answered UNAVAILABLE until it"
                  + " catches up (counted as argos_collector_rejected_total reason wal_slow): {}",
              e.getMessage());
        }
        out.onError(
            CollectorCalls.error(
                Status.UNAVAILABLE.withDescription("The collector's WAL is slow; retry"),
                null,
                WAL_SLOW_RETRY_AFTER_MS));
        return;
      }
      claimed = false;
      charged = null;
      durable(kind, key, context, position, kept, trimmed.droppedItems(), session);
      int dropped = filtered.droppedItems() + trimmed.droppedItems();
      Ack.Builder ack =
          Ack.newBuilder()
              .setStatus(dropped > 0 ? AckStatus.ACK_PARTIAL_DROP : AckStatus.ACK_OK)
              .setLastSeq(header.getSeq())
              .setDroppedItems(dropped);
      throttle.hint().ifPresent(ack::setThrottle);
      complete(out, ack.build());
    } catch (RuntimeException e) {
      if (charged != null) {
        refund(kind, session, charged);
      }
      if (claimed) {
        dedup.release(key);
      }
      LOG.error("A batch of {} failed before its WAL append", session, e);
      out.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
    }
  }

  /**
   * The batch's WAL record is durable: the key enters the window and the claim ends, the quota drop
   * is counted, and the pipeline gets the batch (it never fails the call: the record is durable,
   * recovery and replay load it).
   */
  private <B extends Message> void durable(
      Kind<B> kind,
      DedupKey key,
      SessionContext context,
      WalPosition position,
      B kept,
      int quotaDropped,
      AgentSession session) {
    dedup.mark(key);
    metrics.dropped("quota", quotaDropped);
    try {
      kind.pipeline().accept(context, position, kept);
    } catch (RuntimeException e) {
      LOG.error("The ingest pipeline failed on a durable batch of {}", session, e);
    }
  }

  /**
   * Settles the claim of a batch whose append ended with {@link WalAppendPendingException}: once
   * the queued record is durable, {@link #durable} (a later resend is {@code ACK_DUPLICATE}); when
   * it is not written, the quota tokens of {@code charged} are given back and the key is released
   * (a later resend is new). Runs on the thread that completes the record (the WAL writer), or here
   * when it is already complete: only the bucket and window updates, a counter and the non-blocking
   * pipeline call.
   */
  private <B extends Message> void settleWhenKnown(
      Kind<B> kind,
      DedupKey key,
      CompletionStage<WalPosition> pending,
      SessionContext context,
      B kept,
      B charged,
      int quotaDropped,
      AgentSession session) {
    CompletionStage<?> unused =
        pending.handle(
            (position, error) -> {
              try {
                if (error == null && position != null) {
                  durable(kind, key, context, position, kept, quotaDropped, session);
                } else {
                  refund(kind, session, charged);
                  dedup.release(key);
                }
              } catch (RuntimeException e) {
                LOG.error("Settling a slow WAL append of {} failed", session, e);
              }
              return null;
            });
  }

  /**
   * Gives back the quota tokens {@code charged} took, for a batch that is not written to the WAL
   * (N-1). Never throws: a failure here must not skip the release of the batch's key.
   */
  private <B extends Message> void refund(Kind<B> kind, AgentSession session, B charged) {
    if (charged == null) {
      return;
    }
    try {
      kind.refund().accept(session.instanceId(), charged);
    } catch (RuntimeException e) {
      LOG.error("Giving back the quota of an unwritten batch of {} failed", session, e);
    }
  }

  /**
   * ADR-0010 C-148: the profiles of a {@code SQL_PARAM_FULL} session lose the bind values in the
   * detail of their SQL steps before the WAL append; counted, one WARN per session (no values).
   */
  private ProfileBatch redactBindValues(AgentSession session, ProfileBatch batch) {
    if (!session.sendsFullBindValues()) {
      return batch;
    }
    BindValueRedactor.Redacted redacted = BindValueRedactor.redact(batch);
    if (redacted.cleared() > 0) {
      metrics.redacted(BindValueRedactor.FULL_DETAIL_UNENCRYPTED, redacted.cleared());
      if (session.firstBindValueWarning()) {
        LOG.warn(
            "{} sends SQL bind parameters in full mode (sql_param_mode=full); they are not stored"
                + " until encrypted storage exists (ADR-0010 C-148): the detail of its SQL steps is"
                + " cleared at receipt and counted in argos_collector_redacted_total"
                + " reason full_detail_unencrypted (one line per session)",
            session);
      }
    }
    return redacted.batch();
  }

  /** Whether a WARN about a slow WAL fsync is due (at most one a minute). */
  private boolean walSlowWarnDue() {
    long now = System.nanoTime();
    long last = lastWalSlowWarnNanos.get();
    return (last == NEVER || now - last >= WAL_SLOW_WARN_INTERVAL_NANOS)
        && lastWalSlowWarnNanos.compareAndSet(last, now);
  }

  private SessionContext context(AgentSession session, long receivedAt) {
    return session
        .context()
        .atReceipt(
            receivedAt,
            offsets.tsCorrectionMs(session.instanceId()),
            apdex.apdexTMs(session.appId()));
  }

  private void reject(StreamObserver<Ack> out, Violation v) {
    metrics.rejected(v.reason());
    out.onError(CollectorCalls.error(v.status(), v.reason(), 0));
  }

  private static void complete(StreamObserver<Ack> out, Ack ack) {
    out.onNext(ack);
    out.onCompleted();
  }

  /** The pipeline call of one batch kind. */
  @FunctionalInterface
  private interface Dispatch<B> {
    void accept(SessionContext context, WalPosition position, B batch);
  }

  /**
   * What differs between the four WAL batch kinds.
   *
   * @param quota the quota trim (tokens taken)
   * @param refund gives back what {@code quota} took for its kept batch (N-1)
   * @param redact what the server clears before the WAL append (C-148: profiles only)
   */
  private record Kind<B extends Message>(
      String type,
      WalRecordType walType,
      Function<B, BatchHeader> header,
      ToIntFunction<B> items,
      BiFunction<B, Integer, Violation> limits,
      Function<B, Shaped<B>> enumFilter,
      BiFunction<Integer, B, Shaped<B>> quota,
      BiConsumer<Integer, B> refund,
      BiFunction<AgentSession, B, B> redact,
      Dispatch<B> pipeline) {}
}
