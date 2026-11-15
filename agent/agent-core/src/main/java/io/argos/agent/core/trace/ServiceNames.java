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
 *
 * <p>Memory (10 §14 "상한", N-02 heap budget "기타 8MB"): the kept names are also bounded by their
 * estimated bytes ({@link #bytesOf}: {@value #STRING_BYTES} + 2 per character + {@value
 * #ENTRY_BYTES} per map entry). The bound is {@value #DEFAULT_MAX_BYTES} bytes (4 MiB), or the room
 * for {@code max} names of {@value #TYPICAL_NAME_CHARS} characters when that is more, so the count
 * cap of F-A29 holds for every name of up to {@value #TYPICAL_NAME_CHARS} characters (10,000 of
 * them take about 3.4 MB); only longer names (up to 1,024 characters, {@code
 * TracerImpl.MAX_SERVICE_NAME_CHARS}) can reach {@value #OTHER} before the count does. A name the
 * byte bound refuses is handled like one beyond the count: {@value #OTHER} and {@code
 * service_dictionary_overflow_total}.
 *
 * <p>With the default {@code max} the bound is the 4 MiB above, part of the "기타 8MB" of 10 §14.
 * Raising {@code instrumentation.service-dictionary-max} raises the bound by about 344 bytes per
 * name (about 34.4 MB at 100,000): like {@code buffer.max-bytes}, a limit the operator raises
 * spends memory beyond the default heap budget of N-02 on purpose (ADR-0010 C-120).
 */
public final class ServiceNames {
  /** Name of every service beyond the cap (F-A29). */
  public static final String OTHER = "_other_";

  /** {@code instrumentation.service-dictionary-max} default (10 §15). */
  public static final int DEFAULT_MAX = 10_000;

  /** The least byte bound of the kept names (4 MiB). */
  public static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;

  /** Name length for which the count cap always holds (the byte bound leaves room for it). */
  static final int TYPICAL_NAME_CHARS = 128;

  /** Estimated bytes of a {@code String} without its characters. */
  static final long STRING_BYTES = 40L;

  /** Estimated bytes of one map entry (node and table slot). */
  static final long ENTRY_BYTES = 48L;

  private final int max;
  private final long maxBytes;
  private final ConcurrentHashMap<String, Boolean> names = new ConcurrentHashMap<String, Boolean>();
  private final AtomicLong bytes = new AtomicLong();
  private final AtomicLong overflow = new AtomicLong();

  /** At most {@code max} names, within the byte bound of the class comment. */
  public ServiceNames(int max) {
    this(max, Math.max(DEFAULT_MAX_BYTES, Math.max(1, max) * bytesOf(TYPICAL_NAME_CHARS)));
  }

  /** At most {@code max} names and {@code maxBytes} estimated bytes (tests). */
  ServiceNames(int max, long maxBytes) {
    this.max = Math.max(1, max);
    this.maxBytes = Math.max(0L, maxBytes);
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

  /**
   * {@code name} while the count and byte bounds are not reached or the name is known, else {@value
   * #OTHER}. Lock-free: the bytes are reserved with a compare-and-set before the name is put.
   */
  public String admit(String name) {
    if (name == null || names.containsKey(name)) {
      return name;
    }
    if (names.size() >= max) {
      overflow.incrementAndGet();
      return OTHER;
    }
    long cost = bytesOf(name.length());
    while (true) {
      long used = bytes.get();
      if (used + cost > maxBytes) {
        overflow.incrementAndGet();
        return OTHER;
      }
      if (bytes.compareAndSet(used, used + cost)) {
        break;
      }
    }
    if (names.putIfAbsent(name, Boolean.TRUE) != null) {
      bytes.addAndGet(-cost); // another thread admitted the same name meanwhile
    }
    return name;
  }

  /** Estimated bytes of a kept name of {@code chars} characters. */
  static long bytesOf(int chars) {
    return STRING_BYTES + 2L * chars + ENTRY_BYTES;
  }

  /** Distinct names admitted so far. */
  public int size() {
    return names.size();
  }

  /** Estimated bytes of the kept names (part of {@code argos_agent_heap_bytes_estimated}). */
  public long estimatedBytes() {
    return bytes.get();
  }

  /** The byte bound of the kept names. */
  public long maxBytes() {
    return maxBytes;
  }

  /** Transactions renamed to {@value #OTHER} ({@code service_dictionary_overflow_total}). */
  public long overflowCount() {
    return overflow.get();
  }
}
