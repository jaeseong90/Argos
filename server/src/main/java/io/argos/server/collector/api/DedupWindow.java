/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import io.argos.server.wal.api.WalRecordType;

/**
 * The batch deduplication window in front of the WAL (12 §3.2, 13 §5.1, D-13): per (instance,
 * epoch, kind) the {@value #WINDOW} most recently written seqs and the last written seq.
 *
 * <p>Semantics (the "dedup" section and interpretations of the contract vectors {@code
 * agent/agent-testing/src/main/resources/contract/argos-v1-vectors.json}, which the real server
 * must satisfy):
 *
 * <ul>
 *   <li>only written batches enter the window: a batch is written once its WAL record is fsynced
 *       ({@link #mark}); an error answer or a WAL-full answer writes nothing, so a retry with the
 *       same seq is new;
 *   <li>a written key still in the window is {@link Claim#DUPLICATE}: {@code ACK_DUPLICATE} with
 *       {@link #lastSeq}, nothing written again (11 §6);
 *   <li>a seq that has left the window (older than the {@value #WINDOW} most recent ones) is new
 *       again;
 *   <li>{@link #lastSeq} is the seq most recently written for (instance, epoch, kind), 0 before the
 *       first: the batch's own seq after {@code ACK_OK}; a written retry of an older seq lowers it.
 * </ul>
 *
 * <p>Concurrency: an agent may resend a batch whose first call is still waiting for its fsync
 * (deadline 5 s, 11 §6). {@link #claim} is atomic per key, so at most one call writes a key: the
 * second call sees {@link Claim#IN_FLIGHT} and must not write (the collector answers {@code
 * UNAVAILABLE}; the agent's next retry is then a duplicate or, if the first call failed, new).
 *
 * <p>Call order of {@code AgentServiceImpl}: {@code claim} → (NEW) quota trim → {@code
 * WalWriter.append} → {@code mark} and ack, or {@code release} on {@code WalFullException}, {@code
 * WalBusyException} (nothing queued) or {@code WalWriteException}. On {@code
 * WalAppendPendingException} (fsync slower than 1 s, 11 §10) the call answers {@code UNAVAILABLE}
 * and the claim stays until the queued record's outcome: {@code mark} once it is durable, {@code
 * release} when it is not written. Memory is bounded: idle (instance, epoch, kind) entries are
 * evicted (an evicted entry forgets its seqs, as a restart would without the WAL).
 *
 * <p>Implemented by lane {@code collector} (bitset + ring per entry); restored by lane {@code
 * durable-ingest} ({@code WalRecovery}) before any listener opens (DSN-12-15).
 */
public interface DedupWindow {

  /** Seqs remembered per (instance, epoch, kind) (12 §3.2, 11 §6). */
  int WINDOW = 1_000;

  /** The answer of {@link #claim}. */
  enum Claim {
    /** Not written and not in flight: the caller now holds the key and must mark or release it. */
    NEW,
    /** Written and still in the window: answer {@code ACK_DUPLICATE}, write nothing. */
    DUPLICATE,
    /** Another call holds the key (its WAL append is in progress): write nothing. */
    IN_FLIGHT
  }

  /** Atomically checks {@code key} and, when new, reserves it for the caller. */
  Claim claim(DedupKey key);

  /**
   * The key's WAL record is durable: the key enters the window, {@link #lastSeq} becomes its seq,
   * and the claim ends. Called only after {@code WalWriter.append} returned, or after the pending
   * record of a {@code WalAppendPendingException} became durable (possibly on the WAL writer
   * thread).
   */
  void mark(DedupKey key);

  /** Nothing was written for a claimed key: the claim ends and the window is unchanged. */
  void release(DedupKey key);

  /** Whether {@code key} was written and is still in the window (no claim, no side effect). */
  boolean seen(DedupKey key);

  /** The seq most recently written for (instance, epoch, kind); 0 when none. */
  long lastSeq(int instanceId, long agentEpoch, WalRecordType type);

  /** {@link #lastSeq(int, long, WalRecordType)} of the key's (instance, epoch, kind). */
  default long lastSeq(DedupKey key) {
    return lastSeq(key.instanceId(), key.agentEpoch(), key.type());
  }

  /**
   * Recovery (12 §3.4): replays one data record of the WAL, in WAL order, with the same window
   * effect as {@link #mark} (no claim, no metrics). The window after recovery equals the window
   * before the restart for every key still in the WAL.
   */
  void restore(DedupKey key);
}
