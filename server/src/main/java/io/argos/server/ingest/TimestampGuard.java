/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import java.util.concurrent.TimeUnit;

/**
 * The timestamp guard of D-15 with the session correction of ADR-0009 C-84 as ADR-0010 C-129 fixes
 * it. Pure arithmetic: the inputs are the agent timestamp, the correction decided at receipt and
 * written to the WAL record header ({@code ts_correction_ms}, never recomputed, C-129 (3)) and the
 * server receive time of the record header, so the live path, recovery and replay store the same
 * value.
 *
 * <ol>
 *   <li>corrected = agent ts − correction (C-129 (2); the correction is 0 for a clock that is
 *       behind or less than 5 s ahead, C-129 (1), (5), decided by {@code ClockOffsetProvider});
 *   <li>corrected &gt; received + 5 s: stored as the receive time rounded down to the table's grid
 *       (xlog and profile_step: the millisecond, statistics 10 s, metrics 5 s) and counted {@code
 *       record_clamp} (C-129 (4));
 *   <li>corrected &lt; received − 7 days: the record is rejected and counted (D-15, per record, not
 *       per batch);
 *   <li>received − stored &gt; 60 s: {@code late} (stored, never published in real time).
 * </ol>
 *
 * <p>{@code tx_id} is never changed (C-129 (6)).
 */
public final class TimestampGuard {

  /** D-15: an agent clock this far ahead is clamped. */
  public static final long FUTURE_TOLERANCE_MS = 5_000;

  /** D-15: records older than this are rejected. */
  public static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);

  /** D-15: rows received this much after their time are late. */
  public static final long LATE_MS = 60_000;

  /** Grid of xlog and profile_step timestamps (DateTime64(3)). */
  public static final long GRID_MS = 1;

  /** Grid of statistics windows (10 s). */
  public static final long GRID_STAT_MS = 10_000;

  /** Grid of metric samples (5 s). */
  public static final long GRID_METRIC_MS = 5_000;

  private TimestampGuard() {}

  /**
   * The guarded timestamp of one record.
   *
   * @param tsMs stored timestamp, epoch milliseconds (meaningless when {@code rejected})
   * @param corrected whether the session correction applied (count {@code session_offset})
   * @param clamped whether the record clamp applied (count {@code record_clamp})
   * @param rejected whether the record is dropped
   * @param late whether {@code received − tsMs > 60 s}
   */
  public record Result(
      long tsMs, boolean corrected, boolean clamped, boolean rejected, boolean late) {}

  /**
   * Guards one agent timestamp.
   *
   * @param agentTsMs the timestamp the agent sent (agent clock)
   * @param correctionMs the record header's {@code ts_correction_ms} (0 or a positive multiple of
   *     10 s)
   * @param receivedMs the record header's {@code received_ms}
   * @param gridMs the table's grid ({@link #GRID_MS}, {@link #GRID_STAT_MS}, {@link
   *     #GRID_METRIC_MS})
   */
  public static Result guard(long agentTsMs, long correctionMs, long receivedMs, long gridMs) {
    long correction = Math.max(0, correctionMs);
    long corrected = saturatingSubtract(agentTsMs, correction);
    long ts;
    boolean clamped = false;
    if (corrected > saturatingAdd(receivedMs, FUTURE_TOLERANCE_MS)) {
      ts = Math.floorDiv(receivedMs, gridMs) * gridMs;
      clamped = true;
    } else if (corrected < saturatingSubtract(receivedMs, MAX_AGE_MS)) {
      return new Result(corrected, correction > 0, false, true, false);
    } else {
      ts = corrected;
    }
    boolean late = saturatingSubtract(receivedMs, ts) > LATE_MS;
    return new Result(ts, correction > 0, clamped, false, late);
  }

  private static long saturatingSubtract(long a, long b) {
    long r = a - b;
    if (((a ^ b) & (a ^ r)) < 0) {
      return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return r;
  }

  private static long saturatingAdd(long a, long b) {
    long r = a + b;
    if (((a ^ r) & (b ^ r)) < 0) {
      return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return r;
  }
}
