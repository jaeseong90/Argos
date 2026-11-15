/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;

/**
 * The agent's half of the NTP-style clock synchronisation of 11 §9 (D-02, DSN-11-05): the server
 * computes the offset, the agent only records its own timestamps and reports them in the next
 * heartbeat.
 *
 * <pre>
 * round n:   Heartbeat{t1}                    -> server records t2
 *            HeartbeatAck{t1, t2, t3}         -> agent records t4 = receive time
 * round n+1: Heartbeat{t1', prev_t1 = t1, prev_t4 = t4}
 * </pre>
 *
 * The {@code clock_offset_ms} of each {@code HeartbeatAck} (the server's latest offset) is recorded
 * exactly as the server sent it, without any sign conversion, for the self metric {@code
 * argos_agent_clock_offset_ms}. Its sign is server minus agent (the 11 §9 formula, ADR-0010 C-139);
 * the agent keeps the received value. The agent never corrects its timestamps with it (11 §9:
 * stored timestamps are the agent clock). An acknowledgement whose {@code t1} is not the last
 * heartbeat's (late or duplicated) is ignored, so {@code prev_t1} and {@code prev_t4} always belong
 * to the same round. Event loop only (no synchronisation needed); the offset gauge is volatile for
 * the self-diagnostics thread.
 */
final class ClockSync {
  /** Wall clock in epoch milliseconds (tests inject one). */
  interface WallClock {
    long currentTimeMillis();
  }

  static final WallClock SYSTEM =
      new WallClock() {
        @Override
        public long currentTimeMillis() {
          return System.currentTimeMillis();
        }
      };

  private final WallClock clock;
  private long pendingT1 = Long.MIN_VALUE;
  private long prevT1;
  private long prevT4;
  private volatile boolean offsetKnown;
  private volatile long offsetMillis;

  ClockSync(WallClock clock) {
    this.clock = clock;
  }

  /**
   * The next heartbeat's clock fields ({@code t1} now, the last completed round's {@code prev_t1}
   * and {@code prev_t4}, 0 before the first round completed) on {@code builder}.
   */
  Heartbeat.Builder stamp(Heartbeat.Builder builder) {
    long t1 = clock.currentTimeMillis();
    pendingT1 = t1;
    return builder.setT1Ms(t1).setPrevT1Ms(prevT1).setPrevT4Ms(prevT4);
  }

  /** Records {@code t4} for the acknowledged round and the server's offset. */
  void acknowledged(HeartbeatAck ack) {
    long t4 = clock.currentTimeMillis();
    if (ack.getT1Ms() == pendingT1) {
      prevT1 = pendingT1;
      prevT4 = t4;
      pendingT1 = Long.MIN_VALUE;
    }
    offsetMillis = ack.getClockOffsetMs();
    offsetKnown = true;
  }

  /** A new session: rounds start over (the server keeps no round across sessions). */
  void reset() {
    pendingT1 = Long.MIN_VALUE;
    prevT1 = 0;
    prevT4 = 0;
  }

  /** True once a {@code HeartbeatAck} reported an offset. */
  boolean offsetKnown() {
    return offsetKnown;
  }

  /**
   * The server's latest {@code clock_offset_ms} in milliseconds, recorded as sent (server minus
   * agent, ADR-0010 C-139).
   */
  long offsetMillis() {
    return offsetMillis;
  }

  long prevT1() {
    return prevT1;
  }

  long prevT4() {
    return prevT4;
  }
}
