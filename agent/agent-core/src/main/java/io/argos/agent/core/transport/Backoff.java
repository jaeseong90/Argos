/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Reconnect delays of the session (10 §10.2 "재접속", 11 §10):
 *
 * <ul>
 *   <li>{@link #next()}: 1 s, 2 s, 4 s, ... up to 30 s, each with ±20 % jitter so that many agents
 *       restarted together do not reconnect in step; {@link #reset()} after an accepted hello;
 *   <li>{@link #unauthenticatedMillis()}: the fixed 5 minutes of ADR-0010 C-102 (16 §2.4) after
 *       {@code UNAUTHENTICATED} or {@code SessionClose{TOKEN_REVOKED_CLOSE}}, without jitter: a
 *       revoked or invalid token is not fixed by retrying sooner, and a fleet of such agents must
 *       not load the server.
 * </ul>
 *
 * Not thread-safe: the session uses it on its event loop only.
 */
final class Backoff {
  static final long INITIAL_MILLIS = 1_000;
  static final long MAX_MILLIS = 30_000;
  static final long UNAUTHENTICATED_MILLIS = 5 * 60_000;

  /** Jitter as a fraction of the delay (±). */
  static final double JITTER = 0.2;

  /** Random source of the jitter (tests inject a fixed one). */
  interface Jitter {
    /** A value in [0, 1). */
    double next();
  }

  static final Jitter RANDOM =
      new Jitter() {
        @Override
        public double next() {
          return ThreadLocalRandom.current().nextDouble();
        }
      };

  private final Jitter jitter;
  private long current = INITIAL_MILLIS;

  Backoff() {
    this(RANDOM);
  }

  Backoff(Jitter jitter) {
    this.jitter = jitter;
  }

  /** The next delay: the current step with jitter; the step then doubles up to 30 s. */
  long next() {
    long base = current;
    current = Math.min(MAX_MILLIS, current * 2);
    return withJitter(base);
  }

  /** Back to the first step (after an accepted hello). */
  void reset() {
    current = INITIAL_MILLIS;
  }

  /** The fixed delay after an authentication failure (ADR-0010 C-102: 5 minutes, no jitter). */
  long unauthenticatedMillis() {
    return UNAUTHENTICATED_MILLIS;
  }

  private long withJitter(long base) {
    double factor = 1.0 + JITTER * (2.0 * jitter.next() - 1.0);
    return Math.max(1L, (long) (base * factor));
  }
}
