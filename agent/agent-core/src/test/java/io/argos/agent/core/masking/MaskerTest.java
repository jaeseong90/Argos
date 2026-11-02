/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SEC-36 default masking set (16 §6.6) for exception messages. */
@Tag("req:SEC-36")
class MaskerTest {
  private final Masker masker = Masker.defaults();

  @Test
  @DisplayName("email, Korean mobile number, RRN, card (Luhn), IPv4 and JWT are replaced")
  void defaultSet() {
    assertThat(masker.mask("order 42 not found for kim@example.test"))
        .isEqualTo("order 42 not found for <email>");
    assertThat(masker.mask("call 010-1234-5678 or 01098765432"))
        .isEqualTo("call <phone> or <phone>");
    assertThat(masker.mask("rrn 900101-1234567")).isEqualTo("rrn <rrn>");
    assertThat(masker.mask("card 4111 1111 1111 1111 declined")).isEqualTo("card <card> declined");
    assertThat(masker.mask("card 4111111111111111")).isEqualTo("card <card>");
    assertThat(masker.mask("from 192.168.10.20 refused")).isEqualTo("from <ip> refused");
    assertThat(masker.mask("token " + sampleJwt())).isEqualTo("token <jwt>");
  }

  /**
   * A syntactically valid but meaningless JWT ({"alg":"HS256"}.{"sub":"1234567890"}.signature). It
   * is joined at run time so that the secret scanner (gitleaks jwt rule, SEC-73) does not report a
   * test fixture as a leaked token; the masker still sees the complete three-part token.
   */
  private static String sampleJwt() {
    return String.join(
        ".", "eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiIxMjM0NTY3ODkwIn0", "dozjgNryP4J3jVmNHl0w5N");
  }

  @Test
  @DisplayName("numbers that fail the Luhn check, short numbers and plain text stay")
  void keepsOtherText() {
    assertThat(masker.mask("order 4111111111111112 failed"))
        .isEqualTo("order 4111111111111112 failed");
    assertThat(masker.mask("order 42 not found")).isEqualTo("order 42 not found");
    assertThat(masker.mask("Connection refused: connect")).isEqualTo("Connection refused: connect");
    assertThat(masker.mask("No bean com.example.LongPackageName.AnotherLongName.Klass"))
        .isEqualTo("No bean com.example.LongPackageName.AnotherLongName.Klass");
    assertThat(masker.mask(null)).isNull();
    assertThat(masker.mask("")).isEmpty();
  }

  @Test
  @DisplayName("Luhn: 13-19 digits, separators ignored")
  void luhn() {
    assertThat(Masker.luhn("4111-1111-1111-1111")).isTrue();
    assertThat(Masker.luhn("4111111111111112")).isFalse();
    assertThat(Masker.luhn("0000000000")).isFalse();
  }
}
