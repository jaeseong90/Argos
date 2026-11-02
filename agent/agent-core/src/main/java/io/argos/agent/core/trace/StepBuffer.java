/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.Arrays;

/**
 * Steps of one transaction (10 §9.1, DSN-10-08), stored as parallel arrays (structure of arrays):
 * 64 slots at first, doubled on demand, at most {@code sampling.profile.max-steps} (1,000).
 *
 * <p>Beyond the limit the buffer switches to summary mode: further steps are only aggregated per
 * (type, name id) into at most {@value #MAX_SUMMARY_KEYS} summary entries (count, total, max,
 * errors), so memory stays bounded however long a batch job runs. Thread-safe (async steps may come
 * from other threads); every method is short and never waits on I/O.
 */
public final class StepBuffer {
  static final int INITIAL_CAPACITY = 64;

  /** Depth of the open-step stack used for parent links. */
  static final int MAX_OPEN = 64;

  static final int MAX_SUMMARY_KEYS = 256;

  /** Summary steps that can be open at the same time (their start time is kept). */
  static final int MAX_PENDING_SUMMARY = 64;

  /** Sequence numbers at or above this value denote open summary steps. */
  static final int SUMMARY_SEQ_BASE = 1 << 24;

  /** Upper bound of a step detail (10 §3 stepDetail: 4 KB). */
  public static final int DETAIL_MAX_CHARS = 4096;

  private final long baseNanos;
  private final int maxSteps;

  private int size;
  private int[] type;
  private int[] parent;
  private int[] status;
  private long[] nameId;
  private long[] targetId;
  private long[] startOffsetNanos;
  private long[] elapsedNanos;
  private long[] rows;
  private long[] fetchNanos;
  private boolean[] error;
  private boolean[] ended;
  private String[] detail;
  private long[][] stack;

  private final int[] open = new int[MAX_OPEN];
  private int openCount;

  private boolean summarized;
  private int summaryKeys;
  private int[] summaryType;
  private long[] summaryNameId;
  private long[] summaryCount;
  private long[] summaryTotalNanos;
  private long[] summaryMaxNanos;
  private long[] summaryErrors;
  private long summaryOverflow;
  private final long[] pendingStart = new long[MAX_PENDING_SUMMARY];
  private final int[] pendingKey = new int[MAX_PENDING_SUMMARY];
  private final boolean[] pendingUsed = new boolean[MAX_PENDING_SUMMARY];

  /**
   * @param baseNanos transaction start ({@code System.nanoTime()}), the origin of step offsets
   * @param maxSteps recorded steps before the summary mode (at least 1)
   */
  public StepBuffer(long baseNanos, int maxSteps) {
    this.baseNanos = baseNanos;
    this.maxSteps = Math.max(1, maxSteps);
    allocate(Math.min(INITIAL_CAPACITY, this.maxSteps));
  }

  private void allocate(int capacity) {
    type = new int[capacity];
    parent = new int[capacity];
    status = new int[capacity];
    nameId = new long[capacity];
    targetId = new long[capacity];
    startOffsetNanos = new long[capacity];
    elapsedNanos = new long[capacity];
    rows = new long[capacity];
    fetchNanos = new long[capacity];
    error = new boolean[capacity];
    ended = new boolean[capacity];
  }

  private void grow() {
    int capacity = Math.min(maxSteps, type.length * 2);
    type = Arrays.copyOf(type, capacity);
    parent = Arrays.copyOf(parent, capacity);
    status = Arrays.copyOf(status, capacity);
    nameId = Arrays.copyOf(nameId, capacity);
    targetId = Arrays.copyOf(targetId, capacity);
    startOffsetNanos = Arrays.copyOf(startOffsetNanos, capacity);
    elapsedNanos = Arrays.copyOf(elapsedNanos, capacity);
    rows = Arrays.copyOf(rows, capacity);
    fetchNanos = Arrays.copyOf(fetchNanos, capacity);
    error = Arrays.copyOf(error, capacity);
    ended = Arrays.copyOf(ended, capacity);
    if (detail != null) {
      detail = Arrays.copyOf(detail, capacity);
    }
    if (stack != null) {
      stack = Arrays.copyOf(stack, capacity);
    }
  }

