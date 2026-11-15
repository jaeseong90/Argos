/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

/**
 * The step builder of 11 §16 (DSN-11-03): the steps of one transaction as they are sent ({@code
 * TransactionProfile.steps}, {@code truncated}, {@code total_steps}), built from a sealed {@link
 * StepBuffer} on the sender thread.
 *
 * <p><b>Repeat folding</b> ({@code ProfileStep.repeat_count}): consecutive steps (sequence numbers
 * {@code n} and {@code n+1}) are folded into one when they have the same type, name id, target id,
 * parent, thread, status, error flag and detail, have no stack, are both closed and have no child
 * steps (an N+1 query loop). The folded step keeps the first one's start offset and the sums of
 * elapsed time, rows and fetch time; {@link #repeatCount(int)} says how many steps it stands for (1
 * for a single step). Steps with children are never folded, so parents always map.
 *
 * <p><b>Numbering</b>: steps are numbered 0 .. {@link #size()}-1 in start order and {@link
 * #parent(int)} is the parent's index or -1 (the protocol encoder adds 1: 11 {@code seq >= 1}, root
 * {@code parent_seq = 0}). Slots that were taken but never written (a race with the end of the
 * transaction) are skipped.
 *
 * <p><b>Summary</b>: {@link #truncated()} when the buffer entered the summary mode; the summary
 * entries ({@code SummaryStep(count, sumNanos, maxNanos)} of 10 §9.1) are merged per (type, name
 * id). {@link #totalSteps()} counts every step that started.
 */
public final class ProfileSteps {
  private final int size;
  private final int[] parent;
  private final int[] type;
  private final long[] nameId;
  private final long[] targetId;
  private final long[] startOffset;
  private final long[] elapsed;
  private final long[] rows;
  private final long[] fetch;
  private final int[] status;
  private final boolean[] error;
  private final String[] detail;
  private final long[][] stack;
  private final String[] thread;
  private final int[] repeat;

  private final boolean truncated;
  private final long totalSteps;
  private final int recordedSteps;
  private final long summaryOverflow;
  private final int summarySize;
  private final int[] summaryType;
  private final long[] summaryNameId;
  private final long[] summaryCount;
  private final long[] summarySum;
  private final long[] summaryMax;
  private final long[] summaryErrors;

  private ProfileSteps(StepBuffer buffer) {
    int n = buffer.size();
    recordedSteps = n;
    int[] children = new int[n];
    boolean[] written = new boolean[n];
    for (int s = 0; s < n; s++) {
      written[s] = buffer.isRecorded(s);
      if (written[s]) {
        int p = buffer.parent(s);
        if (p >= 0 && p < n) {
          children[p]++;
        }
      }
    }
    parent = new int[n];
    type = new int[n];
    nameId = new long[n];
    targetId = new long[n];
    startOffset = new long[n];
    elapsed = new long[n];
    rows = new long[n];
    fetch = new long[n];
    status = new int[n];
    error = new boolean[n];
    detail = new String[n];
    stack = new long[n][];
    thread = new String[n];
    repeat = new int[n];
    int[] map = new int[n];
    int emitted = 0;
    int last = -1;
    for (int s = 0; s < n; s++) {
      if (!written[s]) {
        map[s] = -1;
        continue;
      }
      if (last >= 0 && last == s - 1 && foldable(buffer, children, last, s)) {
        int run = map[last];
        repeat[run]++;
        elapsed[run] += buffer.elapsedNanos(s);
        rows[run] += buffer.rows(s);
        fetch[run] += buffer.fetchNanos(s);
        map[s] = run;
        last = s;
        continue;
      }
      int index = emitted++;
      int p = buffer.parent(s);
      parent[index] = p >= 0 && p < s ? map[p] : -1;
      type[index] = buffer.type(s);
      nameId[index] = buffer.nameId(s);
      targetId[index] = buffer.targetId(s);
      startOffset[index] = buffer.startOffsetNanos(s);
      elapsed[index] = buffer.elapsedNanos(s);
      rows[index] = buffer.rows(s);
      fetch[index] = buffer.fetchNanos(s);
      status[index] = buffer.status(s);
      error[index] = buffer.error(s);
      detail[index] = buffer.detail(s);
      stack[index] = buffer.stack(s);
      thread[index] = buffer.threadName(s);
      repeat[index] = 1;
      map[s] = index;
      last = s;
    }
    size = emitted;
    truncated = buffer.summarized();
    totalSteps = buffer.totalSteps();
    summaryOverflow = buffer.summaryOverflow();
    int keys = buffer.summaryKeys();
    summaryType = new int[keys];
    summaryNameId = new long[keys];
    summaryCount = new long[keys];
    summarySum = new long[keys];
    summaryMax = new long[keys];
    summaryErrors = new long[keys];
    int merged = 0;
    for (int k = 0; k < keys; k++) {
      if (!buffer.summaryReady(k)) {
        continue;
      }
      int t = buffer.summaryType(k);
      long name = buffer.summaryNameId(k);
      int target = merged;
      for (int m = 0; m < merged; m++) {
        if (summaryType[m] == t && summaryNameId[m] == name) {
          target = m;
          break;
        }
      }
      if (target == merged) {
        summaryType[target] = t;
        summaryNameId[target] = name;
        merged++;
      }
      summaryCount[target] += buffer.summaryCount(k);
      summarySum[target] += buffer.summaryTotalNanos(k);
      summaryMax[target] = Math.max(summaryMax[target], buffer.summaryMaxNanos(k));
      summaryErrors[target] += buffer.summaryErrors(k);
    }
    summarySize = merged;
  }

