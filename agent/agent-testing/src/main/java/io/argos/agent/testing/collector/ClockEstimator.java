/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The server-side clock arithmetic of 11 §9 (D-02) for one instance, as the {@link FakeCollector}
 * applies it; the contract vectors ({@code contract/argos-v1-vectors.json}, section {@code clock})
 * pin the same numbers for the server (WO-21, WO-22).
 *
 * <ul>
 *   <li>First estimate: {@code ServerHello.server_time_ms - AgentHello.sent_at_ms} ({@link
 *       #hello}), without rtt correction, used until the first round completes.
 *   <li>Round n: the agent sends {@code Heartbeat{t1}}, the server answers {@code HeartbeatAck{t1,
 *       t2 = receive time, t3 = send time}} ({@link #acked}). Round n+1: {@code Heartbeat{prev_t1 =
 *       t1, prev_t4 = t4}} completes round n ({@link #heartbeat}): {@code offset = ((t2 - t1) + (t3
 *       - t4)) / 2} and {@code rtt = (t4 - t1) - (t3 - t2)}. Offset is server minus agent: a server
 *       clock 3 s ahead gives +3000. The {@link FakeCollector} sends this §9 value as {@code
 *       HeartbeatAck.clock_offset_ms} (ADR-0010 C-139, {@code FakeCollector.ackClockOffsetMs}).
 *   <li>The estimate is the median of the last {@value #WINDOW} accepted offsets; a round whose rtt
 *       exceeds {@value #RTT_DISCARD_FACTOR} times the median rtt of the last {@value #WINDOW}
 *       completed rounds is discarded (its offset does not enter the window).
 * </ul>
 *
 * <p>Rules 11 §9 does not fix, chosen here and pinned by the vectors:
 *
 * <ul>
 *   <li>Discarded rounds count for the median rtt. With accepted rounds only, a lasting rtt change
 *       (a new network path, or any rtt above 0 after a run of 0 ms rounds, whose median is 0)
 *       would discard every later round and freeze the estimate; this way the rounds are accepted
 *       again once most of the last {@value #WINDOW} have the new rtt.
 *   <li>Halves round toward negative infinity ({@link Math#floorDiv}) in the offset and in the
 *       median of an even count (the mean of the two middle values).
 *   <li>{@code prev_t1 <= 0} or {@code prev_t4 <= 0} means "no previous round"; a {@code prev_t1}
 *       the server never acknowledged completes nothing.
 * </ul>
 */
public final class ClockEstimator {
  /** Accepted rounds kept for the median (11 §9: the last 5). */
  public static final int WINDOW = 5;

  /** A round with rtt above this many times the median rtt is discarded (11 §9). */
  public static final int RTT_DISCARD_FACTOR = 3;

  /** Acknowledged heartbeats remembered for their next round (bounded). */
  static final int PENDING = 16;

  /**
   * One completed round.
   *
   * @param t1 agent send time of the heartbeat (agent clock)
   * @param t2 server receive time (server clock)
   * @param t3 server send time of the ack (server clock)
   * @param t4 agent receive time of the ack (agent clock, the next heartbeat's {@code prev_t4})
   * @param offsetMs {@code ((t2 - t1) + (t3 - t4)) / 2}
   * @param rttMs {@code (t4 - t1) - (t3 - t2)}
   * @param discarded rtt above {@value #RTT_DISCARD_FACTOR} times the median rtt
   */
  public record Round(
      long t1, long t2, long t3, long t4, long offsetMs, long rttMs, boolean discarded) {}

  private final Map<Long, long[]> pending = new LinkedHashMap<>();
  private final ArrayDeque<Long> acceptedOffsets = new ArrayDeque<>();
  private final ArrayDeque<Long> recentRtts = new ArrayDeque<>();
  private Long firstEstimate;

  /** The first estimate of a session ({@code server_time_ms - sent_at_ms}). */
  public synchronized void hello(long agentSentAtMs, long serverTimeMs) {
    firstEstimate = serverTimeMs - agentSentAtMs;
  }

  /** The server acknowledged heartbeat {@code t1} with receive time t2 and send time t3. */
  public synchronized void acked(long t1, long t2, long t3) {
    pending.remove(t1);
    pending.put(t1, new long[] {t2, t3});
    Iterator<Long> oldest = pending.keySet().iterator();
    while (pending.size() > PENDING && oldest.hasNext()) {
      oldest.next();
      oldest.remove();
    }
  }

  /**
   * A heartbeat's {@code prev_t1}/{@code prev_t4}: completes the round of {@code prevT1} if the
   * server acknowledged it, else returns null.
   */
  public synchronized Round heartbeat(long prevT1, long prevT4) {
    if (prevT1 <= 0 || prevT4 <= 0) {
      return null;
    }
    long[] ack = pending.remove(prevT1);
    if (ack == null) {
      return null;
    }
    long t2 = ack[0];
    long t3 = ack[1];
    long offset = offset(prevT1, t2, t3, prevT4);
    long rtt = rtt(prevT1, t2, t3, prevT4);
    boolean discard =
        !recentRtts.isEmpty() && rtt > RTT_DISCARD_FACTOR * median(toArray(recentRtts));
    push(recentRtts, rtt);
    if (!discard) {
      push(acceptedOffsets, offset);
    }
    return new Round(prevT1, t2, t3, prevT4, offset, rtt, discard);
  }

  /** The current estimate: median of the accepted rounds, else the first estimate, else empty. */
  public synchronized OptionalLong estimate() {
    if (!acceptedOffsets.isEmpty()) {
      return OptionalLong.of(median(toArray(acceptedOffsets)));
    }
    return firstEstimate == null ? OptionalLong.empty() : OptionalLong.of(firstEstimate);
  }

  /** Accepted rounds in the window. */
  public synchronized int acceptedRounds() {
    return acceptedOffsets.size();
  }

  /** {@code ((t2 - t1) + (t3 - t4)) / 2}, halves toward negative infinity (11 §9). */
  public static long offset(long t1, long t2, long t3, long t4) {
    return Math.floorDiv((t2 - t1) + (t3 - t4), 2);
  }

  /** {@code (t4 - t1) - (t3 - t2)} (11 §9). */
  public static long rtt(long t1, long t2, long t3, long t4) {
    return (t4 - t1) - (t3 - t2);
  }

  /** Median; an even count gives the mean of the two middle values, halves toward -infinity. */
  public static long median(long[] values) {
    if (values.length == 0) {
      throw new IllegalArgumentException("median of nothing");
    }
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    if (sorted.length % 2 == 1) {
      return sorted[middle];
    }
    return Math.floorDiv(sorted[middle - 1] + sorted[middle], 2);
  }

  private static void push(ArrayDeque<Long> window, long value) {
    window.addLast(value);
    while (window.size() > WINDOW) {
      window.removeFirst();
    }
  }

  private static long[] toArray(ArrayDeque<Long> window) {
    return window.stream().mapToLong(Long::longValue).toArray();
  }
}
