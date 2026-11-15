/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import java.util.Objects;

/**
 * The result of verifying an access token: the claims, or why it was rejected.
 *
 * @param claims the claims of a valid token, else null
 * @param rejection why the token was rejected, else null
 */
public record TokenCheck(AccessTokenClaims claims, Rejection rejection) {

  /** Why a token was rejected. */
  public enum Rejection {
    /** Longer than {@link AccessTokenService#MAX_TOKEN_LENGTH} characters. */
    TOO_LARGE,
    /** Not three non-empty base64url segments, or not JSON objects. */
    MALFORMED,
    /**
     * {@code alg} is not {@code EdDSA} ({@code none}, {@code HS256}, ...) or {@code typ}/{@code
     * crit} is wrong.
     */
    UNSUPPORTED_ALGORITHM,
    /** {@code kid} names neither the current nor the previous key. */
    UNKNOWN_KEY,
    /** The signature does not verify. */
    BAD_SIGNATURE,
    /**
     * A required claim is missing or of the wrong type, or {@code exp} is not after {@code iat}.
     */
    INVALID_CLAIMS,
    /** {@code iat} or {@code nbf} lies in the future beyond the tolerance. */
    NOT_YET_VALID,
    /** {@code exp} has passed. */
    EXPIRED
  }

  public TokenCheck {
    if ((claims == null) == (rejection == null)) {
      throw new IllegalArgumentException("exactly one of claims and rejection");
    }
  }

  /** A valid token. */
  public static TokenCheck valid(AccessTokenClaims claims) {
    return new TokenCheck(Objects.requireNonNull(claims, "claims"), null);
  }

  /** A rejected token. */
  public static TokenCheck rejected(Rejection rejection) {
    return new TokenCheck(null, Objects.requireNonNull(rejection, "rejection"));
  }

  /** Whether the token is valid. */
  public boolean valid() {
    return claims != null;
  }
}
