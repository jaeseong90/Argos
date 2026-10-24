/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.concurrent.locks.LockSupport;

/** Monotonic time and sleeping of the generator; tests replace it with a simulated clock. */
interface TimeSource {

  /** Monotonic nanoseconds (like {@link System#nanoTime()}). */
  long nanoTime();

  /** Sleeps up to {@code nanos}; may return early (unpark, spurious wake-up). */
  void sleepNanos(long nanos);

  /** The real clock: {@link System#nanoTime()} and {@link LockSupport#parkNanos(long)}. */
  TimeSource SYSTEM =
      new TimeSource() {
        @Override
        public long nanoTime() {
          return System.nanoTime();
        }

        @Override
        public void sleepNanos(long nanos) {
          LockSupport.parkNanos(nanos);
        }
      };
}
