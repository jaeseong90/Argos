/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * The write-ahead log writer (12 §2 {@code WalWriter}, §3.4, DSN-12-01, DSN-12-02).
 *
 * <p>Durability contract (D-13): {@link #append} returns only after the record and everything
 * before it are on disk ({@code FileChannel.force(false)} of the group fsync, at most {@code
 * argos.wal.fsync-interval-ms} = 10 ms or 4 MiB of waiting records). An {@code ACK_OK} or {@code
 * ACK_PARTIAL_DROP} is sent only after {@code append} returned; the one ack without a write is the
 * WAL-full answer of {@link WalFullException} (DSN-11-09).
 *
 * <p>Append wait (11 §10: a WAL fsync slower than 1 s is answered {@code UNAVAILABLE}): a data
 * append is refused at once with {@link WalBusyException} (nothing queued, never written, retry
 * later) while a group write and fsync has already been running for longer than 1 s, or when the
 * data records waiting for the writer would exceed their byte limit (64 MiB): a stuck disk then
 * neither holds executor threads nor piles up batches in memory. Otherwise the append waits at most
 * 1 s for room in the writer queue and at most 1 s for its group fsync. No room in time is a {@link
 * WalWriteException} (nothing was queued). An fsync that is not done in time (or an interrupt while
 * waiting for it) is a {@link WalAppendPendingException}: the record is queued, not yet known to be
 * durable, and may still become durable later; its {@link WalAppendPendingException#pending()}
 * tells which. A gRPC executor thread is therefore held for about 1 s at most, and up to 1 s more
 * only while the writer queue (65,536 records) is full (12 §11). Block records are never refused as
 * busy.
 *
 * <p>Record layout (12 §3.4 + ADR-0010 C-129, format version 1 of the 16-byte segment header):
 * {@code [len u32][crc32c u32][type u8][instance_id u32][epoch u64][seq u64][received_ms
 * u64][ts_correction_ms i64][payload]}. Data records carry a {@link WalDataPayload}; block records
 * carry the batch writer's intent or commit ({@code io.argos.server.storage.api.BlockIntent},
 * {@code BlockToken}) with {@code instance_id}, {@code epoch}, {@code seq}, {@code
 * ts_correction_ms} 0.
 *
 * <p>Implemented by lane {@code durable-ingest} with one {@code argos-wal-writer} thread; callers
 * of any thread block in {@code append} until their group is fsynced or the append wait ends.
 */
public interface WalWriter {

  /**
   * Appends one data record and waits for its group fsync, for at most the append wait (1 s).
   *
   * @param type a data type ({@link WalRecordType#isData()})
   * @param instanceId the session's instance ({@link
   *     io.argos.server.session.api.SessionContext#instanceId()})
   * @param agentEpoch {@code BatchHeader.agent_epoch} (deduplication key, C-3)
   * @param seq {@code BatchHeader.seq} (deduplication key)
   * @param receivedMs {@link io.argos.server.session.api.SessionContext#receivedAtMs()}: the same
   *     value the live path decodes with, so that a replay applies D-15 identically
   * @param tsCorrectionMs {@link io.argos.server.session.api.SessionContext#tsCorrectionMs()}
   *     decided at receipt (ADR-0010 C-129 (3)); recovery and replay read it and never recompute
   * @param payload {@link WalDataPayload#encode()}
   * @return the position of the record, once it is durable
   * @throws WalFullException the record would exceed {@code argos.wal.max-bytes}; nothing written
   * @throws WalBusyException a group fsync is already slower than the wait, or the waiting data
   *     records reached their byte limit: nothing was queued, the record will never be written by
   *     this call; the caller must not ack and may treat the batch as never written (retry later)
   * @throws WalWriteException the record is not known to be durable and will not become durable
   *     through this call (an I/O error, a stopped WAL, no room in the queue within the wait); the
   *     caller must not ack
   * @throws WalAppendPendingException the group fsync did not end within the wait (or the waiting
   *     thread was interrupted): the record is not known to be durable yet but may become durable
   *     later; the caller must not ack and learns the outcome from {@link
   *     WalAppendPendingException#pending()}
   * @throws IllegalArgumentException {@code type} is not a data type
   */
  WalPosition append(
      WalRecordType type,
      int instanceId,
      long agentEpoch,
      long seq,
      long receivedMs,
      long tsCorrectionMs,
      byte[] payload)
      throws WalFullException, WalBusyException, WalWriteException, WalAppendPendingException;

  /**
   * Appends a {@link WalRecordType#BLOCK_INTENT} record and waits for its fsync; the batch writer
   * inserts the block only afterwards (ADR-0007 C-61: the token is recorded before the insert).
   * Block records are accepted above {@code argos.wal.max-bytes}: they are small and needed to free
   * space.
   *
   * @param payload {@code BlockIntent.encode()} of the batch writer
   * @return the position of the intent
   * @throws WalWriteException the intent is not known to be durable; the block must not be inserted
   */
  WalPosition appendBlockIntent(byte[] payload) throws WalWriteException;

  /**
   * Appends a {@link WalRecordType#BLOCK_COMMIT} record (the block of an intent is in ClickHouse)
   * and waits for its fsync. Accepted above {@code argos.wal.max-bytes}.
   *
   * @param payload {@code BlockToken.commitPayload()} of the batch writer
   * @return the position of the commit
   * @throws WalWriteException the commit is not known to be durable (recovery re-inserts the block
   *     with the same token, which ClickHouse deduplicates)
   */
  WalPosition appendBlockCommit(byte[] payload) throws WalWriteException;

  /** The identity (instance id and epoch) behind every block token of this WAL (C-61). */
  WalIdentity identity();

  /** Bytes of all segments on disk (metric {@code argos_wal_bytes}). */
  long bytesInUse();

  /** {@code argos.wal.max-bytes}. */
  long maxBytes();

  /**
   * {@link #bytesInUse()} / {@link #maxBytes()}: the collector adds a {@code Throttle} hint to its
   * acks above {@code argos.wal.warn-ratio} (0.8, 12 §3.2, §3.4).
   */
  default double usageRatio() {
    long max = maxBytes();
    return max <= 0 ? 1.0 : (double) bytesInUse() / max;
  }
}
