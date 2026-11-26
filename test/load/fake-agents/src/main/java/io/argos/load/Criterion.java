/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

/**
 * One row of the N-06 / N-18 / D-13 table of the load report.
 *
 * @param id test ID ({@code T-NFR-06}, {@code T-NFR-18}, ...)
 * @param requirement requirement ({@code N-06}, {@code N-18}, {@code D-13}, {@code 12 §11})
 * @param title what is judged
 * @param target the target, as the requirement states it
 * @param measured the measured value (text)
 * @param kind whether the profile makes it INVALID ({@link Kind#MEASUREMENT}) or it is judged in
 *     every profile ({@link Kind#INVARIANT})
 * @param verdict the verdict
 * @param note how it was measured or why it is INVALID
 */
public record Criterion(
    String id,
    String requirement,
    String title,
    String target,
    String measured,
    Kind kind,
    Verdict verdict,
    String note) {

  /** How a criterion is judged. */
  public enum Kind {
    /**
     * A timing or resource target: PASS/FAIL only in an N-06 run at full scale, INVALID otherwise.
     */
    MEASUREMENT,
    /**
     * A correctness or configuration rule (D-13 counts, OOM 0, ClickHouse settings): PASS/FAIL in
     * every profile; a FAIL fails the task.
     */
    INVARIANT
  }

  /** Whether this row fails the task: an invariant that did not pass. */
  public boolean failsTask() {
    return kind == Kind.INVARIANT && verdict != Verdict.PASS;
  }
}
