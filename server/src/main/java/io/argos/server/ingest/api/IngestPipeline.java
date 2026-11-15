/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.session.api.SessionContext;
import io.argos.server.wal.api.WalPosition;

/**
 * The collector → ingest boundary (12 §2 {@code IngestPipeline}, §3.5): decode, dictionary,
 * timestamp guard (D-15 with ADR-0010 C-129), error fingerprint (C-131), Apdex stamp, row
 * conversion and {@code ClickHouseWriter.enqueue}.
 *
 * <p>Calls:
 *
 * <ul>
 *   <li>The batch methods are called with a batch whose WAL record at {@code position} is durable:
 *       by the collector right after {@code WalWriter.append} returned (the ack does not wait for
 *       the pipeline), and by recovery and replay with the context and batch read back from the WAL
 *       ({@link io.argos.server.wal.api.WalDataPayload#context}). Both paths must store the same
 *       rows (D-13): the pipeline uses only the batch and {@code session} (its {@code
 *       receivedAtMs}, {@code tsCorrectionMs}, {@code apdexTMs}, modes and {@code appVersion}),
 *       never the current time or a live session.
 *   <li>Every method returns quickly and never blocks the caller (a gRPC executor thread): the
 *       implementation runs the work on its {@code argos-ingest-N} pool (12 §11). When its queue is
 *       full it leaves the record to the WAL replayer (the record is durable) instead of blocking.
 *   <li>No method throws for bad data: a record that fails to decode is skipped and counted ({@code
 *       argos_ingest_errors_total{stage}}, 12 §3.9); the rest of the batch is kept.
 * </ul>
 *
 * <p>The batch is what the agent sent minus what the collector removed before the WAL append, so
 * that the ack could report it: items beyond a quota (DSN-12-04) and items whose required enum
 * ({@code TransactionRecord.kind}, {@code TransactionProfile.kind}, {@code ProfileStep.type},
 * {@code MetricSeries.kind} with its samples, {@code SqlStat.kind}, {@code ExtStat.kind}) is
 * UNSPECIFIED or unknown (ADR-0010 C-143 (1): {@code ACK_PARTIAL_DROP}, {@code
 * argos_collector_unknown_enum_total{field}}). An item that still has such an enum is skipped here
 * and counted as a decode error.
 */
public interface IngestPipeline {

  /**
   * {@code SendTransactions}: {@code xlog} rows; {@link RealtimePublisher#publishXlog} for {@code
   * late = 0}.
   */
  void onTransactions(SessionContext session, WalPosition position, TransactionBatch batch);

  /** {@code SendProfiles}: {@code profile_step} rows (C-138, C-141, C-144). */
  void onProfiles(SessionContext session, WalPosition position, ProfileBatch batch);

  /** {@code SendMetrics}: {@code metric} rows on the 5 s grid (C-15). */
  void onMetrics(SessionContext session, WalPosition position, MetricBatch batch);

  /**
   * {@code SendStats}: {@code sql_stat_raw} and {@code ext_stat_raw} rows ({@code window_start} as
   * {@code ts}, 12 §3.6, C-143 (2)); {@code CounterDelta}s as {@code argos_agent_*} rows of {@code
   * metric}.
   */
  void onStats(SessionContext session, WalPosition position, StatBatch batch);

  /**
   * {@code SendActiveSnapshot}: not written to the WAL (volatile, 12 §3.2); the collector has
   * already replaced the session's latest snapshot. The pipeline forwards it to {@link
   * RealtimePublisher#publishActive}.
   */
  void onActive(SessionContext session, ActiveTransactionSnapshot snapshot);
}
