/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch deduplication of 11 §6 as the {@link FakeCollector} applies it; the contract vectors
 * ({@code contract/argos-v1-vectors.json}, section {@code dedup}) pin the same rules for the server
 * (WO-21).
 *
 * <ul>
 *   <li>Key: {@code (instance_id, agent_epoch, kind, seq)} (C-3, DSN-11-02). Another epoch (a
 *       restarted agent process) or another kind never collides.
 *   <li>Window: per {@code (instance_id, agent_epoch, kind)} the {@value #WINDOW} most recently
 *       written seqs (11 §6 "중복 제거 윈도 1,000", 11 §16 "종류별 1,000", 12 §3.2); the server assumes no
 *       order. A seq that left the window is written again (the fake's reading of the window; 11
 *       does not say more).
 *   <li>{@code Ack.last_seq}: the seq most recently written for that key (11 §4.3 "WAL에 기록한 마지막
 *       seq"): the batch's own seq on {@code ACK_OK} (12 §3.2 {@code Ack.ok(key.seq())}), the seq
 *       remembered for the key on {@code ACK_DUPLICATE} (12 §3.2 {@code
 *       Ack.duplicate(dedup.lastSeq(type))}). A retry of an older seq lowers it: it is the last
 *       write, not the highest seq. 0 before the first write.
 *   <li>Only written batches enter the window: a call answered with an error, or acknowledged with
 *       every item dropped because the WAL is full (12 §3.2), is not written, so its retry with the
 *       same seq is new (11 §6 retries).
 * </ul>
 */
public final class Deduplicator {
  /** Written seqs remembered per (instance, epoch, kind). */
  public static final int WINDOW = 1_000;

  /**
   * The decision for one batch.
   *
   * @param duplicate the key was in the window ({@code ACK_DUPLICATE})
   * @param lastSeq {@code Ack.last_seq} after this batch
   */
  public record Outcome(boolean duplicate, long lastSeq) {}

  private record Key(long instanceId, long agentEpoch, BatchKind kind) {}

  private static final class Window {
    final LinkedHashSet<Long> seqs = new LinkedHashSet<>();
    long lastSeq;
  }

  private final Map<Key, Window> windows = new ConcurrentHashMap<>();

  /** Checks one batch and, unless it is a duplicate, writes it. */
  public Outcome offer(long instanceId, long agentEpoch, BatchKind kind, long seq) {
    Window window =
        windows.computeIfAbsent(new Key(instanceId, agentEpoch, kind), k -> new Window());
    synchronized (window) {
      if (window.seqs.contains(seq)) {
        return new Outcome(true, window.lastSeq);
      }
      window.seqs.add(seq);
      Iterator<Long> oldest = window.seqs.iterator();
      while (window.seqs.size() > WINDOW && oldest.hasNext()) {
        oldest.next();
        oldest.remove();
      }
      window.lastSeq = seq;
      return new Outcome(false, window.lastSeq);
    }
  }

  /** True when the key is in the window; nothing is written. */
  public boolean seen(long instanceId, long agentEpoch, BatchKind kind, long seq) {
    Window window = windows.get(new Key(instanceId, agentEpoch, kind));
    if (window == null) {
      return false;
    }
    synchronized (window) {
      return window.seqs.contains(seq);
    }
  }

  /** The seq most recently written for the key, 0 when none. */
  public long lastSeq(long instanceId, long agentEpoch, BatchKind kind) {
    Window window = windows.get(new Key(instanceId, agentEpoch, kind));
    if (window == null) {
      return 0;
    }
    synchronized (window) {
      return window.lastSeq;
    }
  }
}
