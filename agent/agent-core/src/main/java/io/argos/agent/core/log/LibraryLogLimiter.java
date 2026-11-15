/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * At most one line per minute for each (library logger, message template) at WARN and above ({@link
 * JulLogger}): a library that repeats a warning on every reconnect or name lookup (gRPC's {@code
 * ManagedChannelImpl}, Netty) cannot fill the agent log. The agent's own warnings have their own
 * limits (10 §14). Suppressed lines are counted ({@link #suppressed()}, the self metric {@code
 * argos_agent_library_log_suppressed_total}); the next line of the key that is written says how
 * many were suppressed.
 *
 * <p>Bounded and lock-free (the libraries may log on any thread, application threads included): at
 * most {@value #MAX_KEYS} keys; a new key beyond that evicts the key whose window started first.
 * Races between threads cost at most one extra line or one miscounted repeat, never a wait.
 */
final class LibraryLogLimiter {
  static final int MAX_KEYS = 64;
  static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** One key's window: its start and the lines suppressed since. */
  private static final class Window {
    final AtomicLong startNanos;
    final AtomicLong suppressed = new AtomicLong();

    Window(long start) {
      this.startNanos = new AtomicLong(start);
    }
  }

  private final LongSupplier clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<String, Window>();
  private final AtomicLong suppressedTotal = new AtomicLong();

  LibraryLogLimiter(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Whether a line of {@code key} may be written now: -1 when it is suppressed (and counted), else
   * the number of lines of the key suppressed since the last written one.
   */
  long admit(String key) {
    long now = clock.getAsLong();
    Window window = windows.get(key);
    if (window == null) {
      if (windows.size() >= MAX_KEYS) {
        evictOldest();
      }
      Window fresh = new Window(now);
      window = windows.putIfAbsent(key, fresh);
      if (window == null) {
        return 0;
      }
    }
    long start = window.startNanos.get();
    if (now - start >= WINDOW_NANOS && window.startNanos.compareAndSet(start, now)) {
      return window.suppressed.getAndSet(0);
    }
    window.suppressed.incrementAndGet();
    suppressedTotal.incrementAndGet();
    return -1;
  }

  /** Lines suppressed since the start. */
  long suppressed() {
    return suppressedTotal.get();
  }

  /** Keys held now (tests). */
  int keys() {
    return windows.size();
  }

  private void evictOldest() {
    String oldest = null;
    long oldestStart = Long.MAX_VALUE;
    for (Map.Entry<String, Window> entry : windows.entrySet()) {
      long start = entry.getValue().startNanos.get();
      if (oldest == null || start - oldestStart < 0) {
        oldest = entry.getKey();
        oldestStart = start;
      }
    }
    if (oldest != null) {
      windows.remove(oldest);
    }
  }
}
