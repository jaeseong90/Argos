/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.Sensitive;
import io.argos.server.config.ArgosProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The password policy (16 §7.9, SEC-48, NIST SP 800-63B): length between {@code
 * argos.auth.password.min-length} (12) and {@code max-length} (128) Unicode code points, counted on
 * the password exactly as given (no trimming, no normalisation); not on the denylist; not
 * containing the user name or {@code argos} (case-insensitive). No composition rules.
 *
 * <p>A violation is 422 {@code password_policy} with one detail per broken rule, field {@code
 * password} (or the caller's field name), reason {@link Reason#code()}.
 */
@Component
public class PasswordPolicy {

  /** The product name a password must not contain (16 §7.9). */
  static final String PRODUCT_NAME = "argos";

  /** User names shorter than this are not searched for in the password. */
  static final int MIN_USERNAME_LENGTH_CHECKED = 3;

  /** A broken rule. */
  public enum Reason {
    /** Fewer code points than the minimum. */
    TOO_SHORT,
    /** More code points than the maximum. */
    TOO_LONG,
    /** One of the banned (common) passwords. */
    DENYLISTED,
    /** Contains the user name. */
    CONTAINS_USERNAME,
    /** Contains {@code argos}. */
    CONTAINS_PRODUCT_NAME;

    /** The detail reason: {@code too_short}, {@code denylisted}, ... */
    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final int minLength;
  private final int maxLength;
  private final PasswordDenylist denylist;

  public PasswordPolicy(ArgosProperties properties, PasswordDenylist denylist) {
    this.minLength = properties.auth().password().minLength();
    this.maxLength = properties.auth().password().maxLength();
    this.denylist = denylist;
  }

  /** The minimum length in code points. */
  public int minLength() {
    return minLength;
  }

  /** The maximum length in code points. */
  public int maxLength() {
    return maxLength;
  }

  /**
   * The rules {@code password} breaks for {@code username}; empty when it is acceptable.
   *
   * @param username the account's name, or null when unknown
   */
  public List<Reason> violations(@Sensitive String password, String username) {
    List<Reason> out = new ArrayList<>();
    if (password == null) {
      out.add(Reason.TOO_SHORT);
      return out;
    }
    int length = password.codePointCount(0, password.length());
    if (length < minLength) {
      out.add(Reason.TOO_SHORT);
    }
    if (length > maxLength) {
      out.add(Reason.TOO_LONG);
      // Longer input is not inspected further.
      return out;
    }
    if (denylist.contains(password)) {
      out.add(Reason.DENYLISTED);
    }
    String lower = password.toLowerCase(Locale.ROOT);
    if (username != null
        && username.length() >= MIN_USERNAME_LENGTH_CHECKED
        && lower.contains(username.toLowerCase(Locale.ROOT))) {
      out.add(Reason.CONTAINS_USERNAME);
    }
    if (lower.contains(PRODUCT_NAME)) {
      out.add(Reason.CONTAINS_PRODUCT_NAME);
    }
    return out;
  }

  /**
   * Checks a new password.
   *
   * @param field the request field the password came from (error detail)
   * @throws ApiException 422 {@code password_policy} with a detail per broken rule
   */
  public void require(@Sensitive String password, String username, String field) {
    List<Reason> violations = violations(password, username);
    if (!violations.isEmpty()) {
      ApiException error =
          new ApiException(
              ErrorCode.PASSWORD_POLICY,
              "The password must be "
                  + minLength
                  + " to "
                  + maxLength
                  + " characters, not a common password and not contain the user name or the"
                  + " product name");
      violations.forEach(v -> error.detail(field, v.code()));
      throw error;
    }
  }
}
