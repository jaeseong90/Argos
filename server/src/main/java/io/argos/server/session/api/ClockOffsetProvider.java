/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.OptionalLong;

/**
 * The clock offset of a live instance (11 §9, DSN-11-05) and the C-84 timestamp correction derived
 * from it (ADR-0009 C-84 as fixed by ADR-0010 C-129).
 *
 * <p>Sign (ADR-0010 C-139): the offset is <b>server − agent</b>, the 11 §9 formula {@code ((t2 −
 * t1) + (t3 − t4)) / 2}; a server clock 3 s ahead of the agent gives {@code +3000}. An agent clock
 * ahead of the server gives a negative offset.
 *
 * <p>Implemented by lane {@code collector} ({@code ClockSync}): before the first completed
 * heartbeat round the value is the {@code ServerHello.server_time_ms − AgentHello.sent_at_ms}
 * estimate; afterwards the median of the last 5 accepted rounds, a round being discarded when its
 * rtt is above 3 times the median rtt of the last 5 rounds, discarded ones included (C-139 (2); the
 * contract vectors {@code argos-v1-vectors.json} "clock" section). The offset lives in memory only
 * (its persistence is WO-22's), which is why the correction is decided at receipt and written to
 * the WAL (C-129 (3)).
 */
@FunctionalInterface
public interface ClockOffsetProvider {

  /** C-129 (1): an agent clock more than this far ahead of the server is corrected. */
  long CORRECTION_THRESHOLD_MS = 5_000L;

  /**
   * C-129 (2): the correction is a multiple of this (the 5 s metric grid and 10 s stat window stay
   * aligned).
   */
  long CORRECTION_STEP_MS = 10_000L;

  /**
   * The current median offset (server − agent) of {@code instanceId}, milliseconds; empty when the
   * instance has no live session.
   */
  OptionalLong medianOffsetMs(int instanceId);

  /**
   * The C-129 correction for a batch of {@code instanceId} received now: {@link
   * #correctionMs(OptionalLong)} of the current median. Called once at receipt; the result goes
   * into {@link SessionContext#tsCorrectionMs()} and the WAL record header {@code
   * ts_correction_ms}.
   */
  default long tsCorrectionMs(int instanceId) {
    return correctionMs(medianOffsetMs(instanceId));
  }

  /**
   * ADR-0010 C-129, the only definition of the rule (lane {@code collector} applies it at receipt,
   * lane {@code durable-ingest} only reads the stored value):
   *
   * <ol>
   *   <li>a session whose median offset is below {@code −5,000} ms (agent clock more than 5 s
   *       ahead) is a corrected instance; {@code −5,000} itself, any smaller lead, a clock behind
   *       (offset {@code > 0}, C-129 (5)) and an unknown offset give 0;
   *   <li>the correction is the lead ({@code −offset}) rounded half up to a multiple of 10 s (C-129
   *       (2)), so it is never 0 for a corrected instance: a lead of 5,001 ms gives 10,000;
   *   <li>the value is subtracted from {@code xlog.ts}, {@code profile_step.ts}, statistics {@code
   *       window_start} and metric {@code ts}; {@code tx_id} is never changed (C-129 (6)); D-15 is
   *       applied to the corrected value (C-129 (4)).
   * </ol>
   *
   * @return milliseconds to subtract from the agent timestamps, 0 or a positive multiple of {@link
   *     #CORRECTION_STEP_MS}
   */
  static long correctionMs(OptionalLong medianOffsetMs) {
    if (medianOffsetMs.isEmpty()) {
      return 0;
    }
    long offset = medianOffsetMs.getAsLong();
    if (offset >= -CORRECTION_THRESHOLD_MS) {
      return 0;
    }
    long lead = offset == Long.MIN_VALUE ? Long.MAX_VALUE : -offset;
    long steps = lead / CORRECTION_STEP_MS;
    if (lead % CORRECTION_STEP_MS >= CORRECTION_STEP_MS / 2) {
      steps++;
    }
    long maxSteps = Long.MAX_VALUE / CORRECTION_STEP_MS;
    return Math.min(steps, maxSteps) * CORRECTION_STEP_MS;
  }
}
