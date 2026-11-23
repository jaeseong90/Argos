/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

/**
 * The outcome of a rollup operation run outside the start sequence ({@link ReconcileService},
 * {@link ApdexReapplier}): its result, or why it did not run or did not finish. Messages are one
 * operator-facing line without secret values.
 *
 * @param <T> the result type
 */
public sealed interface Outcome<T> {

  /** The operation finished. */
  record Done<T>(T value) implements Outcome<T> {}

  /**
   * The operation did not run or failed.
   *
   * @param reason why
   * @param message one line, no secret
   */
  record Refused<T>(Reason reason, String message) implements Outcome<T> {}

  /** Why an operation did not run or did not finish. */
  enum Reason {
    /** Another rebuild, check or reapply holds the rollup lock. */
    BUSY,
    /** The scheduled check of the day already ran (another server node, a restart). */
    ALREADY_DONE,
    /** Invalid arguments (a day after today, an unknown table, a range that is too long). */
    USAGE,
    /** Configuration, schema, database or refusal (retention). */
    FAILURE
  }
}
