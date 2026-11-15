/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.auth.password.PasswordPolicy.Reason;
import io.argos.server.config.ArgosProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The password policy (16 §7.9, SEC-48): length in code points, no trimming, no composition rules,
 * denylist, user name, product name; and the lockout durations behind it. The hashes are tested in
 * {@code PasswordPolicySlowIT} (src/integrationTest: bcrypt at cost 12 is slow).
 */
@Tag("req:SEC-48")
class PasswordPolicyTest {

  private static final String LETTERS = "qwzxkvjpqwzxkvjpqwzxkvjp";

  private static PasswordPolicy policy;

  @TempDir Path dir;

  @BeforeAll
  static void load() {
    ArgosProperties properties = AuthTestProperties.defaults();
    policy = new PasswordPolicy(properties, new PasswordDenylist(properties));
  }

  /** {@code n} letters that are no common password. */
  private static String letters(int n) {
    return LETTERS.repeat(n / LETTERS.length() + 1).substring(0, n);
  }

  @Test
  @DisplayName("T-SEC-48a 11 is refused, 12 allowed, 128 allowed, 129 refused")
  void lengthBoundaries() {
    assertThat(policy.violations(letters(11), "someone")).containsExactly(Reason.TOO_SHORT);
    assertThat(policy.violations(letters(12), "someone")).isEmpty();
    assertThat(policy.violations(letters(128), "someone")).isEmpty();
    assertThat(policy.violations(letters(129), "someone")).containsExactly(Reason.TOO_LONG);
    assertThat(policy.violations(null, "someone")).containsExactly(Reason.TOO_SHORT);
    assertThat(policy.violations("", "someone")).containsExactly(Reason.TOO_SHORT);
  }

  @Test
  @DisplayName("T-SEC-48a length counts Unicode code points, not chars or bytes")
  void codePoints() {
    String emoji = new String(Character.toChars(0x1F510));
    assertThat(emoji.repeat(12).length()).isEqualTo(24);
    assertThat(policy.violations(emoji.repeat(12), "someone")).isEmpty();
    assertThat(policy.violations(emoji.repeat(11), "someone")).containsExactly(Reason.TOO_SHORT);
    assertThat(policy.violations(emoji.repeat(128), "someone")).isEmpty();
    assertThat(policy.violations(emoji.repeat(129), "someone")).containsExactly(Reason.TOO_LONG);
    assertThat(policy.violations("한글비밀번호테스트문장들", "someone")).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-48a blanks count and are not trimmed; no composition rules")
  void noTrimmingNoComposition() {
    assertThat(policy.violations("  " + letters(9), "someone")).containsExactly(Reason.TOO_SHORT);
    assertThat(policy.violations("  " + letters(10), "someone")).isEmpty();
    assertThat(policy.violations("            ", "someone")).isEmpty();
    assertThat(policy.violations("111222333444555", "someone")).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-48a the denylist holds the 10,000 list and ignores case")
  void denylist() {
    assertThat(policy.violations("unbelievable", "someone")).containsExactly(Reason.DENYLISTED);
    assertThat(policy.violations("UnBelievable", "someone")).containsExactly(Reason.DENYLISTED);
    assertThat(policy.violations("password", "someone"))
        .containsExactly(Reason.TOO_SHORT, Reason.DENYLISTED);
    assertThat(policy.violations("unbelievable1", "someone")).isEmpty();
    PasswordDenylist list = new PasswordDenylist(AuthTestProperties.defaults());
    assertThat(list.bundledEntries()).isEqualTo(PasswordDenylist.BUNDLED_ENTRIES);
    assertThat(list.size()).isEqualTo(PasswordDenylist.BUNDLED_ENTRIES);
  }

  @Test
  @DisplayName("T-SEC-48a the operator's list adds to the bundled one")
  void operatorList() throws Exception {
    Path file = dir.resolve("banned.txt");
    Files.write(file, List.of("# comment", "", "Corporate-Secret-Word"));
    ArgosProperties properties =
        AuthTestProperties.of(Map.of("argos.auth.password.banned-list-file", file.toString()));
    PasswordPolicy withList = new PasswordPolicy(properties, new PasswordDenylist(properties));
    assertThat(withList.violations("corporate-secret-word", "someone"))
        .containsExactly(Reason.DENYLISTED);
    assertThat(withList.violations("unbelievable", "someone")).containsExactly(Reason.DENYLISTED);
  }

  @Test
  @DisplayName("T-SEC-48a a password may not contain the user name or argos, ignoring case")
  void containsNames() {
    assertThat(policy.violations("xxOPERATOR-onexx", "operator-one"))
        .containsExactly(Reason.CONTAINS_USERNAME);
    assertThat(policy.violations("xxxxxArGoSxxxxx", "someone"))
        .containsExactly(Reason.CONTAINS_PRODUCT_NAME);
    assertThat(policy.violations(letters(12) + "ab", "ab")).isEmpty();
    assertThat(policy.violations(letters(12), null)).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-48a a violation is 422 password_policy with one reason code per rule")
  void errorBody() {
    assertThatThrownBy(() -> policy.require("argos", "argos", "new"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY);
              assertThat(e.errorCode().status()).isEqualTo(422);
              assertThat(e.details())
                  .extracting(d -> d.field() + ":" + d.reason())
                  .containsExactly(
                      "new:too_short", "new:contains_username", "new:contains_product_name");
              assertThat(e.clientMessage()).doesNotContain("argos");
            });
  }

  @Test
  @DisplayName("T-SEC-48a lockout lasts 15, 30, then 60 minutes at most")
  void lockoutDurations() {
    LockoutPolicy lockout = new LockoutPolicy(AuthTestProperties.defaults());
    assertThat(lockout.failures()).isEqualTo(10);
    assertThat(lockout.duration(0).toMinutes()).isEqualTo(15);
    assertThat(lockout.duration(1).toMinutes()).isEqualTo(30);
    assertThat(lockout.duration(2).toMinutes()).isEqualTo(60);
    assertThat(lockout.duration(3).toMinutes()).isEqualTo(60);
    assertThat(lockout.duration(1000).toMinutes()).isEqualTo(60);
  }
}
