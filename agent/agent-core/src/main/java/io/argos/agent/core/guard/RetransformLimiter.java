/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Upper bounds of a retransformation request (10 §12.2 step 4, F-A15, T-AG-046 agent part): at most
 * {@value #MAX_CLASSES} classes per request and at most one request per {@value
 * #MIN_INTERVAL_MILLIS} ms. A refused request is answered with {@code reason=retransform_limit}
 * ({@code ConfigApplyResult}, WO-17, which also executes the retransformation). The clock is
 * injected so the minute rule is tested without waiting. Thread-safe and lock-free.
 */
public final class RetransformLimiter {
  /** Classes one request may retransform (10 §12.2 step 4). */
  public static final int MAX_CLASSES = 50;

  /** Minimum time between two accepted requests (10 §12.2 step 4, "분당 1회"). */
  public static final long MIN_INTERVAL_MILLIS = 60_000L;

  /** The {@code ConfigApplyResult} rejection reason (10 §12.2 step 4). */
  public static final String REASON = "retransform_limit";

  /** Outcome of {@link #tryAcquire(int)}. */
  public enum Decision {
    /** The request may run; the next one is accepted a minute later. */
    ALLOWED,
    /** More than {@link RetransformLimiter#MAX_CLASSES} classes; refused, the minute stays free. */
    TOO_MANY_CLASSES,
    /** Less than a minute after the last accepted request; refused. */
    TOO_SOON;

    /** True when the request may run. */
    public boolean allowed() {
      return this == ALLOWED;
    }
  }

  /** A clock in milliseconds (monotonic in production, controlled in tests). */
  public interface Clock {
    long millis();
  }

  /** The production clock: {@code System.nanoTime()} in milliseconds (immune to clock changes). */
  public static final Clock MONOTONIC =
      new Clock() {
        @Override
        public long millis() {
          return System.nanoTime() / 1_000_000L;
        }
      };

  private static final long NEVER = Long.MIN_VALUE;

  private final Clock clock;
  private final AtomicLong lastAccepted = new AtomicLong(NEVER);

  public RetransformLimiter() {
    this(MONOTONIC);
  }

  public RetransformLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Decides a request that would retransform {@code classCount} classes. A request without classes
   * is allowed and does not use up the minute; a refused request does not either.
   */
  public Decision tryAcquire(int classCount) {
    if (classCount > MAX_CLASSES) {
      return Decision.TOO_MANY_CLASSES;
    }
    if (classCount <= 0) {
      return Decision.ALLOWED;
    }
    long now = clock.millis();
    while (true) {
      long last = lastAccepted.get();
      if (last != NEVER && now - last < MIN_INTERVAL_MILLIS) {
        return Decision.TOO_SOON;
      }
      if (lastAccepted.compareAndSet(last, now)) {
        return Decision.ALLOWED;
      }
    }
  }

  /** Milliseconds until a request would be accepted again (0 when it would be now). */
  public long millisUntilNext() {
    long last = lastAccepted.get();
    if (last == NEVER) {
      return 0;
    }
    return Math.max(0, MIN_INTERVAL_MILLIS - (clock.millis() - last));
  }
}
