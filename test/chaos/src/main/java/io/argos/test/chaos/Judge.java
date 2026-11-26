/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Collection;
import java.util.Set;

/**
 * The verdict rules of the chaos report (18 §11.1, §2.2; same split as the server load report of
 * WO-29):
 *
 * <ul>
 *   <li>{@link Check.Kind#INVARIANT} rows (drop accounting, duplicates, agent ERROR lines, what the
 *       server shows) are PASS or FAIL in every run;
 *   <li>{@link Check.Kind#MEASUREMENT} rows (app p99 within the noise band, recovery within 60 s)
 *       are PASS or FAIL only in a valid run: profile {@code full} on a dedicated runner ({@link
 *       #DEDICATED_PROFILES}); any other run reports them INVALID with the measured value as a
 *       reference. A measurement a valid run could not take is FAIL.
 * </ul>
 *
 * A scenario is FAIL when one row is FAIL, else INVALID when one row is INVALID, else PASS.
 */
public final class Judge {

  /**
   * Machine profiles of the dedicated runners where a chaos run is valid (18 §2.3: chaos runs on
   * the self-hosted {@code argos-matrix} runner).
   */
  public static final Set<String> DEDICATED_PROFILES = Set.of("argos-matrix");

  private Judge() {}

  /** Whether a run of {@code profile} on {@code machineProfile} may judge measurements. */
  public static boolean valid(ChaosProfile profile, String machineProfile) {
    return profile == ChaosProfile.FULL
        && machineProfile != null
        && DEDICATED_PROFILES.contains(machineProfile.strip());
  }

  /** An invariant: PASS or FAIL. */
  public static Verdict invariant(boolean pass) {
    return pass ? Verdict.PASS : Verdict.FAIL;
  }

  /**
   * A measurement: INVALID outside a valid run; in a valid run FAIL when it was not measured, else
   * PASS or FAIL.
   */
  public static Verdict measurement(boolean validRun, boolean measured, boolean pass) {
    if (!validRun) {
      return Verdict.INVALID;
    }
    return measured && pass ? Verdict.PASS : Verdict.FAIL;
  }

  /** The verdict of a scenario from its rows. */
  public static Verdict scenario(Collection<Check> checks) {
    boolean invalid = false;
    for (Check c : checks) {
      if (c.verdict() == Verdict.FAIL) {
        return Verdict.FAIL;
      }
      invalid |= c.verdict() == Verdict.INVALID;
    }
    return invalid ? Verdict.INVALID : Verdict.PASS;
  }

  /** The note of an INVALID measurement. */
  public static String invalidNote(ChaosOptions options) {
    return "profile "
        + options.profile().id()
        + " on machine profile '"
        + options.machineProfile()
        + "': a reference value; PASS/FAIL needs profile full on "
        + String.join(", ", DEDICATED_PROFILES);
  }
}
