/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.util.Arrays;

/**
 * The clock offset of one session, computed by the server (11 §9, DSN-11-05; ADR-0010 C-139):
 *
 * <pre>
 * round n:   agent Heartbeat{t1} → server t2 (receive), HeartbeatAck{t1, t2, t3 (send)} → agent t4
 * round n+1: Heartbeat{t1', prev_t1 = t1, prev_t4 = t4}
 *            offset = ((t2 − t1) + (t3 − t4)) / 2   (server − agent; floor division)
 *            rtt    = (t4 − t1) − (t3 − t2)
 * </pre>
 *
 * <ul>
 *   <li>Before the first completed round the estimate is {@code ServerHello.server_time_ms −
 *       AgentHello.sent_at_ms}.
 *   <li>A round is discarded when its rtt is above 3 × the median rtt of the last {@value #ROUNDS}
 *       completed rounds before it, discarded ones included (C-139 (2): otherwise a lasting rtt
 *       change would freeze the estimate). The first round is never discarded.
 *   <li>The estimate is the median of the last {@value #ROUNDS} accepted offsets; an even count
 *       takes the floor of the mean of the two middle values.
 *   <li>A heartbeat whose {@code prev_t1} or {@code prev_t4} is not positive, or whose {@code
 *       prev_t1} the server did not acknowledge (or already used), completes no round.
 * </ul>
 *
 * The contract vectors {@code argos-v1-vectors.json} "clock" pin these rules (the agent's
 * FakeCollector computes the same). The offset is kept in memory only (WO-22 persists it); the
 * C-129 correction derived from it is written to the WAL at receipt ({@link
 * io.argos.server.session.api.ClockOffsetProvider}). Thread-safe.
 */
public final class ClockSync {

  /** Rounds kept for both medians (11 §9). */
  public static final int ROUNDS = 5;

  /** A round is discarded above this multiple of the median rtt (11 §9). */
  public static final int RTT_OUTLIER_FACTOR = 3;

  /** Acknowledged heartbeats remembered for the next round. */
  static final int ACKED = 4;

  private final long helloEstimateMs;
  private final long[] offsets = new long[ROUNDS];
  private int offsetCount;
  private int offsetNext;
  private final long[] rtts = new long[ROUNDS];
  private int rttCount;
  private int rttNext;
  private final long[][] acked = new long[ACKED][];
  private int ackedNext;
  private long estimateMs;

  /** The clock of a session started with {@code AgentHello.sent_at_ms} at server time. */
  public ClockSync(long serverTimeMs, long agentSentAtMs) {
    this.helloEstimateMs = serverTimeMs - agentSentAtMs;
    this.estimateMs = helloEstimateMs;
  }

  /**
   * Completes the round of a heartbeat's {@code prev_t1}/{@code prev_t4} when possible.
   *
   * @return the round, or {@code null} when the heartbeat completes none
   */
  public synchronized Round onHeartbeat(long prevT1, long prevT4) {
    if (prevT1 <= 0 || prevT4 <= 0) {
      return null;
    }
    long[] ack = takeAcked(prevT1);
    if (ack == null) {
      return null;
    }
    long t2 = ack[1];
    long t3 = ack[2];
    long offset = Math.floorDiv((t2 - prevT1) + (t3 - prevT4), 2L);
    long rtt = (prevT4 - prevT1) - (t3 - t2);
    boolean discarded = rttCount > 0 && rtt > RTT_OUTLIER_FACTOR * median(rtts, rttCount);
    rtts[rttNext] = rtt;
    rttNext = (rttNext + 1) % ROUNDS;
    rttCount = Math.min(ROUNDS, rttCount + 1);
    if (!discarded) {
      offsets[offsetNext] = offset;
      offsetNext = (offsetNext + 1) % ROUNDS;
      offsetCount = Math.min(ROUNDS, offsetCount + 1);
      estimateMs = median(offsets, offsetCount);
    }
    return new Round(offset, rtt, discarded);
  }

  /** Remembers the times of an acknowledged heartbeat for the next round. */
  public synchronized void acked(long t1, long t2, long t3) {
    acked[ackedNext] = new long[] {t1, t2, t3};
    ackedNext = (ackedNext + 1) % ACKED;
  }

  /** The current estimate (server − agent), milliseconds. */
  public synchronized long estimateMs() {
    return estimateMs;
  }

  /** The Hello estimate ({@code server_time_ms − sent_at_ms}). */
  public long helloEstimateMs() {
    return helloEstimateMs;
  }

  private long[] takeAcked(long t1) {
    for (int i = 0; i < ACKED; i++) {
      long[] a = acked[i];
      if (a != null && a[0] == t1) {
        acked[i] = null;
        return a;
      }
    }
    return null;
  }

  /** The median of the first {@code count} values (floor of the mean of the middle two). */
  static long median(long[] values, int count) {
    long[] sorted = Arrays.copyOf(values, count);
    Arrays.sort(sorted);
    int mid = count / 2;
    if (count % 2 == 1) {
      return sorted[mid];
    }
    return Math.floorDiv(sorted[mid - 1] + sorted[mid], 2L);
  }

  /**
   * One completed round.
   *
   * @param offsetMs server − agent
   * @param rttMs round-trip time without the server's processing time
   * @param discarded whether the round was left out of the estimate (rtt outlier)
   */
  public record Round(long offsetMs, long rttMs, boolean discarded) {}
}
