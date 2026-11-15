/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.SecureTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashes: {@code BCryptPasswordEncoder(12)} (16 §7.9, SEC-48, ADR-0010 C-108: bcrypt cost
 * 12 only), stored as {@code $2a$12$...}.
 *
 * <p>bcrypt reads at most 72 bytes and Spring's encoder refuses longer input, while the policy
 * allows 128 code points (up to 512 UTF-8 bytes). A password longer than 72 UTF-8 bytes is
 * therefore hashed as {@code "sha256:" + base64(SHA-256(password))} (51 bytes) before bcrypt, so
 * every character counts; shorter passwords go to bcrypt unchanged.
 *
 * <p>{@link #dummyCheck(String)} spends the time of one verification without a stored hash: a login
 * for an unknown or locked user takes as long as one for a known user (no user enumeration by
 * timing).
 */
@Component
public class PasswordHasher {

  /** bcrypt cost (log2 rounds). */
  public static final int COST = 12;

  /** Bytes bcrypt reads. */
  static final int BCRYPT_MAX_BYTES = 72;

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST, new SecureRandom());
  private final String dummyHash = encoder.encode(SecureTokens.newToken());

  /** The hash of a new password. */
  public String hash(@Sensitive String password) {
    return encoder.encode(bcryptInput(password));
  }

  /** Whether {@code password} matches {@code hash}; false for a malformed hash. */
  public boolean matches(@Sensitive String password, @Sensitive String hash) {
    if (password == null || hash == null || hash.isEmpty()) {
      return false;
    }
    try {
      return encoder.matches(bcryptInput(password), hash);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** One bcrypt verification against a random hash (always false). */
  public boolean dummyCheck(@Sensitive String password) {
    matches(password == null ? "" : password, dummyHash);
    return false;
  }

  /** Whether a stored hash has another algorithm or a lower cost and should be replaced. */
  public boolean needsRehash(@Sensitive String hash) {
    try {
      return hash == null || !hash.startsWith("$2") || encoder.upgradeEncoding(hash);
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /** The bytes bcrypt sees: the password, or its SHA-256 when longer than 72 UTF-8 bytes. */
  static String bcryptInput(String password) {
    byte[] utf8 = password.getBytes(StandardCharsets.UTF_8);
    if (utf8.length <= BCRYPT_MAX_BYTES) {
      return password;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(utf8);
      return "sha256:" + Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
