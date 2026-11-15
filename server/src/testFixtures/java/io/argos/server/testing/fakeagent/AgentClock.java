/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The fake agent's wall clock (D-02): a base clock (the real time by default) plus a skew. Every
 * agent-clock field the fake agent fills reads this clock: {@code AgentHello.sent_at_ms} and {@code
 * process_start_ms}, {@code Heartbeat.t1_ms}/{@code prev_t1_ms}/{@code prev_t4_ms}, {@code
 * BatchHeader.sent_at_ms}, record timestamps ({@code start_ts_ms}, {@code ts_ms}, {@code
 * window_start_ms}, {@code snapshot_ts_ms}) and the time prefix of generated {@code tx_id}s.
 *
 * <p>A skew of {@code +60 s} is an agent clock 60 s ahead of the server: the server's 11 §9 offset
 * (server − agent, ADR-0010 C-139) is then about {@code −60,000} ms, a C-129 corrected instance.
 */
public final class AgentClock {

  private final LongSupplier base;
  private volatile long skewMs;

  private AgentClock(LongSupplier base, long skewMs) {
    this.base = Objects.requireNonNull(base, "base");
    this.skewMs = skewMs;
  }

  /** The real time, no skew. */
  public static AgentClock system() {
    return new AgentClock(System::currentTimeMillis, 0);
  }

  /** The real time plus {@code skew} (positive: the agent is ahead of the server). */
  public static AgentClock skewed(Duration skew) {
    return new AgentClock(System::currentTimeMillis, skew.toMillis());
  }

  /** {@code base} plus {@code skewMs}: a test clock (for example a fixed or stepping base). */
  public static AgentClock of(LongSupplier base, long skewMs) {
    return new AgentClock(base, skewMs);
  }

  /** The agent's current time, epoch milliseconds. */
  public long nowMs() {
    return base.getAsLong() + skewMs;
  }

  /** The current skew in milliseconds. */
  public long skewMs() {
    return skewMs;
  }

  /** Changes the skew (the agent clock jumps). */
  public void setSkewMs(long newSkewMs) {
    this.skewMs = newSkewMs;
  }
}