  private static boolean foldable(StepBuffer buffer, int[] children, int previous, int s) {
    return children[previous] == 0
        && children[s] == 0
        && buffer.isClosed(previous)
        && buffer.isClosed(s)
        && buffer.type(previous) == buffer.type(s)
        && buffer.nameId(previous) == buffer.nameId(s)
        && buffer.targetId(previous) == buffer.targetId(s)
        && buffer.parent(previous) == buffer.parent(s)
        && buffer.status(previous) == buffer.status(s)
        && buffer.error(previous) == buffer.error(s)
        && buffer.stack(previous) == null
        && buffer.stack(s) == null
        && same(buffer.detail(previous), buffer.detail(s))
        && same(buffer.threadName(previous), buffer.threadName(s));
  }

  private static boolean same(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  /** The folded steps of the sealed {@code buffer}. */
  public static ProfileSteps of(StepBuffer buffer) {
    return new ProfileSteps(buffer);
  }

  /** Folded steps. */
  public int size() {
    return size;
  }

  /** Index of the parent step, or -1 at the top level. */
  public int parent(int index) {
    check(index);
    return parent[index];
  }

  public int type(int index) {
    check(index);
    return type[index];
  }

  public long nameId(int index) {
    check(index);
    return nameId[index];
  }

  public long targetId(int index) {
    check(index);
    return targetId[index];
  }

  public long startOffsetNanos(int index) {
    check(index);
    return startOffset[index];
  }

  /** Elapsed nanoseconds (the sum over folded steps), -1 for a step that was never closed. */
  public long elapsedNanos(int index) {
    check(index);
    return elapsed[index];
  }

  public long rows(int index) {
    check(index);
    return rows[index];
  }

  public long fetchNanos(int index) {
    check(index);
    return fetch[index];
  }

  public int status(int index) {
    check(index);
    return status[index];
  }

  public boolean error(int index) {
    check(index);
    return error[index];
  }

  public String detail(int index) {
    check(index);
    return detail[index];
  }

  public long[] stack(int index) {
    check(index);
    return stack[index];
  }

  /** Name of the thread that recorded the step (see {@link StepBuffer#threadName(int)}). */
  public String threadName(int index) {
    check(index);
    return thread[index];
  }

  /** How many consecutive steps this one stands for (11 {@code repeat_count}); 1 for one step. */
  public int repeatCount(int index) {
    check(index);
    return repeat[index];
  }

  /** True when steps beyond {@code max-steps} were summarized (11 {@code truncated}). */
  public boolean truncated() {
    return truncated;
  }

  /** Every step that started (11 {@code total_steps}). */
  public long totalSteps() {
    return totalSteps;
  }

  /** Steps recorded one by one before folding. */
  public int recordedSteps() {
    return recordedSteps;
  }

  /** Steps neither recorded nor summarized (more than 256 summary keys). */
  public long summaryOverflow() {
    return summaryOverflow;
  }

  /** Summary entries, merged per (type, name id). */
  public int summarySize() {
    return summarySize;
  }

  public int summaryType(int key) {
    checkSummary(key);
    return summaryType[key];
  }

  public long summaryNameId(int key) {
    checkSummary(key);
    return summaryNameId[key];
  }

  public long summaryCount(int key) {
    checkSummary(key);
    return summaryCount[key];
  }

  public long summaryTotalNanos(int key) {
    checkSummary(key);
    return summarySum[key];
  }

  public long summaryMaxNanos(int key) {
    checkSummary(key);
    return summaryMax[key];
  }

  public long summaryErrors(int key) {
    checkSummary(key);
    return summaryErrors[key];
  }

  private void check(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("step " + index + " of " + size);
    }
  }

  private void checkSummary(int key) {
    if (key < 0 || key >= summarySize) {
      throw new IndexOutOfBoundsException("summary " + key + " of " + summarySize);
    }
  }
}
