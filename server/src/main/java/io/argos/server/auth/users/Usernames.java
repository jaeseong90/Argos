/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.users;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * The form of a new user name ({@code users.username VARCHAR(64)}): 1 to 64 letters, digits, {@code
 * .}, {@code _}, {@code @}, {@code +} and {@code -}, starting with a letter or digit. No blanks or
 * control characters: the name appears in logs and audit records.
 */
public final class Usernames {

  /** Longest name in code points. */
  public static final int MAX_LENGTH = 64;

  private static final Pattern FORM = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._@+-]*+");

  private Usernames() {}

  /** Whether {@code name} has the form of a user name. */
  public static boolean valid(String name) {
    return name != null
        && !name.isEmpty()
        && name.length() <= MAX_LENGTH * 2
        && name.codePointCount(0, name.length()) <= MAX_LENGTH
        && FORM.matcher(name).matches();
  }

  /**
   * Checks a new user name.
   *
   * @throws ApiException 400 {@code invalid_request} with a detail for {@code field}
   */
  public static void require(String name, String field) {
    if (!valid(name)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST)
          .detail(field, name == null || name.isEmpty() ? "required" : "invalid");
    }
  }
}
