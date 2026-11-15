/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches of one fake agent process (11 §6): a {@link BatchHeader} with the session's instance id,
 * the process's {@code agent_epoch}, a seq per batch kind (from 1, monotonic, independent of the
 * session) and {@code sent_at_ms} on the agent clock.
 *
 * <p>A resend is the same batch object sent again (same seq). A gap is {@link #skip}. An explicit
 * seq or epoch is {@link #header(BatchKind, long, long)}.
 */
public final class Batches {

  private final AgentClock clock;
  private final Map<BatchKind, AtomicLong> nextSeq = new EnumMap<>(BatchKind.class);
  private volatile long agentEpoch;
  private volatile int instanceId;

  /** Batches of a process with {@code agentEpoch}, before any session (instance id 0). */
  public Batches(AgentClock clock, long agentEpoch) {
    this.clock = clock;
    this.agentEpoch = agentEpoch;
    for (BatchKind kind : BatchKind.values()) {
      nextSeq.put(kind, new AtomicLong(1));
    }
  }

  /** The instance id of the accepted session (set by {@link FakeAgent#connect}). */
  public void setInstanceId(int id) {
    this.instanceId = id;
  }

  /** The instance id written into headers. */
  public int instanceId() {
    return instanceId;
  }

  /** The process epoch written into headers (C-3). */
  public long agentEpoch() {
    return agentEpoch;
  }

  /** A new epoch: the agent process restarted; every seq starts at 1 again (11 §6). */
  public void restart(long newEpoch) {
    this.agentEpoch = newEpoch;
    nextSeq.values().forEach(s -> s.set(1));
  }

  /** Takes the next seq of {@code kind}. */
  public long nextSeq(BatchKind kind) {
    return nextSeq.get(kind).getAndIncrement();
  }

  /** The seq the next batch of {@code kind} gets. */
  public long peekSeq(BatchKind kind) {
    return nextSeq.get(kind).get();
  }

  /** Leaves out {@code count} seqs of {@code kind} (a gap, as after agent-side drops). */
  public void skip(BatchKind kind, long count) {
    nextSeq.get(kind).addAndGet(count);
  }

  /** A header with the next seq of {@code kind}. */
  public BatchHeader header(BatchKind kind) {
    return header(kind, nextSeq(kind), agentEpoch);
  }

  /** A header with an explicit {@code seq} and {@code epoch} (the counters are unchanged). */
  public BatchHeader header(BatchKind kind, long seq, long epoch) {
    return BatchHeader.newBuilder()
        .setInstanceId(instanceId)
        .setAgentEpoch(epoch)
        .setSeq(seq)
        .setSentAtMs(clock.nowMs())
        .build();
  }

  /** A transaction batch with the next seq (at most 5,000 records, 11 §4.4). */
  public TransactionBatch transactions(List<TransactionRecord> records) {
    return transactions(header(BatchKind.TRANSACTIONS), records);
  }

  /** A transaction batch with {@code header}. */
  public static TransactionBatch transactions(BatchHeader header, List<TransactionRecord> records) {
    return TransactionBatch.newBuilder().setHeader(header).addAllRecords(records).build();
  }

  /** A profile batch with the next seq (at most 50,000 steps in total). */
  public ProfileBatch profiles(List<TransactionProfile> profiles) {
    return profiles(header(BatchKind.PROFILES), profiles);
  }

  /** A profile batch with {@code header}. */
  public static ProfileBatch profiles(BatchHeader header, List<TransactionProfile> profiles) {
    return ProfileBatch.newBuilder().setHeader(header).addAllProfiles(profiles).build();
  }

  /** A metric batch with the next seq (at most 500 series). */
  public MetricBatch metrics(List<MetricSeries> series, List<MetricSample> samples) {
    return MetricBatch.newBuilder()
        .setHeader(header(BatchKind.METRICS))
        .addAllSeries(series)
        .addAllSamples(samples)
        .build();
  }

  /**
   * A statistics batch of the 10 s window starting at {@code windowStartMs} (agent clock) with the
   * next seq (at most 5,000 SQL and 1,000 outgoing-call rows).
   */
  public StatBatch stats(
      long windowStartMs, List<SqlStat> sql, List<ExtStat> ext, List<CounterDelta> counters) {
    return StatBatch.newBuilder()
        .setHeader(header(BatchKind.STATS))
        .setWindowStartMs(windowStartMs)
        .setWindowLenS(10)
        .addAllSql(sql)
        .addAllExt(ext)
        .addAllCounters(counters)
        .build();
  }

  /** An active-transaction snapshot taken now (at most 500 items). */
  public ActiveTransactionSnapshot active(List<ActiveTransaction> items, int totalActive) {
    return ActiveTransactionSnapshot.newBuilder()
        .setHeader(header(BatchKind.ACTIVE))
        .setSnapshotTsMs(clock.nowMs())
        .setTotalActive(totalActive)
        .addAllItems(items)
        .setTruncated(totalActive > items.size())
        .build();
  }
}
