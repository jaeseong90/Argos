/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.function.LongUnaryOperator;

/**
 * A clock for the generator tests: {@link #sleepNanos} advances the time instead of waiting, so a
 * run of minutes takes milliseconds and every send time is exact. {@code extraSleep} injects late
 * wake-ups (coarse timers, GC pauses): it receives the sleep number and returns extra nanoseconds.
 */
final class SimulatedTime implements TimeSource {

  static final long START = 5_000_000_000L;

  private final LongUnaryOperator extraSleep;
  private long now = START;
  private long sleeps;

  SimulatedTime() {
    this(n -> 0);
  }

  SimulatedTime(LongUnaryOperator extraSleep) {
    this.extraSleep = extraSleep;
  }

  @Override
  public synchronized long nanoTime() {
    return now;
  }

  @Override
  public synchronized void sleepNanos(long nanos) {
    now += Math.max(1, nanos) + extraSleep.applyAsLong(sleeps++);
  }

  synchronized void advance(long nanos) {
    now += nanos;
  }

  /** Nanoseconds since {@link #START}. */
  synchronized long elapsed() {
    return now - START;
  }
}
