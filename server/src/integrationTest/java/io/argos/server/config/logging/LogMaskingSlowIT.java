/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The slow part of {@code LogMaskingTest} (12 §7.1, 17 §11.3, ADR-0010 C-94): masking inputs of
 * several megabytes takes about a second, beyond the 1 s of a unit test (18 §2.2), so the test
 * lives here, unchanged, with the helpers of {@code LogMaskingTest} it uses.
 */
@Tag("req:SEC-06")
@Tag("slow")
class LogMaskingSlowIT {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final String DASHES = "-----";

  static String secret() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return "s" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * A PEM armour line ({@code kind} is {@code BEGIN} or {@code END}), assembled at run time, so the
   * source of this class holds no armour that secret scanners report as a private key.
   */
  private static String armour(String kind, String label) {
    return DASHES + kind + " " + label + DASHES;
  }

  /**
   * {@link LogMasking#mask(String)}, checking on every input of this class that masking is
   * idempotent: masking the masked text again changes nothing.
   */
  private static String mask(String text) {
    String once = LogMasking.mask(text);
    assertThat(LogMasking.mask(once)).as("mask(mask(x)) equals mask(x)").isEqualTo(once);
    return once;
  }

  @Test
  @DisplayName(
      "T-SEC-06e many PEM begin lines, with or without end lines, are masked in linear time")
  void pemManyBeginLines() {
    String s = secret();
    String begin = armour("BEGIN", "PRIVATE KEY");
    String end = armour("END", "PRIVATE KEY");
    // A scan that searched again from every begin line would take minutes on these inputs. Inputs
    // that the PEM rule leaves long are smaller: the other rules also read all of them.
    Duration limit = Duration.ofSeconds(5);

    // Begin lines of private keys without an end line: the first one masks the rest.
    String unterminated = (begin + s + "\n").repeat(100_000);
    assertThat(assertTimeoutPreemptively(limit, () -> mask(unterminated)))
        .isEqualTo(begin + LogMasking.MASK);
    // End lines of another label only: the first begin line masks the rest.
    String otherEnds = (begin + s + armour("END", "RSA PRIVATE KEY")).repeat(50_000);
    assertThat(assertTimeoutPreemptively(limit, () -> mask(otherEnds)))
        .isEqualTo(begin + LogMasking.MASK);
    // Every block closed: each is masked.
    String closed = (begin + s + end).repeat(20_000);
    assertThat(assertTimeoutPreemptively(limit, () -> mask(closed)))
        .isEqualTo((begin + LogMasking.MASK + end).repeat(20_000));

    // Begin lines that are not those of a private key, then one key at the end.
    String tail = begin + "\n" + s + "\n" + end;
    String noDashes = (DASHES + "BEGIN PRIVATE KEY ").repeat(40_000);
    String publicKeys = (armour("BEGIN", "PUBLIC KEY") + "x").repeat(40_000);
    for (String prefix : List.of(noDashes, publicKeys)) {
      assertThat(assertTimeoutPreemptively(limit, () -> mask(prefix + tail)))
          .isEqualTo(prefix + begin + LogMasking.MASK + end);
    }
    // Labels longer than any private key label: read up to the bound, no search for an end line.
    String longLabels =
        (DASHES + "BEGIN " + "A".repeat(200) + " PRIVATE KEY" + DASHES).repeat(4_000);
    assertThat(assertTimeoutPreemptively(limit, () -> mask(longLabels + tail)))
        .endsWith(begin + LogMasking.MASK + end)
        .doesNotContain(s);
  }
}
