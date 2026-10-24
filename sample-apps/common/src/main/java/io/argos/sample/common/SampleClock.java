/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Business clock of the sample apps: fixed when {@code -Dsample.fixed-clock} (or
 * SAMPLE_FIXED_CLOCK) is set, for deterministic E2E data (18 §4.4); the system UTC clock otherwise.
 * Agent timestamps never use it.
 */
public final class SampleClock {

  private SampleClock() {}

  public static Clock of(SampleConfig config) {
    return config.fixedClock() == null
        ? Clock.systemUTC()
        : Clock.fixed(config.fixedClock(), ZoneOffset.UTC);
  }
}
