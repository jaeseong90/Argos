/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.util.Locale;

/**
 * Where the generated time range lies, relative to the load time (WO-03F anchor-date rule: never
 * fixed dates, so the rows stay inside every retention whenever the test runs).
 */
public enum Placement {
  /**
   * The range ends at the start of the current minute: the latest data is a minute old, so the raw
   * live tail (last 2 minutes, 14 §3) and the 1 hour views have data.
   */
  RECENT,
  /**
   * The generator's default: a range shorter than a day is centred on the last UTC midnight at
   * least half a span ago (daily checks, midnight crossing, C-57); a longer one ends at the last
   * full hour.
   */
  AROUND_MIDNIGHT;

  /** The placement of a name ({@code recent}, {@code around_midnight}), case-insensitive. */
  public static Placement of(String name) {
    return valueOf(name.strip().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
