/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service names of HTTP transactions (10 §6.2, F-A29): the fallback for requests without a handler
 * pattern and the per-application cap on distinct names.
 *
 * <ul>
 *   <li>{@link #fallback}: the provisional name {@code [<context path> ]<METHOD> <path>} that the
 *       servlet helper gives a transaction, with identifier segments of the path replaced by {@code
 *       {*}} ({@link HttpPaths#normalize}). Used when no module fixed a name (404, requests
 *       rejected by a filter, static resources, non-Spring servlets).
 *   <li>{@link #admit}: at most {@code instrumentation.service-dictionary-max} (10,000) distinct
 *       names per agent; later new names become {@value #OTHER} and are counted ({@code
 *       service_dictionary_overflow_total}).
 * </ul>
 */
public final class ServiceNames {
  /** Name of every service beyond the cap (F-A29). */
  public static final String OTHER = "_other_";

  /** {@code instrumentation.service-dictionary-max} default (10 §15). */
  public static final int DEFAULT_MAX = 10_000;

  private final int max;
  private final ConcurrentHashMap<String, Boolean> names = new ConcurrentHashMap<String, Boolean>();
  private final AtomicLong overflow = new AtomicLong();

  public ServiceNames(int max) {
    this.max = Math.max(1, max);
  }

  /**
   * F-A29 fallback: normalizes the path (the last space-separated token when it starts with {@code
   * /}) of a provisional name such as {@code GET /api/orders/42} or {@code /shop GET /api/x/1}.
   */
  public static String fallback(String provisional) {
    if (provisional == null) {
      return null;
    }
    int space = provisional.lastIndexOf(' ');
    String path = provisional.substring(space + 1);
    if (!path.startsWith("/")) {
      return provisional;
    }
    String normalized = HttpPaths.normalize(path);
    return normalized.equals(path) ? provisional : provisional.substring(0, space + 1) + normalized;
  }

  /** {@code name} while the cap is not reached or the name is known, else {@value #OTHER}. */
  public String admit(String name) {
    if (name == null || names.containsKey(name)) {
      return name;
    }
    if (names.size() >= max) {
      overflow.incrementAndGet();
      return OTHER;
    }
    names.putIfAbsent(name, Boolean.TRUE);
    return name;
  }

  /** Distinct names admitted so far. */
  public int size() {
    return names.size();
  }

  /** Transactions renamed to {@value #OTHER} ({@code service_dictionary_overflow_total}). */
  public long overflowCount() {
    return overflow.get();
  }
}
