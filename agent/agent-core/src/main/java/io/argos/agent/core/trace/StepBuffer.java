/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.core.context.ThreadNames;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Steps of one transaction (10 §9.1, DSN-10-08), stored as a structure of arrays. Lock-free:
 * application threads of the transaction (the request thread and async workers) record steps
 * concurrently with atomics only, and never wait (N-05, "no lock on an advice path").
 *
 * <p><b>Layout and growth.</b> Sequence numbers are handed out by one atomic counter. The slots
 * live in chunks: the first chunk has {@value #INITIAL_CAPACITY} slots, every further chunk doubles
 * the capacity (64, 128, 256, 512, 1,024 slots in total), and the last chunk is cut at {@code
 * sampling.profile.max-steps} (1,000). Growing allocates the next chunk (published with a
 * compare-and-set) instead of copying arrays, so a writer never sees a moved array. No chunk exists
 * before the first step: a transaction without steps allocates no step arrays.
 *
 * <p><b>Summary mode.</b> Steps beyond {@code max-steps} are not recorded one by one: they are
 * aggregated per (type, name id) into at most {@value #MAX_SUMMARY_KEYS} summary entries (count,
 * total time, maximum time, errors; 10 §9.1 {@code SummaryStep}); steps beyond those keys are only
 * counted ({@link #summaryOverflow()}). {@link #totalSteps()} counts every step that started and
 * {@link #summarized()} tells that the profile is truncated (11 §4.3 {@code truncated}, {@code
 * total_steps}). Up to {@value #MAX_PENDING_SUMMARY} summary steps can be open at once; a further
 * one is counted without a duration.
 *
 * <p><b>Parents.</b> The caller passes the parent: the top of the open-step stack of the current
 * activation scope ({@code TracerImpl}, WO-02 D-14 fixed): an ASYNC step that a worker opened is
 * never the parent of a request-thread step.
 *
 * <p><b>Sealing.</b> {@link #seal()} at the end of the transaction stops new steps, details and
 * stacks; closing and fetch updates of steps already recorded still apply (fixed-size fields).
 * {@link #estimatedBytes()} is fixed at that moment, so the queues add and subtract one value.
 *
 * <p><b>Memory caps</b> (10 §14, every cap has a test in {@code StepBufferTest}): steps {@code
 * max-steps} (at most {@value #MAX_SLOTS}, {@value #SLOT_BYTES} bytes each), detail {@value
 * #DETAIL_MAX_CHARS} characters, stack {@value #MAX_STACK_FRAMES} frame ids, summary {@value
 * #MAX_SUMMARY_KEYS} keys and {@value #MAX_PENDING_SUMMARY} open summary steps, {@value
 * #MAX_THREADS} distinct step threads per transaction (names cut at {@value
 * ThreadNames#MAX_NAME_CHARS} characters; further threads read as {@value ThreadNames#OTHER}).
 */
public final class StepBuffer {
  /** Slots of the first chunk (10 §9.1 "초기 64"). */
  public static final int INITIAL_CAPACITY = 64;

  /** Chunks at most: 64 << 9 = 32,768 slots, above every allowed {@code max-steps}. */
  static final int MAX_CHUNKS = 10;

  /** Upper bound of {@code max-steps} that the buffer accepts. */
  public static final int MAX_SLOTS = INITIAL_CAPACITY << (MAX_CHUNKS - 1);

  /** Upper bound of a step detail (10 §3 stepDetail: 4 KB). */
  public static final int DETAIL_MAX_CHARS = 4096;

  /** Upper bound of a step stack (10 §13.1 depth 64; ERROR steps use capture.stack-depth). */
  public static final int MAX_STACK_FRAMES = 64;

  static final int MAX_SUMMARY_KEYS = 256;

  /** Summary steps that can be open at the same time (their start time is kept). */
  static final int MAX_PENDING_SUMMARY = 64;

  /** Sequence numbers at or above this value denote open summary steps. */
  public static final int SUMMARY_SEQ_BASE = 1 << 24;

  /** Distinct threads per transaction whose names are kept for step records. */
  static final int MAX_THREADS = 16;

  /** {@link #parent(int)} of a top-level step. */
  public static final int NO_PARENT = -1;

  /**
   * Estimated bytes of one slot in the arrays every chunk has (10 elements and the two lazily
   * created reference arrays, see {@link Chunk}); rows and fetch time add {@value #LAZY_LONG_BYTES}
   * bytes per slot each, batch sizes {@value #LAZY_INT_BYTES}, once a step of the chunk has them.
   */
  static final int SLOT_BYTES = 58;

  /** Bytes per slot of a lazily created {@code long} array (rows, fetch time). */
  static final int LAZY_LONG_BYTES = 8;

  /** Bytes per slot of the lazily created {@code int} array of batch sizes (WO-13). */
  static final int LAZY_INT_BYTES = 4;

  private static final int FREE = 0;
  private static final int OPEN = 1;
  private static final int CLOSING = 2;
  private static final int CLOSED = 3;

  /** The error flag in a slot's error byte; the bits above it hold the failure kind (WO-14). */
  private static final int ERROR_BIT = 1;

  private static final int FAILURE_SHIFT = 1;
  private static final int FAILURE_MASK = 0x7;

  private static final int SEALED = 1 << 30;
  private static final byte NO_THREAD = -1;
  private static final byte OTHER_THREAD = -2;

  /** Estimated bytes of the buffer object and its atomics, without chunks. */
  private static final long BASE_BYTES = 256L + 16L * MAX_THREADS;

  /** Estimated bytes of the summary tables. */
  private static final long SUMMARY_BYTES =
      512L + MAX_SUMMARY_KEYS * (4L + 4L + 8L + 8L * 4) + MAX_PENDING_SUMMARY * (4L + 4L + 8L);

  private final long baseNanos;
  private final int maxSteps;

  /** Next sequence number, with {@link #SEALED} once the transaction ended. */
  private final AtomicInteger next = new AtomicInteger();

  private final AtomicReferenceArray<Chunk> chunks = new AtomicReferenceArray<Chunk>(MAX_CHUNKS);
  private final AtomicLong total = new AtomicLong();
  private final AtomicReference<Summary> summary = new AtomicReference<Summary>();
  private final AtomicReferenceArray<StepThread> threads =
      new AtomicReferenceArray<StepThread>(MAX_THREADS);
  private final AtomicInteger threadCount = new AtomicInteger();
  private volatile long sealedBytes = -1L;

  /**
   * @param baseNanos transaction start ({@code System.nanoTime()}), the origin of step offsets
   * @param maxSteps recorded steps before the summary mode (1 to {@value #MAX_SLOTS})
   */
  public StepBuffer(long baseNanos, int maxSteps) {
    this.baseNanos = baseNanos;
    this.maxSteps = Math.max(1, Math.min(MAX_SLOTS, maxSteps));
  }

  /** {@code sampling.profile.max-steps} of this buffer. */
  public int maxSteps() {
    return maxSteps;
  }

  // --- writes (application threads) ---------------------------------------------------------

  /**
   * Opens a step whose parent is {@code parentSeq} ({@link #NO_PARENT} at the top level), recorded
   * by {@code thread}. Returns its sequence number, a summary handle ({@code >=} {@link
   * #SUMMARY_SEQ_BASE}) in summary mode, or -1 when the step is not recorded at all (sealed, or
   * counted only).
   */
  public int start(
      int stepType, long name, long target, long startNanos, int parentSeq, Thread thread) {
    int seq = reserve();
    if (seq == SEALED_RESERVE) {
      return -1;
    }
    total.incrementAndGet();
    if (seq < 0) {
      return startSummary(stepType, name, startNanos);
    }
    return record(seq, stepType, name, target, startNanos, parentSeq, thread);
  }

  /**
   * Opens a step like {@link #start} but only as a recorded step: returns -1, without counting or
   * summarizing anything, when the buffer is sealed or already in summary mode (all {@code
   * max-steps} slots taken). The slow-stack sampler's {@code STACK} steps (10 §13.1, WO-19), which
   * the agent adds on its own thread, use it: a sample is worth nothing as a summary entry.
   */
  public int startRecorded(
      int stepType, long name, long target, long startNanos, int parentSeq, Thread thread) {
    if (summary.get() != null) {
      return -1;
    }
    int seq = reserve();
    if (seq < 0) {
      return -1;
    }
    total.incrementAndGet();
    return record(seq, stepType, name, target, startNanos, parentSeq, thread);
  }

  /** {@link #reserve()} of a sealed buffer. */
  private static final int SEALED_RESERVE = Integer.MIN_VALUE;

  /**
   * The next sequence number, -1 when every recorded slot is taken (summary mode), or {@link
   * #SEALED_RESERVE}.
   */
  private int reserve() {
    while (true) {
      int n = next.get();
      if ((n & SEALED) != 0) {
        return SEALED_RESERVE;
      }
      if (n >= maxSteps) {
        return -1;
      }
      if (next.compareAndSet(n, n + 1)) {
        return n;
      }
    }
  }

  private int record(
      int seq,
      int stepType,
      long name,
      long target,
      long startNanos,
      int parentSeq,
      Thread thread) {
    Chunk chunk = chunkFor(seq, true);
    int i = seq - chunk.start;
    chunk.type[i] = stepType;
    chunk.parent[i] = parentSeq >= 0 && parentSeq < seq ? parentSeq : NO_PARENT;
    chunk.nameId[i] = name;
    chunk.targetId[i] = target;
    chunk.startOffset[i] = startNanos - baseNanos;
    chunk.elapsed[i] = -1L;
    chunk.thread[i] = threadIndex(thread);
    chunk.state.set(i, OPEN); // publishes the fields above
    return seq;
  }

  /** Closes a step opened by {@link #start}. Unknown or already closed handles are ignored. */
  public void end(int seq, int stepStatus, boolean failed, long rowCount, long endNanos) {
    end(seq, stepStatus, failed, rowCount, endNanos, 0);
  }

  /**
   * {@link #end(int, int, boolean, long, long)} of an HTTP step with its {@code FailureKind} (0 to
   * 7, WO-14), kept in the bits above the error flag of the same byte: no extra memory per slot. A
   * summary handle keeps only the error flag.
   */
  public void end(
      int seq, int stepStatus, boolean failed, long rowCount, long endNanos, int failureKind) {
    if (seq < 0) {
      return;
    }
    if (seq >= SUMMARY_SEQ_BASE) {
      endSummary(seq - SUMMARY_SEQ_BASE, failed, endNanos);
      return;
    }
    Chunk chunk = chunkFor(seq, false);
    if (chunk == null) {
      return;
    }
    int i = seq - chunk.start;
    if (!chunk.state.compareAndSet(i, OPEN, CLOSING)) {
      return;
    }
    chunk.elapsed[i] = Math.max(0L, endNanos - baseNanos - chunk.startOffset[i]);
    chunk.status[i] = stepStatus;
    chunk.error[i] =
        (byte) ((failed ? ERROR_BIT : 0) | ((failureKind & FAILURE_MASK) << FAILURE_SHIFT));
    if (rowCount != 0) {
      chunk.rows().addAndGet(i, rowCount);
    }
    chunk.state.set(i, CLOSED);
  }

  /**
   * Sets the {@code executeBatch} item count of a recorded step (10 §6.3, 11 {@code
   * ProfileStep.batch_size}); counts below 1, summary handles and unknown steps are ignored. Like
   * {@link #fetch}, a fixed-size field: it still applies after sealing.
   */
  public void batch(int seq, int batchSize) {
    if (batchSize < 1) {
      return;
    }
    Chunk chunk = recorded(seq);
    if (chunk != null) {
      chunk.batch().set(seq - chunk.start, batchSize);
    }
  }

  /** Adds fetched rows and fetch time to a recorded step (JDBC ResultSet, 10 §6.3). */
  public void fetch(int seq, long rowCount, long nanos) {
    Chunk chunk = recorded(seq);
    if (chunk != null) {
      int i = seq - chunk.start;
      if (rowCount != 0) {
        chunk.rows().addAndGet(i, rowCount);
      }
      if (nanos != 0) {
        chunk.fetch().addAndGet(i, nanos);
      }
    }
  }

  /** Sets the detail of a recorded step (cut to {@link #DETAIL_MAX_CHARS}); not after sealing. */
  public void detail(int seq, String text) {
    Chunk chunk = recorded(seq);
    if (chunk == null || text == null || isSealed()) {
      return;
    }
    String kept = text.length() > DETAIL_MAX_CHARS ? text.substring(0, DETAIL_MAX_CHARS) : text;
    AtomicReferenceArray<String> details = chunk.details();
    int i = seq - chunk.start;
    details.set(i, kept);
    if (isSealed()) {
      // Raced with seal(): the byte estimate may not include it, so it is not kept.
      details.compareAndSet(i, kept, null);
    }
  }

  /** Sets the stack (frame dictionary ids, at most {@link #MAX_STACK_FRAMES}) of a step. */
  public void stack(int seq, long[] frameIds) {
    Chunk chunk = recorded(seq);
    if (chunk == null || frameIds == null || isSealed()) {
      return;
    }
    long[] kept =
        frameIds.length > MAX_STACK_FRAMES
            ? java.util.Arrays.copyOf(frameIds, MAX_STACK_FRAMES)
            : frameIds;
    AtomicReferenceArray<long[]> stacks = chunk.stacks();
    int i = seq - chunk.start;
    stacks.set(i, kept);
    if (isSealed()) {
      stacks.compareAndSet(i, kept, null);
    }
  }

  /**
   * Ends recording (end of the transaction): later {@link #start}, {@link #detail} and {@link
   * #stack} calls are ignored and {@link #estimatedBytes()} is fixed. Returns false when the buffer
   * was already sealed.
   */
  public boolean seal() {
    while (true) {
      int n = next.get();
      if ((n & SEALED) != 0) {
        return false;
      }
      if (next.compareAndSet(n, n | SEALED)) {
        sealedBytes = computeBytes();
        return true;
      }
    }
  }

  public boolean isSealed() {
    return (next.get() & SEALED) != 0;
  }

  private Chunk recorded(int seq) {
    if (seq < 0 || seq >= SUMMARY_SEQ_BASE) {
      return null;
    }
    Chunk chunk = chunkFor(seq, false);
    if (chunk == null || chunk.state.get(seq - chunk.start) == FREE) {
      return null;
    }
    return chunk;
  }

  // --- chunks -------------------------------------------------------------------------------

  /** Chunk index of {@code seq}: 0 for 0..63, k for 32 << k .. (64 << k) - 1. */
  static int chunkIndex(int seq) {
    return seq < INITIAL_CAPACITY ? 0 : 32 - Integer.numberOfLeadingZeros(seq >>> 6);
  }

  /** First sequence number of chunk {@code index}. */
  static int chunkStart(int index) {
    return index == 0 ? 0 : 32 << index;
  }

  /** Slots of chunk {@code index} before the {@code max-steps} cut. */
  static int chunkSize(int index) {
    return index == 0 ? INITIAL_CAPACITY : 32 << index;
  }

  private Chunk chunkFor(int seq, boolean create) {
    if (seq >= maxSteps) {
      return null;
    }
    int index = chunkIndex(seq);
    Chunk chunk = chunks.get(index);
    if (chunk != null || !create) {
      return chunk;
    }
    int start = chunkStart(index);
    Chunk created = new Chunk(start, Math.min(chunkSize(index), maxSteps - start));
    if (chunks.compareAndSet(index, null, created)) {
      return created;
    }
    return chunks.get(index);
  }

  private Chunk readChunk(int seq) {
    if (seq < 0 || seq >= size()) {
      throw new IndexOutOfBoundsException("step " + seq + " of " + size());
    }
    return chunkFor(seq, false);
  }

  // --- threads ------------------------------------------------------------------------------

  private byte threadIndex(Thread thread) {
    if (thread == null) {
      return NO_THREAD;
    }
    long id = thread.getId();
    int known = Math.min(threadCount.get(), MAX_THREADS);
    for (int i = 0; i < known; i++) {
      StepThread entry = threads.get(i);
      if (entry != null && entry.id == id) {
        return (byte) i;
      }
    }
    int index = threadCount.getAndIncrement();
    if (index >= MAX_THREADS) {
      return OTHER_THREAD;
    }
    String name = thread.getName();
    if (name != null && name.length() > ThreadNames.MAX_NAME_CHARS) {
      name = name.substring(0, ThreadNames.MAX_NAME_CHARS);
    }
    threads.set(index, new StepThread(id, name));
    return (byte) index;
  }

  /** One thread that recorded steps: its id and name (cut) at its first step. */
  private static final class StepThread {
    final long id;
    final String name;

    StepThread(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  // --- summary mode -------------------------------------------------------------------------

  private Summary summary() {
    Summary current = summary.get();
    if (current != null) {
      return current;
    }
    Summary created = new Summary();
    return summary.compareAndSet(null, created) ? created : summary.get();
  }

  private int startSummary(int stepType, long name, long startNanos) {
    Summary s = summary();
    int key = s.key(stepType, name);
    if (key < 0) {
      s.overflow.incrementAndGet();
      return -1;
    }
    for (int slot = 0; slot < MAX_PENDING_SUMMARY; slot++) {
      if (s.pending.get(slot) == 0 && s.pending.compareAndSet(slot, 0, 1)) {
        s.pendingKey.set(slot, key);
        s.pendingStart.set(slot, startNanos);
        return SUMMARY_SEQ_BASE + slot;
      }
    }
    s.count.incrementAndGet(key); // too many open summary steps: counted without a duration
    return -1;
  }

  private void endSummary(int slot, boolean failed, long endNanos) {
    Summary s = summary.get();
    if (s == null || slot >= MAX_PENDING_SUMMARY || s.pending.get(slot) != 1) {
      return;
    }
    int key = s.pendingKey.get(slot);
    long elapsed = Math.max(0L, endNanos - s.pendingStart.get(slot));
    if (!s.pending.compareAndSet(slot, 1, 0)) {
      return;
    }
    s.count.incrementAndGet(key);
    s.sumNanos.addAndGet(key, elapsed);
    while (true) {
      long max = s.maxNanos.get(key);
      if (elapsed <= max || s.maxNanos.compareAndSet(key, max, elapsed)) {
        break;
      }
    }
    if (failed) {
      s.errors.incrementAndGet(key);
    }
  }

  /** The summary tables (created when the summary mode starts). */
  private static final class Summary {
    /** 0 free, 2 ready; a key is written before its state becomes 2. */
    final AtomicIntegerArray keyState = new AtomicIntegerArray(MAX_SUMMARY_KEYS);

    final AtomicInteger keys = new AtomicInteger();
    final int[] type = new int[MAX_SUMMARY_KEYS];
    final long[] nameId = new long[MAX_SUMMARY_KEYS];
    final AtomicLongArray count = new AtomicLongArray(MAX_SUMMARY_KEYS);
    final AtomicLongArray sumNanos = new AtomicLongArray(MAX_SUMMARY_KEYS);
    final AtomicLongArray maxNanos = new AtomicLongArray(MAX_SUMMARY_KEYS);
    final AtomicLongArray errors = new AtomicLongArray(MAX_SUMMARY_KEYS);
    final AtomicLong overflow = new AtomicLong();
    final AtomicIntegerArray pending = new AtomicIntegerArray(MAX_PENDING_SUMMARY);
    final AtomicIntegerArray pendingKey = new AtomicIntegerArray(MAX_PENDING_SUMMARY);
    final AtomicLongArray pendingStart = new AtomicLongArray(MAX_PENDING_SUMMARY);

    /**
     * The key of (type, name), added if new; -1 when all keys are taken. Two threads adding the
     * same new key at once may create two entries; readers merge them ({@code ProfileSteps}).
     */
    int key(int stepType, long name) {
      int known = Math.min(keys.get(), MAX_SUMMARY_KEYS);
      for (int k = 0; k < known; k++) {
        if (keyState.get(k) == 2 && type[k] == stepType && nameId[k] == name) {
          return k;
        }
      }
      while (true) {
        int k = keys.get();
        if (k >= MAX_SUMMARY_KEYS) {
          return -1;
        }
        if (keys.compareAndSet(k, k + 1)) {
          type[k] = stepType;
          nameId[k] = name;
          keyState.set(k, 2);
          return k;
        }
      }
    }
  }

  /** One chunk of slots ({@link #SLOT_BYTES} bytes per slot). */
  private static final class Chunk {
    final int start;
    final AtomicIntegerArray state;
    final int[] type;
    final int[] parent;
    final int[] status;
    final byte[] thread;
    final byte[] error;
    final long[] nameId;
    final long[] targetId;
    final long[] startOffset;
    final long[] elapsed;

    /**
     * Rows and fetch time, created by the first step that has them (WO-02 A-2: most steps of an
     * HTTP transaction have neither, and two eager arrays cost about 1 KB per transaction).
     */
    final AtomicReference<AtomicLongArray> rows = new AtomicReference<AtomicLongArray>();

    final AtomicReference<AtomicLongArray> fetch = new AtomicReference<AtomicLongArray>();

    /** {@code executeBatch} item counts, created by the first batch step of the chunk (WO-13). */
    final AtomicReference<AtomicIntegerArray> batch = new AtomicReference<AtomicIntegerArray>();

    final AtomicReference<AtomicReferenceArray<String>> details =
        new AtomicReference<AtomicReferenceArray<String>>();
    final AtomicReference<AtomicReferenceArray<long[]>> stacks =
        new AtomicReference<AtomicReferenceArray<long[]>>();

    Chunk(int start, int length) {
      this.start = start;
      this.state = new AtomicIntegerArray(length);
      this.type = new int[length];
      this.parent = new int[length];
      this.status = new int[length];
      this.thread = new byte[length];
      this.error = new byte[length];
      this.nameId = new long[length];
      this.targetId = new long[length];
      this.startOffset = new long[length];
      this.elapsed = new long[length];
    }

    int length() {
      return type.length;
    }

    AtomicLongArray rows() {
      return longs(rows);
    }

    AtomicLongArray fetch() {
      return longs(fetch);
    }

    AtomicIntegerArray batch() {
      AtomicIntegerArray current = batch.get();
      if (current != null) {
        return current;
      }
      AtomicIntegerArray created = new AtomicIntegerArray(length());
      return batch.compareAndSet(null, created) ? created : batch.get();
    }

    private AtomicLongArray longs(AtomicReference<AtomicLongArray> holder) {
      AtomicLongArray current = holder.get();
      if (current != null) {
        return current;
      }
      AtomicLongArray created = new AtomicLongArray(length());
      return holder.compareAndSet(null, created) ? created : holder.get();
    }

    AtomicReferenceArray<String> details() {
      AtomicReferenceArray<String> current = details.get();
      if (current != null) {
        return current;
      }
      AtomicReferenceArray<String> created = new AtomicReferenceArray<String>(length());
      return details.compareAndSet(null, created) ? created : details.get();
    }

    AtomicReferenceArray<long[]> stacks() {
      AtomicReferenceArray<long[]> current = stacks.get();
      if (current != null) {
        return current;
      }
      AtomicReferenceArray<long[]> created = new AtomicReferenceArray<long[]>(length());
      return stacks.compareAndSet(null, created) ? created : stacks.get();
    }
  }

  // --- read access (exporters after the end; tests) -----------------------------------------

  /** Recorded steps (sequence numbers 0 .. size-1); summary steps are not included. */
  public int size() {
    return Math.min(next.get() & ~SEALED, maxSteps);
  }

  /** Every step that started, recorded or summarized or only counted (11 {@code total_steps}). */
  public long totalSteps() {
    return total.get();
  }

  /** True when the step {@code seq} was written (a slot can be taken but not yet written). */
  public boolean isRecorded(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk != null && chunk.state.get(seq - chunk.start) != FREE;
  }

  /** True when the step {@code seq} was closed by {@link #end}. */
  public boolean isClosed(int seq) {
    if (seq < 0 || seq >= SUMMARY_SEQ_BASE) {
      return true;
    }
    Chunk chunk = chunkFor(seq, false);
    return chunk == null || chunk.state.get(seq - chunk.start) == CLOSED;
  }

  public int type(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : chunk.type[seq - chunk.start];
  }

  /** Sequence number of the parent step, {@link #NO_PARENT} at the top level. */
  public int parent(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? NO_PARENT : chunk.parent[seq - chunk.start];
  }

  public long nameId(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : chunk.nameId[seq - chunk.start];
  }

  public long targetId(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : chunk.targetId[seq - chunk.start];
  }

  public long startOffsetNanos(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : chunk.startOffset[seq - chunk.start];
  }

  /** Elapsed nanoseconds, -1 for a step that was never closed. */
  public long elapsedNanos(int seq) {
    Chunk chunk = readChunk(seq);
    if (chunk == null) {
      return -1L;
    }
    int i = seq - chunk.start;
    return chunk.state.get(i) == CLOSED ? chunk.elapsed[i] : -1L;
  }

  public int status(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : chunk.status[seq - chunk.start];
  }

  public boolean error(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk != null && (chunk.error[seq - chunk.start] & ERROR_BIT) != 0;
  }

  /**
   * The {@code FailureKind} of an HTTP step ({@link #end(int, int, boolean, long, long, int)}, 11
   * {@code ProfileStep.failure}); 0 for other steps and steps without failure.
   */
  public int failure(int seq) {
    Chunk chunk = readChunk(seq);
    return chunk == null ? 0 : (chunk.error[seq - chunk.start] >> FAILURE_SHIFT) & FAILURE_MASK;
  }

  public long rows(int seq) {
    Chunk chunk = readChunk(seq);
    AtomicLongArray rows = chunk == null ? null : chunk.rows.get();
    return rows == null ? 0 : rows.get(seq - chunk.start);
  }

  public long fetchNanos(int seq) {
    Chunk chunk = readChunk(seq);
    AtomicLongArray fetch = chunk == null ? null : chunk.fetch.get();
    return fetch == null ? 0 : fetch.get(seq - chunk.start);
  }

  /** The {@code executeBatch} item count of the step ({@link #batch}), 0 when none was set. */
  public int batchSize(int seq) {
    Chunk chunk = readChunk(seq);
    AtomicIntegerArray batch = chunk == null ? null : chunk.batch.get();
    return batch == null ? 0 : batch.get(seq - chunk.start);
  }

  public String detail(int seq) {
    Chunk chunk = readChunk(seq);
    AtomicReferenceArray<String> details = chunk == null ? null : chunk.details.get();
    return details == null ? null : details.get(seq - chunk.start);
  }

  public long[] stack(int seq) {
    Chunk chunk = readChunk(seq);
    AtomicReferenceArray<long[]> stacks = chunk == null ? null : chunk.stacks.get();
    return stacks == null ? null : stacks.get(seq - chunk.start);
  }

  /**
   * Name of the thread that opened the step {@code seq} (cut at {@value ThreadNames#MAX_NAME_CHARS}
   * characters), {@value ThreadNames#OTHER} beyond {@value #MAX_THREADS} threads, null when
   * unknown.
   */
  public String threadName(int seq) {
    Chunk chunk = readChunk(seq);
    if (chunk == null) {
      return null;
    }
    byte index = chunk.thread[seq - chunk.start];
    if (index == OTHER_THREAD) {
      return ThreadNames.OTHER;
    }
    if (index < 0) {
      return null;
    }
    StepThread entry = threads.get(index);
    return entry == null ? null : entry.name;
  }

  /** True once a step was aggregated instead of recorded (the profile is truncated). */
  public boolean summarized() {
    return summary.get() != null;
  }

  /** Summary keys taken (a key may appear twice after a race; readers merge). */
  public int summaryKeys() {
    Summary s = summary.get();
    return s == null ? 0 : Math.min(s.keys.get(), MAX_SUMMARY_KEYS);
  }

  /** True when the summary key {@code key} is fully written. */
  public boolean summaryReady(int key) {
    Summary s = summary.get();
    return s != null && s.keyState.get(key) == 2;
  }

  public int summaryType(int key) {
    return summaryOrFail().type[key];
  }

  public long summaryNameId(int key) {
    return summaryOrFail().nameId[key];
  }

  public long summaryCount(int key) {
    return summaryOrFail().count.get(key);
  }

  public long summaryTotalNanos(int key) {
    return summaryOrFail().sumNanos.get(key);
  }

  public long summaryMaxNanos(int key) {
    return summaryOrFail().maxNanos.get(key);
  }

  public long summaryErrors(int key) {
    return summaryOrFail().errors.get(key);
  }

  /** Steps dropped entirely (more than {@value #MAX_SUMMARY_KEYS} distinct summary keys). */
  public long summaryOverflow() {
    Summary s = summary.get();
    return s == null ? 0 : s.overflow.get();
  }

  private Summary summaryOrFail() {
    Summary s = summary.get();
    if (s == null) {
      throw new IndexOutOfBoundsException("no summary steps");
    }
    return s;
  }

  /**
   * Estimated heap bytes of this buffer (queue byte bounds, 10 §10.1, N-02): fixed when sealed.
   * Covers the buffer, its chunks ({@value #SLOT_BYTES} bytes per slot), details, stacks, step
   * thread names and the summary tables.
   */
  public long estimatedBytes() {
    long sealed = sealedBytes;
    return sealed >= 0 ? sealed : computeBytes();
  }

  private long computeBytes() {
    long bytes = BASE_BYTES;
    for (int index = 0; index < MAX_CHUNKS; index++) {
      Chunk chunk = chunks.get(index);
      if (chunk == null) {
        continue;
      }
      int length = chunk.length();
      bytes += 64L + 13L * 16L + (long) length * SLOT_BYTES;
      if (chunk.rows.get() != null) {
        bytes += 32L + (long) length * LAZY_LONG_BYTES;
      }
      if (chunk.fetch.get() != null) {
        bytes += 32L + (long) length * LAZY_LONG_BYTES;
      }
      if (chunk.batch.get() != null) {
        bytes += 32L + (long) length * LAZY_INT_BYTES;
      }
      AtomicReferenceArray<String> details = chunk.details.get();
      if (details != null) {
        bytes += 16L + 4L * length;
        for (int i = 0; i < length; i++) {
          String text = details.get(i);
          if (text != null) {
            bytes += 40L + 2L * text.length();
          }
        }
      }
      AtomicReferenceArray<long[]> stacks = chunk.stacks.get();
      if (stacks != null) {
        bytes += 16L + 4L * length;
        for (int i = 0; i < length; i++) {
          long[] frames = stacks.get(i);
          if (frames != null) {
            bytes += 16L + 8L * frames.length;
          }
        }
      }
    }
    int known = Math.min(threadCount.get(), MAX_THREADS);
    for (int i = 0; i < known; i++) {
      StepThread entry = threads.get(i);
      if (entry != null) {
        bytes += 32L + 40L + (entry.name == null ? 0 : 2L * entry.name.length());
      }
    }
    if (summary.get() != null) {
      bytes += SUMMARY_BYTES;
    }
    return bytes;
  }
}
