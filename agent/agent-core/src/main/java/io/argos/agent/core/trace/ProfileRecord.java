/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.core.transport.QueueItem;

/**
 * The profile of a sampled transaction (10 §9.2 step 3, 11 {@code TransactionProfile}): the item of
 * the profile queue. It holds the sealed {@link StepBuffer} and the three values of the protocol
 * message ({@code tx_id}, {@code start_ts_ms}, {@code kind}), not the transaction record: the
 * record travels in the transaction queue, and a profile must not keep a dropped record alive
 * outside every byte estimate (N-02). The step builder ({@link ProfileSteps}) folds repeats and
 * computes {@code truncated}/{@code total_steps} when the profile is encoded, on the sender thread,
 * so the application thread only offers it.
 */
public final class ProfileRecord implements QueueItem {
  /** Estimated bytes of the record object. */
  static final long BASE_BYTES = 56L;

  private final long txId;
  private final long startEpochMs;
  private final int kind;
  private final StepBuffer steps;
  private final long estimatedBytes;
  private final long createdNanos;

  ProfileRecord(long txId, long startEpochMs, int kind, StepBuffer steps, long createdNanos) {
    this.txId = txId;
    this.startEpochMs = startEpochMs;
    this.kind = kind;
    this.steps = steps;
    this.estimatedBytes = BASE_BYTES + steps.estimatedBytes();
    this.createdNanos = createdNanos;
  }

  /** {@code tx_id} of the transaction. */
  public long txId() {
    return txId;
  }

  /** {@code start_ts_ms} of the transaction (agent wall clock, D-02). */
  public long startEpochMs() {
    return startEpochMs;
  }

  /**
   * The transaction's {@link io.argos.agent.bootstrap.Kind} (the protocol {@code TransactionKind}
   * number of {@code TransactionProfile.kind}, ADR-0010 C-138).
   */
  public int kind() {
    return kind;
  }

  /** The sealed step buffer. */
  public StepBuffer steps() {
    return steps;
  }

  /** The folded steps of 11 {@code TransactionProfile} (built on each call). */
  public ProfileSteps build() {
    return ProfileSteps.of(steps);
  }

  @Override
  public long estimatedBytes() {
    return estimatedBytes;
  }

  @Override
  public long createdNanos() {
    return createdNanos;
  }
}
