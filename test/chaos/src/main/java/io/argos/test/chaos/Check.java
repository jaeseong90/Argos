/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Objects;

/**
 * One row of a scenario's table in the chaos report.
 *
 * @param id test ID ({@code T-CH-001}, {@code T-NFR-05}, ...)
 * @param title what is judged
 * @param target the target as 18 §11.1 states it
 * @param measured the measured value (text)
 * @param kind how the row is judged
 * @param verdict the verdict
 * @param note how it was measured, or why it is INVALID or SKIPPED
 */
public record Check(
    String id,
    String title,
    String target,
    String measured,
    Kind kind,
    Verdict verdict,
    String note) {

  /** How a row is judged ({@link Judge}). */
  public enum Kind {
    /**
     * Timing or latency (p99 band, recovery time): PASS/FAIL only with profile {@code full} on a
     * dedicated runner, INVALID otherwise.
     */
    MEASUREMENT,
    /** Correctness (counts, duplicates, ERROR lines, what the server shows): always PASS/FAIL. */
    INVARIANT,
    /** A value without a target. */
    INFO
  }

  /** Validates. */
  public Check {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(verdict, "verdict");
    target = target == null ? "" : target;
    measured = measured == null ? "" : measured;
    note = note == null ? "" : note;
  }

  /** Whether this row fails its scenario. */
  public boolean fails() {
    return verdict == Verdict.FAIL;
  }
}
