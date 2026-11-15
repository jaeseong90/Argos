/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The slow part of {@code PasswordPolicyTest} (16 §7.9, SEC-48): five bcrypt hashes and checks at
 * cost 12 take about two seconds, beyond the 1 s of a unit test (18 §2.2), so the test lives here,
 * unchanged.
 */
@Tag("req:SEC-48")
@Tag("slow")
class PasswordPolicySlowIT {

  private static final String LETTERS = "qwzxkvjpqwzxkvjpqwzxkvjp";

  /** {@code n} letters that are no common password. */
  private static String letters(int n) {
    return LETTERS.repeat(n / LETTERS.length() + 1).substring(0, n);
  }

  @Test
  @DisplayName(
      "T-SEC-48a bcrypt cost 12 ($2a$12$), every character of a long password counts, a malformed"
          + " hash matches nothing")
  void hashes() {
    PasswordHasher hasher = new PasswordHasher();
    String password = letters(20);
    String hash = hasher.hash(password);
    assertThat(hash).startsWith("$2a$12$").hasSize(60);
    assertThat(hasher.matches(password, hash)).isTrue();
    assertThat(hasher.matches(password + "x", hash)).isFalse();
    assertThat(hasher.needsRehash(hash)).isFalse();
    assertThat(hasher.needsRehash("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"))
        .isTrue();
    assertThat(hasher.needsRehash("plain")).isTrue();

    String longPassword = letters(127) + "a";
    String otherEnd = letters(127) + "b";
    String longHash = hasher.hash(longPassword);
    assertThat(hasher.matches(longPassword, longHash)).isTrue();
    assertThat(hasher.matches(otherEnd, longHash)).isFalse();

    assertThat(hasher.matches(password, "not-a-hash")).isFalse();
    assertThat(hasher.matches(null, hash)).isFalse();
    assertThat(hasher.matches(password, null)).isFalse();
    assertThat(hasher.dummyCheck(password)).isFalse();
    assertThat(hasher.dummyCheck(null)).isFalse();
  }
}