  /**
   * Opens a step. Returns its sequence number, a summary handle ({@code >= SUMMARY_SEQ_BASE}) in
   * summary mode, or -1 when the step is not recorded at all.
   */
  public synchronized int start(int stepType, long name, long target, long startNanos) {
    if (size < maxSteps) {
      if (size == type.length) {
        grow();
      }
      int seq = size++;
      type[seq] = stepType;
      parent[seq] = openCount > 0 ? open[openCount - 1] : -1;
      nameId[seq] = name;
      targetId[seq] = target;
      startOffsetNanos[seq] = startNanos - baseNanos;
      elapsedNanos[seq] = -1;
      if (openCount < MAX_OPEN) {
        open[openCount++] = seq;
      }
      return seq;
    }
    summarized = true;
    int key = summaryKey(stepType, name);
    if (key < 0) {
      summaryOverflow++;
      return -1;
    }
    for (int slot = 0; slot < MAX_PENDING_SUMMARY; slot++) {
      if (!pendingUsed[slot]) {
        pendingUsed[slot] = true;
        pendingKey[slot] = key;
        pendingStart[slot] = startNanos;
        return SUMMARY_SEQ_BASE + slot;
      }
    }
    // Too many open summary steps: count without a duration.
    summaryCount[key]++;
    return -1;
  }

  private int summaryKey(int stepType, long name) {
    if (summaryType == null) {
      summaryType = new int[MAX_SUMMARY_KEYS];
      summaryNameId = new long[MAX_SUMMARY_KEYS];
      summaryCount = new long[MAX_SUMMARY_KEYS];
      summaryTotalNanos = new long[MAX_SUMMARY_KEYS];
      summaryMaxNanos = new long[MAX_SUMMARY_KEYS];
      summaryErrors = new long[MAX_SUMMARY_KEYS];
    }
    for (int i = 0; i < summaryKeys; i++) {
      if (summaryType[i] == stepType && summaryNameId[i] == name) {
        return i;
      }
    }
    if (summaryKeys == MAX_SUMMARY_KEYS) {
      return -1;
    }
    int key = summaryKeys++;
    summaryType[key] = stepType;
    summaryNameId[key] = name;
    return key;
  }

  /** Closes a step opened by {@link #start}. Unknown or already closed handles are ignored. */
  public synchronized void end(
      int seq, int stepStatus, boolean failed, long rowCount, long endNanos) {
    if (seq < 0) {
      return;
    }
    if (seq >= SUMMARY_SEQ_BASE) {
      int slot = seq - SUMMARY_SEQ_BASE;
      if (slot < MAX_PENDING_SUMMARY && pendingUsed[slot]) {
        pendingUsed[slot] = false;
        int key = pendingKey[slot];
        long elapsed = Math.max(0, endNanos - pendingStart[slot]);
        summaryCount[key]++;
        summaryTotalNanos[key] += elapsed;
        if (elapsed > summaryMaxNanos[key]) {
          summaryMaxNanos[key] = elapsed;
        }
        if (failed) {
          summaryErrors[key]++;
        }
      }
      return;
    }
    if (seq >= size || ended[seq]) {
      return;
    }
    ended[seq] = true;
    elapsedNanos[seq] = Math.max(0, endNanos - baseNanos - startOffsetNanos[seq]);
    status[seq] = stepStatus;
    error[seq] = failed;
    rows[seq] += rowCount;
    for (int i = openCount - 1; i >= 0; i--) {
      if (open[i] == seq) {
        System.arraycopy(open, i + 1, open, i, openCount - i - 1);
        openCount--;
        break;
      }
    }
  }

