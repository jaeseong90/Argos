/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

/** The verdict of one criterion of the load report (03 §6 report format, docs/bench style). */
public enum Verdict {
  /** Measured and within the target. */
  PASS,
  /** Measured and outside the target, or not measured where it had to be. */
  FAIL,
  /**
   * Not a valid measurement of the target: a timing or resource criterion of a run that is not N-06
   * at full scale on 13 §7.1 hardware (profile other than {@code full}, or a full-profile run with
   * reduced rates). The measured value is still reported as a reference.
   */
  INVALID
}
