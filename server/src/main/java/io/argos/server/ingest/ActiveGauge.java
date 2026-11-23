/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.session.api.SessionContext;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-instance gauge {@value #NAME} of ADR-0010 C-178 (1): the {@code total_active} of every
 * {@code ActiveTransactionSnapshot} (11, every 5 s) becomes one gauge sample of the session's
 * instance in ClickHouse {@code metric}, from which the rollups {@code metric_1m}/{@code metric_1h}
 * follow, and the dashboard's {@code series.active} sums the instances.
 *
 * <p>Write path: the sample is a {@link MetricBatch} of one series written to the WAL as a {@link
 * WalRecordType#METRIC} data record of the session (its {@link WalDataPayload}, instance, epoch,
 * receipt time and timestamp correction), so it takes exactly the path of an agent's metric batch
 * after the WAL: durable before it is loaded, decoded by {@link RecordDecoder}, inserted in blocks
 * whose token is recorded first (ADR-0007 C-61), re-inserted by the WAL recovery. No dictionary is
 * involved: the metric name is a column value.
 *
 * <p>Deduplication: the record's seq is {@link #seq} (the snapshot's own seq with the top bit set).
 * Agent seqs start at 1 and only increase (11 §6), so they never reach 2<sup>63</sup>; the
 * collector's deduplication window never sees these records (they do not pass through it), and the
 * WAL scan does not restore them into it ({@link #isServerRecord}, {@code WalRecoveryPhase}).
 *
 * <p>The snapshot call is volatile and must not wait for an fsync (12 §3.2): {@link #record} only
 * queues the sample for one daemon thread ({@code argos-active-gauge}, at most {@value #QUEUE}
 * waiting samples); a full queue, a full or slow WAL drops the sample and counts it in {@code
 * argos_ingest_errors_total{stage="active_gauge"}}. A dropped sample leaves its bucket without a
 * value for that instance (D-23), never a wrong one.
 */
final class ActiveGauge implements AutoCloseable {

  /** The metric name (ADR-0010 C-178 (1)). */
  static final String NAME = "argos_active_transactions";

  /** The {@code argos_ingest_errors_total} stage of a dropped sample. */
  static final String ERROR_STAGE = "active_gauge";

  /** Samples waiting for the WAL at most. */
  static final int QUEUE = 1_024;

  /** The top bit marks a WAL record the server wrote itself. */
  static final long SERVER_SEQ_BIT = Long.MIN_VALUE;

  private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private static final Logger LOG = LoggerFactory.getLogger(ActiveGauge.class);

  private final WalWriter wal;
  private final IngestMetrics metrics;
  private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);
  private ThreadPoolExecutor executor;
  private volatile boolean closed;

  ActiveGauge(WalWriter wal, IngestMetrics metrics) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /** Whether a WAL record with {@code seq} was written by the server, not sent by an agent. */
  static boolean isServerRecord(long seq) {
    return (seq & SERVER_SEQ_BIT) != 0;
  }

  /** The WAL seq of the gauge record of a snapshot with {@code snapshotSeq}. */
  static long seq(long snapshotSeq) {
    return SERVER_SEQ_BIT | (snapshotSeq & Long.MAX_VALUE);
  }

  /**
   * The gauge sample of {@code snapshot} as a metric batch of {@code session}: one {@link
   * MetricKind#GAUGE} series {@value #NAME}, value {@code total_active} (unsigned), delta 0, at the
   * snapshot time on the 5 s grid of {@code metric} ({@link TimestampGuard#GRID_METRIC_MS}). The
   * time is the agent's; the decoder applies the session correction of the WAL record (C-129) as
   * for any metric sample. A snapshot without a time takes the receipt time.
   */
  static MetricBatch batch(SessionContext session, ActiveTransactionSnapshot snapshot) {
    long ts = snapshot.getSnapshotTsMs();
    if (ts <= 0) {
      ts = session.receivedAtMs() + Math.max(0, session.tsCorrectionMs());
    }
    long grid = TimestampGuard.GRID_METRIC_MS;
    long aligned = Math.floorDiv(ts, grid) * grid;
    BatchHeader header = snapshot.getHeader();
    return MetricBatch.newBuilder()
        .setHeader(
            BatchHeader.newBuilder()
                .setInstanceId(session.instanceId())
                .setAgentEpoch(session.agentEpoch())
                .setSeq(seq(header.getSeq()))
                .setSentAtMs(header.getSentAtMs()))
        .addSeries(MetricSeries.newBuilder().setIndex(0).setName(NAME).setKind(MetricKind.GAUGE))
        .addSamples(
            MetricSample.newBuilder()
                .setSeriesIndex(0)
                .setTsMs(aligned)
                .setValue((double) Integer.toUnsignedLong(snapshot.getTotalActive()))
                .setDelta(0))
        .build();
  }

  /** Queues the gauge sample of {@code snapshot}; never blocks. */
  void record(SessionContext session, ActiveTransactionSnapshot snapshot) {
    if (closed) {
      return;
    }
    MetricBatch batch;
    try {
      batch = batch(session, snapshot);
    } catch (RuntimeException e) {
      dropped("the sample could not be built: " + e.getClass().getName());
      return;
    }
    try {
      executor().execute(() -> append(session, batch));
    } catch (RejectedExecutionException e) {
      dropped("the queue of " + QUEUE + " samples is full");
    }
  }

  /** Appends one sample record and waits for its fsync (on {@code argos-active-gauge}). */
  void append(SessionContext session, MetricBatch batch) {
    if (closed) {
      return;
    }
    try {
      wal.append(
          WalRecordType.METRIC,
          session.instanceId(),
          session.agentEpoch(),
          batch.getHeader().getSeq(),
          session.receivedAtMs(),
          session.tsCorrectionMs(),
          WalDataPayload.of(session, batch.toByteString()).encode());
    } catch (WalAppendPendingException e) {
      // Queued: it may still become durable and is then loaded like any record.
    } catch (WalFullException | WalBusyException | WalWriteException e) {
      dropped("the WAL did not take it (" + e.getClass().getSimpleName() + ")");
    } catch (RuntimeException e) {
      dropped("the WAL append failed: " + e.getClass().getName());
    }
  }

  private void dropped(String why) {
    metrics.error(ERROR_STAGE);
    long now = System.nanoTime();
    long last = lastWarnNanos.get();
    if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
      LOG.warn(
          "An active-transaction gauge sample was dropped: {}. Further drops this minute are only"
              + " counted in argos_ingest_errors_total{stage=\"{}\"}",
          why,
          ERROR_STAGE);
    }
  }

  private synchronized ThreadPoolExecutor executor() {
    if (executor == null) {
      executor =
          new ThreadPoolExecutor(
              1,
              1,
              0,
              TimeUnit.MILLISECONDS,
              new ArrayBlockingQueue<>(QUEUE),
              r -> {
                Thread t = new Thread(r, "argos-active-gauge");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
    }
    return executor;
  }

  /** Samples waiting for the WAL (tests). */
  synchronized int queued() {
    return executor == null ? 0 : executor.getQueue().size() + executor.getActiveCount();
  }

  @Override
  public void close() {
    closed = true;
    ThreadPoolExecutor e;
    synchronized (this) {
      e = executor;
    }
    if (e != null) {
      e.shutdownNow();
    }
  }
}
