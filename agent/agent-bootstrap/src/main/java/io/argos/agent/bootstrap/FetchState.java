/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * The fetch state of one {@code ResultSet} object (10 §6.3 ContextStore {@code ResultSet ->
 * FetchInfo}): the rows read with {@code next()} and the time from the first {@code next()} on,
 * which the JDBC helper adds to the SQL step {@link #seq} of the transaction {@link #txId} with
 * {@link Tracer#stepFetch} at {@code close()} or at the next execution of the same statement (11
 * {@code ProfileStep.fetch_us}, {@code rows}). A bootstrap type with numbers only (10 §5.3 A-9).
 *
 * <p>The per-row path reads the clock twice per result set at most: at the first {@code next()}
 * ({@link #begin}) and at the {@code next()} that returns false ({@link #row}); the rows in between
 * only increment {@link #rows}.
 *
 * <p>Plain fields, no synchronization: a result set is read by one thread at a time and the advice
 * path takes no lock (N-05).
 *
 * <p>Addition to 10 §3 (WO-13).
 */
public final class FetchState {
  /** The SQL step the rows belong to, -1 when the execution was not recorded. */
  public int seq = -1;

  /** {@code tx_id} of the transaction of the step {@link #seq}; 0 when unknown. */
  public long txId;

  /** Rows read so far. */
  public long rows;

  /** {@code System.nanoTime()} of the first {@code next()}; valid when {@link #started()}. */
  public long firstNextNanos;

  /**
   * {@code System.nanoTime()} of the last {@code next()} that was timed: the one that returned
   * false (end of the rows), or any call of {@link #next(long, boolean)}; valid when {@link
   * #ended()} or after {@link #next(long, boolean)}.
   */
  public long lastNextNanos;

  private boolean started;
  private boolean ended;
  private boolean reported;

  public FetchState() {}

  /** The fetch state of a result set produced by the SQL step {@code seq}. */
  public FetchState(int seq) {
    this.seq = seq;
  }

  /** The fetch state of a result set produced by the SQL step {@code seq} of {@code txId}. */
  public FetchState(int seq, long txId) {
    this.seq = seq;
    this.txId = txId;
  }

  /**
   * One {@code next()} call at {@code nowNanos}; {@code row} is its result (true: one more row).
   * The first call starts the fetch time.
   */
  public void next(long nowNanos, boolean row) {
    begin(nowNanos);
    lastNextNanos = nowNanos;
    if (row) {
      if (rows < Long.MAX_VALUE) {
        rows++;
      }
    } else {
      ended = true;
    }
  }

  /** True until the first {@code next()} started the fetch time ({@link #begin}). */
  public boolean needsStart() {
    return !started;
  }

  /** Enter of a {@code next()} call: the first one starts the fetch time at {@code nowNanos}. */
  public void begin(long nowNanos) {
    if (!started) {
      started = true;
      firstNextNanos = nowNanos;
    }
  }

  /**
   * Exit of a {@code next()} call that returned {@code row}: counts the row, or, for the first call
   * that returned false, marks the end of the rows at {@code System.nanoTime()}.
   */
  public void row(boolean row) {
    if (row) {
      if (rows < Long.MAX_VALUE) {
        rows++;
      }
    } else if (!ended) {
      ended = true;
      lastNextNanos = System.nanoTime();
    }
  }

  /** True once {@code next()} was called. */
  public boolean started() {
    return started;
  }

  /** True once a {@code next()} call returned false. */
  public boolean ended() {
    return ended;
  }

  /** Fetch time from the first {@code next()} to {@code endNanos}; 0 before the first call. */
  public long fetchNanos(long endNanos) {
    return started ? Math.max(0L, endNanos - firstNextNanos) : 0L;
  }

  /**
   * The end of the fetch time when the rows are reported at {@code nowNanos}: the time of the
   * {@code next()} that returned false when there was one, else {@code nowNanos} (the {@code
   * close()} or the next execution of the statement).
   */
  public long endNanos(long nowNanos) {
    return ended ? lastNextNanos : nowNanos;
  }

  /**
   * Marks the state reported; true for the first call only, so that {@code close()} after the next
   * execution of the statement does not add the rows twice.
   */
  public boolean markReported() {
    if (reported) {
      return false;
    }
    reported = true;
    return true;
  }
}
