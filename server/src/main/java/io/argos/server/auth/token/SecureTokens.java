/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random tokens, their hashes and constant-time comparison (16 §7.1, §7.6, §7.8): refresh tokens,
 * CSRF tokens and the setup token are 32 random bytes as 43 base64url characters; the database
 * keeps the hex SHA-256 of a refresh token only; every comparison of a presented secret is {@link
 * MessageDigest#isEqual} (constant time for equal lengths).
 */
public final class SecureTokens {

  /** Random bytes of a token. */
  public static final int TOKEN_BYTES = 32;

  /** Characters of a token: 32 bytes as base64url without padding. */
  public static final int TOKEN_LENGTH = 43;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private SecureTokens() {}

  /** A new token: {@value #TOKEN_BYTES} random bytes, base64url without padding. */
  public static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return BASE64URL.encodeToString(bytes);
  }

  /** Whether {@code value} has the shape of a token (43 base64url characters). */
  public static boolean looksLikeToken(String value) {
    if (value == null || value.length() != TOKEN_LENGTH) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isBase64Url(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** The lower-case hex SHA-256 of the UTF-8 bytes of {@code value} (64 characters). */
  public static String sha256Hex(String value) {
    return HexFormat.of().formatHex(sha256(value));
  }

  /** The SHA-256 of the UTF-8 bytes of {@code value}. */
  public static byte[] sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  /** The SHA-256 of {@code bytes} as base64url without padding. */
  public static String sha256Base64Url(byte[] bytes) {
    return BASE64URL.encodeToString(sha256(bytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Whether two secrets are equal, in time independent of where they differ (both are hashed first,
   * so the comparison also does not depend on their lengths). Null equals nothing.
   */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(sha256(a), sha256(b));
  }

  /** Whether {@code c} is a base64url character. */
  static boolean isBase64Url(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_';
  }
}
