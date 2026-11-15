/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import io.argos.server.wal.api.WalPosition;

/**
 * The ClickHouse batch writer (12 §2 {@code ClickHouseWriter}, §3.8, 13 §5, §7.4).
 *
 * <p>Contract of the implementation (lane {@code durable-ingest}):
 *
 * <ul>
 *   <li>one {@code argos-ch-writer-<table>} thread and one bounded queue ({@code
 *       argos.writer.queue-rows}, 200,000 rows) per {@link Table}; a block is flushed every {@code
 *       argos.writer.flush-interval-ms} (2 s) or at {@code argos.writer.flush-rows} (50,000);
 *   <li>per block: {@link BlockToken#of} over the first and last WAL position of its rows, {@code
 *       WalWriter.appendBlockIntent} ({@link BlockIntent}) and its fsync, then {@code INSERT INTO
 *       <db>.<prefix><table> (<columnList>) SETTINGS insert_deduplication_token = '<token>',
 *       deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0 FORMAT
 *       RowBinaryWithNamesAndTypes} as {@code argos_writer} only, then {@code
 *       WalWriter.appendBlockCommit}; a failed insert is retried with backoff 1 s → 30 s and the
 *       same token; duplicates are never judged by {@code written_rows} (C-61); never an insert
 *       into a rollup table (C-60);
 *   <li>metrics {@code argos_writer_rows_total{table}}, {@code
 *       argos_writer_inserts_total{table,result}}, {@code argos_writer_queue_rows{table}}, {@code
 *       argos_ingest_lag_seconds} (receive → insert ack, N-15).
 * </ul>
 */
public interface ClickHouseWriter {

  /**
   * Queues the rows of the WAL data record at {@code position}; returns immediately (12 §3.8: the
   * pipeline never blocks on the writer).
   *
   * @return {@code true} when queued; {@code false} when the queue is full: the rows then stay only
   *     in the WAL (the WAL is the spool) and the {@code WalReplayer} rebuilds them from the record
   *     once the writer has caught up
   */
  boolean enqueue(Table table, WalPosition position, EncodedRows rows);

  /** Rows waiting in the queue of {@code table}. */
  int queuedRows(Table table);

  /** The capacity of each queue in rows ({@code argos.writer.queue-rows}). */
  int queueCapacityRows();

  /**
   * The fullest queue as a share of its capacity: the collector adds a {@code Throttle} hint to its
   * acks above the warn ratio (12 §3.2).
   */
  default double maxQueueRatio() {
    int capacity = queueCapacityRows();
    if (capacity <= 0) {
      return 1.0;
    }
    int max = 0;
    for (Table table : Table.values()) {
      max = Math.max(max, queuedRows(table));
    }
    return (double) max / capacity;
  }
}