  /** Adds fetched rows and fetch time to a recorded step (JDBC ResultSet, 10 §6.3). */
  public synchronized void fetch(int seq, long rowCount, long nanos) {
    if (seq < 0 || seq >= size) {
      return;
    }
    rows[seq] += rowCount;
    fetchNanos[seq] += nanos;
  }

  /** Sets the detail of a recorded step (truncated to {@link #DETAIL_MAX_CHARS}). */
  public synchronized void detail(int seq, String text) {
    if (seq < 0 || seq >= size || text == null) {
      return;
    }
    if (detail == null) {
      detail = new String[type.length];
    }
    detail[seq] = text.length() > DETAIL_MAX_CHARS ? text.substring(0, DETAIL_MAX_CHARS) : text;
  }

  /** Sets the stack (frame dictionary ids) of a recorded step. */
  public synchronized void stack(int seq, long[] frameIds) {
    if (seq < 0 || seq >= size || frameIds == null) {
      return;
    }
    if (stack == null) {
      stack = new long[type.length][];
    }
    stack[seq] = frameIds;
  }

  // --- read access for exporters (after the transaction ended) ---------------------------------

  public synchronized int size() {
    return size;
  }

  public synchronized int type(int seq) {
    return type[seq];
  }

  public synchronized int parent(int seq) {
    return parent[seq];
  }

  public synchronized long nameId(int seq) {
    return nameId[seq];
  }

  public synchronized long targetId(int seq) {
    return targetId[seq];
  }

  public synchronized long startOffsetNanos(int seq) {
    return startOffsetNanos[seq];
  }

  /** Elapsed nanoseconds, -1 for a step that was never closed. */
  public synchronized long elapsedNanos(int seq) {
    return elapsedNanos[seq];
  }

  public synchronized int status(int seq) {
    return status[seq];
  }

  public synchronized boolean error(int seq) {
    return error[seq];
  }

  public synchronized long rows(int seq) {
    return rows[seq];
  }

  public synchronized long fetchNanos(int seq) {
    return fetchNanos[seq];
  }

  public synchronized String detail(int seq) {
    return detail == null ? null : detail[seq];
  }

  public synchronized long[] stack(int seq) {
    return stack == null ? null : stack[seq];
  }

  /** True once a step was aggregated instead of recorded. */
  public synchronized boolean summarized() {
    return summarized;
  }

  public synchronized int summaryKeys() {
    return summaryKeys;
  }

  public synchronized int summaryType(int key) {
    return summaryType[key];
  }

  public synchronized long summaryNameId(int key) {
    return summaryNameId[key];
  }

  public synchronized long summaryCount(int key) {
    return summaryCount[key];
  }

  public synchronized long summaryTotalNanos(int key) {
    return summaryTotalNanos[key];
  }

  public synchronized long summaryMaxNanos(int key) {
    return summaryMaxNanos[key];
  }

  public synchronized long summaryErrors(int key) {
    return summaryErrors[key];
  }

  /** Steps dropped entirely (more than {@value #MAX_SUMMARY_KEYS} distinct summary keys). */
  public synchronized long summaryOverflow() {
    return summaryOverflow;
  }

  /** Rough heap size of this buffer in bytes (export queue byte bound, 10 §10.1). */
  public synchronized long estimatedBytes() {
    long bytes = 64L + (long) type.length * 70L;
    if (detail != null) {
      for (int i = 0; i < size; i++) {
        if (detail[i] != null) {
          bytes += 40L + 2L * detail[i].length();
        }
      }
    }
    if (stack != null) {
      for (int i = 0; i < size; i++) {
        if (stack[i] != null) {
          bytes += 16L + 8L * stack[i].length;
        }
      }
    }
    if (summaryType != null) {
      bytes += MAX_SUMMARY_KEYS * 44L;
    }
    return bytes;
  }
}
