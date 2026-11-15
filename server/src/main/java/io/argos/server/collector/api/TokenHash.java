/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.SecureTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The lookup key of an agent token: the lower-case hex SHA-256 of the token's UTF-8 bytes (16 §2.1,
 * SEC-02), as stored in PostgreSQL {@code agent_tokens.token_hash CHAR(64)}. The token itself is
 * never stored, logged or compared directly.
 *
 * @param hex 64 lower-case hex characters
 */
public record TokenHash(String hex) {

  /** Token text: {@code argos_} and 32 random bytes as 43 base64url characters (16 §2.1). */
  public static final Pattern TOKEN_FORMAT = Pattern.compile("argos_[A-Za-z0-9_-]{43}");

  /** Characters of a token. */
  public static final int TOKEN_LENGTH = 49;

  /**
   * Characters of the display prefix ({@code agent_tokens.token_prefix CHAR(14)}): {@code argos_}
   * and the next 8 characters (16 §2.1). The only part of a token that may appear in a log line.
   */
  public static final int PREFIX_LENGTH = 14;

  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

  /** Validates the stored shape. */
  public TokenHash {
    Objects.requireNonNull(hex, "hex");
    if (!HEX.matcher(hex).matches()) {
      throw new IllegalArgumentException("A token hash is 64 lower-case hex characters");
    }
  }

  /** The hash of {@code token} (any text; check {@link #isWellFormed} first). */
  public static TokenHash of(@Sensitive String token) {
    return new TokenHash(SecureTokens.sha256Hex(token));
  }

  /** Whether {@code token} has the 16 §2.1 shape; {@code null} is not. */
  public static boolean isWellFormed(@Sensitive String token) {
    return token != null && token.length() == TOKEN_LENGTH && TOKEN_FORMAT.matcher(token).matches();
  }

  /**
   * The display prefix of a well-formed {@code token} (16 §2.1, {@value #PREFIX_LENGTH}
   * characters); {@code "argos_****"} for anything else, so that a malformed value is never echoed.
   */
  public static String displayPrefix(@Sensitive String token) {
    return isWellFormed(token) ? token.substring(0, PREFIX_LENGTH) : "argos_****";
  }

  /** Constant-time comparison of the two hashes ({@link MessageDigest#isEqual}, 16 §2.1). */
  public boolean matches(TokenHash other) {
    return other != null
        && MessageDigest.isEqual(
            hex.getBytes(StandardCharsets.US_ASCII), other.hex.getBytes(StandardCharsets.US_ASCII));
  }

  /** The first 12 hex characters only (the hash is not secret, but logs stay short). */
  @Override
  public String toString() {
    return "TokenHash[" + hex.substring(0, 12) + "...]";
  }
}
