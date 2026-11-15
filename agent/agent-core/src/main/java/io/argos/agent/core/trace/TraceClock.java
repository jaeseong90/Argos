/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

/**
 * The two clocks of the recording side (D-01, D-02, T-ACC-01): {@link #nanoTime()} measures
 * durations (elapsed times are differences of it and nothing else), {@link #currentTimeMillis()}
 * stamps wall-clock times ({@code start_ts}, {@code tx_id} prefix, queue ages use {@code
 * nanoTime}). Production uses {@link #SYSTEM}; tests inject a clock whose wall time jumps.
 */
public interface TraceClock {
  /** {@code System.nanoTime()} equivalent: monotonic, for durations only. */
  long nanoTime();

  /** {@code System.currentTimeMillis()} equivalent: wall clock, may jump. */
  long currentTimeMillis();

  /** The JVM clocks. */
  TraceClock SYSTEM = new SystemClock();

  /** {@link #SYSTEM}. */
  final class SystemClock implements TraceClock {
    SystemClock() {}

    @Override
    public long nanoTime() {
      return System.nanoTime();
    }

    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }
}
