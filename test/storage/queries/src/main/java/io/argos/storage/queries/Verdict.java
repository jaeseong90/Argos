/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

/** Outcome of a query variant or a procedure against its criterion. */
public enum Verdict {
  /** The criterion holds. */
  PASS,
  /** The criterion does not hold. */
  FAIL,
  /** Measured and reported; the criterion is {@code record}. */
  RECORDED,
  /** The measurement cannot be judged: an expectation failed (e.g. a lookup found no row). */
  INVALID,
  /** Not run (missing parameter, unsupported on this server). */
  SKIPPED,
  /**
   * Not run because the data has no case for it: an optional probe ({@code @optional}) found no
   * value, e.g. no transaction of a skewed clock for the A-6 fallback variants.
   */
  NOT_APPLICABLE,
  /** The query failed with an error. */
  ERROR
}
