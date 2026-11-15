/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The generator profiles of the query test data (test/storage/generator, 18 §9): the same rates and
 * cardinalities as the full data set (500 TPS over 10 applications and 50 instances), a shorter
 * time span except for {@link #FULL}.
 */
public enum QueryDataProfile {
  /** 1/1000: about 43 minutes of xlog (about 1.3 million rows); functional integration tests. */
  TINY,
  /** 1/100: 7.2 hours of xlog (about 13 million rows); the CI bench (N-07, N-24 reference). */
  CI,
  /** 1/20: 36 hours of xlog (about 65 million rows); the local bench, quiet machine only. */
  LOCAL,
  /**
   * 1/1: 30 days of xlog (about 1.3 billion rows), 7 days of profile steps and 14 days of metrics,
   * the default retentions (13 §8); the data of the N-07 and N-24 targets ("on 30 days of data"),
   * measured on the target hardware only (WO-53). The load takes hours and needs the disk of a full
   * installation.
   */
  FULL;

  /** The generator's profile name ({@code --profile}). */
  public String generatorName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * The profile of a name ({@code tiny}, {@code ci}, {@code local}, {@code full}),
   * case-insensitive.
   *
   * @throws IllegalArgumentException for another name
   */
  public static QueryDataProfile of(String name) {
    String key = name.strip().toUpperCase(Locale.ROOT);
    for (QueryDataProfile p : values()) {
      if (p.name().equals(key)) {
        return p;
      }
    }
    throw new IllegalArgumentException(
        "Unknown query data profile '"
            + name.strip()
            + "', one of "
            + Arrays.stream(values())
                .map(QueryDataProfile::generatorName)
                .collect(Collectors.joining(", ")));
  }
}
