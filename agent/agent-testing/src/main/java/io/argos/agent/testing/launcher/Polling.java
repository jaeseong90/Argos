/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Polling of files the agent rewrites periodically (no retries of assertions: DSN-18-13). */
public final class Polling {
  private static final long INTERVAL_MILLIS = 200;

  private Polling() {}

  /**
   * Reads {@code value} every 200 ms until {@code condition} holds or {@code timeout} passes;
   * returns the last value read either way.
   */
  public static <T> T await(Supplier<T> value, Predicate<T> condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    T current = value.get();
    while (!condition.test(current) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(INTERVAL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      current = value.get();
    }
    return current;
  }
}
