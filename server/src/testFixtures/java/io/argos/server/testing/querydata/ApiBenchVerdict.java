/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

/**
 * The N-07/N-24 verdict of one query API bench case (QueryApiBenchIT; ADR-0010 C-180): a case
 * passes only when both hold,
 *
 * <ul>
 *   <li>(a) the warm median (of 3 calls) is at most the target, and
 *   <li>(b) the cold call (the first after a server restart) is at most {@value #COLD_FACTOR} times
 *       the target.
 * </ul>
 *
 * Warm alone would pass a slow first screen after a restart; cold alone shifts with the JVM and the
 * page cache. Both conditions are reported as columns of their own.
 *
 * @param warmWithin (a)
 * @param coldWithin (b)
 */
public record ApiBenchVerdict(boolean warmWithin, boolean coldWithin) {

  /** The cold call may take this many times the target (C-180 (b)). */
  public static final int COLD_FACTOR = 2;

  /** The verdict of a measured case. */
  public static ApiBenchVerdict of(double coldMs, double warmMedianMs, long targetMs) {
    return new ApiBenchVerdict(warmMedianMs <= targetMs, coldMs <= (double) COLD_FACTOR * targetMs);
  }

  /** Both conditions hold. */
  public boolean pass() {
    return warmWithin && coldWithin;
  }

  /** The case verdict: {@code PASS}/{@code FAIL} enforced, else a reference label. */
  public String overall(boolean enforce) {
    if (enforce) {
      return pass() ? "PASS" : "FAIL";
    }
    return pass() ? "reference (within)" : "reference (above)";
  }

  /** The (a) column: {@code PASS}/{@code FAIL} enforced, else {@code within}/{@code above}. */
  public String warm(boolean enforce) {
    return label(warmWithin, enforce);
  }

  /** The (b) column: {@code PASS}/{@code FAIL} enforced, else {@code within}/{@code above}. */
  public String cold(boolean enforce) {
    return label(coldWithin, enforce);
  }

  private static String label(boolean within, boolean enforce) {
    if (enforce) {
      return within ? "PASS" : "FAIL";
    }
    return within ? "within" : "above";
  }
}
