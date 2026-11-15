/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * The five data batch kinds of 11 §3 and §6: each has its own RPC, its own {@code seq} counter
 * (part of the deduplication key {@code (instance_id, agent_epoch, kind, seq)}, C-3, DSN-11-02),
 * its own queue of 10 §10.1 and its deadline (5 s, the active snapshot 3 s).
 */
public enum BatchKind {
  TRANSACTIONS(0, Queues.TRANSACTION, false),
  PROFILES(1, Queues.PROFILE, false),
  METRICS(2, Queues.METRIC, false),
  STATS(3, Queues.STATS, false),
  ACTIVE(4, Queues.ACTIVE, true);

  /** Number of kinds (array sizes indexed by {@link #index()}). */
  static final int COUNT = 5;

  /** Deadline of a data RPC (11 §3, §6): {@code server.deadline-ms}, 5,000 by default. */
  static final long DATA_DEADLINE_MILLIS = 5_000;

  /** Deadline of {@code SendActiveSnapshot} (11 §3). */
  static final long ACTIVE_DEADLINE_MILLIS = 3_000;

  private final int index;
  private final String queue;
  private final boolean active;

  BatchKind(int index, String queue, boolean active) {
    this.index = index;
    this.queue = queue;
    this.active = active;
  }

  /** A fixed array index 0..{@value #COUNT}-1 (per-kind counters and seqs). */
  int index() {
    return index;
  }

  /** The {@code queue} label of the queue this kind drains ({@code argos_agent_dropped_total}). */
  public String queue() {
    return queue;
  }

  /** The kind label of the transport counters ({@code transactions}, {@code profiles}, ...). */
  public String label() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * The RPC deadline: {@code configured} ({@code server.deadline-ms}) for data batches, at most 3 s
   * for the active snapshot (11 §3).
   */
  long deadlineMillis(long configured) {
    long value = configured > 0 ? configured : DATA_DEADLINE_MILLIS;
    return active ? Math.min(value, ACTIVE_DEADLINE_MILLIS) : value;
  }
}
