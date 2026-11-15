/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the collector is shutting down (12 §3.9): from the first step of the shutdown, new Hellos
 * are refused with {@code SERVER_SHUTTING_DOWN} and new data calls with {@code UNAVAILABLE}; calls
 * already running complete during the drain.
 */
public final class CollectorState {

  /** 11 §10 (the contract vectors): retry advice of a Hello refused during the shutdown. */
  public static final int SHUTTING_DOWN_RETRY_AFTER_S = 5;

  /** 12 §3.9: retry advice of {@code SessionClose{SERVER_SHUTDOWN}}. */
  public static final int SHUTDOWN_CLOSE_RETRY_AFTER_S = 30;

  private final AtomicBoolean shuttingDown = new AtomicBoolean();

  /** Marks the start of the shutdown; returns {@code false} when it had already started. */
  public boolean beginShutdown() {
    return shuttingDown.compareAndSet(false, true);
  }

  /** Whether the shutdown has started. */
  public boolean shuttingDown() {
    return shuttingDown.get();
  }
}
