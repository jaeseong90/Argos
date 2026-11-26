/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

/** The verdict of one check or scenario of the chaos report. */
public enum Verdict {
  /** Measured and within the target. */
  PASS,
  /** Measured and outside the target, or not measured where it must be. */
  FAIL,
  /** Measured, but the run cannot judge it (shared machine or short profile): a reference. */
  INVALID,
  /** Not run, with a reason. */
  SKIPPED,
  /** A value the report shows without a target (counts, references). */
  INFO
}
