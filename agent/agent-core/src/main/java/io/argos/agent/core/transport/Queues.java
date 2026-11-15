/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import java.util.Objects;

/**
 * The five queues of 10 §10.1 that application and agent threads fill and the sender (WO-11)
 * drains: {@link #transaction()} (XLog records), {@link #profile()} (sampled step profiles), {@link
 * #stats()} (10-second statistics windows, WO-16), {@link #metric()} (metric snapshots, WO-16) and
 * {@link #active()} (the latest active snapshot, single slot). See {@link AgentQueue} and {@link
 * BoundedQueue} for the drop policy.
 *
 * <p>Bounds built by {@link #create(AgentConfig, AgentCounters)} (10 §10.1, §14, DSN-10-09):
 *
 * <ul>
 *   <li>transaction: {@code buffer.transaction.capacity} items (20,000), {@code
 *       buffer.max-age-seconds} (120), and the shared {@code buffer.max-bytes} budget (32 MB);
 *   <li>profile: {@value #PROFILE_MAX_BYTES} bytes of its own ({@code buffer.profile.max-bytes} of
 *       10 §10.1, a constant because 10 §15 has no such key), the same shared budget (10 §14: the
 *       two queues together stay within {@code buffer.max-bytes}), the same age bound, and the
 *       transaction capacity as item bound (every profile belongs to one transaction record);
 *   <li>stats: {@value #STATS_CAPACITY} windows (10 minutes of 10-second windows); metric: {@value
 *       #METRIC_CAPACITY} snapshots (10 minutes of 5-second snapshots); no age bound: they hold ten
 *       minutes on purpose;
 *   <li>active: one slot, the latest snapshot wins ({@link LatestSlot}).
 * </ul>
 *
 * Queue sizes are fixed at start; a remote push does not resize them ({@code buffer.*} are "N" keys
 * and take effect at the next start).
 */
public final class Queues {
  /** {@code queue} label values ({@code argos_agent_dropped_total{queue}}). */
  public static final String TRANSACTION = "transaction";

  public static final String PROFILE = "profile";
  public static final String STATS = "stats";
  public static final String METRIC = "metric";
  public static final String ACTIVE = "active";

  /** {@code buffer.profile.max-bytes} of 10 §10.1 (16 MB). */
  public static final long PROFILE_MAX_BYTES = 16L * 1024 * 1024;

  /** 60 statistics windows (10 §10.1). */
  public static final int STATS_CAPACITY = 60;

  /** 120 metric snapshots (10 §10.1). */
  public static final int METRIC_CAPACITY = 120;

  private final AgentQueue<QueueItem> transaction;
  private final AgentQueue<QueueItem> profile;
  private final AgentQueue<QueueItem> stats;
  private final AgentQueue<QueueItem> metric;
  private final AgentQueue<QueueItem> active;

  /** The queues given; {@link #create} is the factory of the agent. */
  public Queues(
      AgentQueue<QueueItem> transaction,
      AgentQueue<QueueItem> profile,
      AgentQueue<QueueItem> stats,
      AgentQueue<QueueItem> metric,
      AgentQueue<QueueItem> active) {
    this.transaction = Objects.requireNonNull(transaction, TRANSACTION);
    this.profile = Objects.requireNonNull(profile, PROFILE);
    this.stats = Objects.requireNonNull(stats, STATS);
    this.metric = Objects.requireNonNull(metric, METRIC);
    this.active = Objects.requireNonNull(active, ACTIVE);
  }

  /**
   * The queues of {@code config} ({@code buffer.transaction.capacity}, {@code buffer.max-bytes},
   * {@code buffer.max-age-seconds}), counting drops in {@code counters}.
   */
  public static Queues create(AgentConfig config, AgentCounters counters) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(counters, "counters");
    return create(
        config.bufferTransactionCapacity(),
        config.bufferMaxBytes(),
        PROFILE_MAX_BYTES,
        config.bufferMaxAgeSeconds(),
        counters,
        null);
  }

  /**
   * The queues of explicit bounds (tests: small caps, an injected clock for the age bound).
   *
   * @param transactionCapacity transaction (and profile) item bound
   * @param sharedMaxBytes {@code buffer.max-bytes} shared by transaction and profile items
   * @param profileMaxBytes the profile queue's own byte bound
   * @param maxAgeSeconds age bound of the transaction and profile queues
   * @param clock clock of the age bound, null for {@code System.nanoTime()}
   */
  public static Queues create(
      int transactionCapacity,
      long sharedMaxBytes,
      long profileMaxBytes,
      int maxAgeSeconds,
      AgentCounters counters,
      BoundedQueue.NanoClock clock) {
    ByteBudget shared = new ByteBudget(sharedMaxBytes);
    return new Queues(
        new BoundedQueue(
            TRANSACTION, transactionCapacity, null, shared, maxAgeSeconds, counters, clock),
        new BoundedQueue(
            PROFILE,
            transactionCapacity,
            new ByteBudget(profileMaxBytes),
            shared,
            maxAgeSeconds,
            counters,
            clock),
        new BoundedQueue(STATS, STATS_CAPACITY, null, null, 0, counters, clock),
        new BoundedQueue(METRIC, METRIC_CAPACITY, null, null, 0, counters, clock),
        new LatestSlot(ACTIVE, counters));
  }

  /** Ended transactions (XLog points), always offered (10 §9.2 step 2). */
  public AgentQueue<QueueItem> transaction() {
    return transaction;
  }

  /** Sampled profiles (10 §9.2 step 3). */
  public AgentQueue<QueueItem> profile() {
    return profile;
  }

  /** Statistics windows (10 §9.3, WO-16). */
  public AgentQueue<QueueItem> stats() {
    return stats;
  }

  /** Metric snapshots (10 §11, WO-16). */
  public AgentQueue<QueueItem> metric() {
    return metric;
  }

  /** The latest active snapshot; a new one replaces the old (10 §10.1 "덮어쓰기"). */
  public AgentQueue<QueueItem> active() {
    return active;
  }

  /** The five queues in the order of 10 §10.1. */
  public java.util.List<AgentQueue<QueueItem>> all() {
    return java.util.Arrays.asList(transaction, profile, stats, metric, active);
  }

  /** Estimated bytes of every queue together (self-diagnostics, heap budget N-02). */
  public long estimatedBytes() {
    return transaction.estimatedBytes()
        + profile.estimatedBytes()
        + stats.estimatedBytes()
        + metric.estimatedBytes()
        + active.estimatedBytes();
  }
}
