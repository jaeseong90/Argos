/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The server master key (16 §8.2): exactly 32 bytes, the AES-256 key of every envelope ({@link
 * EnvelopeCipher}).
 *
 * <p>Accepted text forms (one line, surrounding white space ignored):
 *
 * <ul>
 *   <li>64 hexadecimal characters, as {@code argos-env-init.sh} generates it (17 §3, {@code
 *       secrets/master_key});
 *   <li>base64 or base64url of 32 bytes, with or without padding (16 §8.2 {@code
 *       ARGOS_MASTER_KEY}).
 * </ul>
 *
 * The key id is {@code SHA-256(key)[0:8]} in hex (16 §8.2), written into every envelope so that a
 * rotation can tell which key decrypts it. The key bytes are kept in one array, never logged and
 * never in {@link #toString()}; {@link #close()} overwrites them with zeros.
 */
public final class MasterKey implements AutoCloseable {

  /** Key length in bytes (AES-256). */
  public static final int LENGTH = 32;

  private final byte[] key;
  private final String kid;
  private volatile boolean closed;

  private MasterKey(byte[] key) {
    this.key = key;
    this.kid = keyId(key);
  }

  /**
   * Parses a key from its text form.
   *
   * @throws IllegalArgumentException with a fixed reason (never the text) when it is not 32 bytes
   *     in one of the accepted forms
   */
  public static MasterKey parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("the master key is empty");
    }
    String value = text.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("the master key is empty");
    }
    byte[] bytes = decode(value);
    if (bytes == null || bytes.length != LENGTH) {
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
      }
      throw new IllegalArgumentException(
          "the master key is not 32 bytes as 64 hex characters or base64");
    }
    return new MasterKey(bytes);
  }

  /** A key from raw bytes (copied); for tests and key generation. */
  public static MasterKey of(byte[] bytes) {
    if (bytes == null || bytes.length != LENGTH) {
      throw new IllegalArgumentException("the master key is not 32 bytes");
    }
    return new MasterKey(bytes.clone());
  }

  /** {@code SHA-256(key)[0:8]} as 16 lower-case hex characters (16 §8.2). */
  public String kid() {
    return kid;
  }

  /**
   * The AES key for one operation. The caller does not keep it beyond the operation.
   *
   * @throws IllegalStateException after {@link #close()}
   */
  SecretKey secretKey() {
    if (closed) {
      throw new IllegalStateException("the master key is closed");
    }
    return new SecretKeySpec(key, "AES");
  }

  /** Overwrites the key bytes with zeros; the key is unusable afterwards. */
  @Override
  public void close() {
    closed = true;
    Arrays.fill(key, (byte) 0);
  }

  /** Never the key: its id only. */
  @Override
  public String toString() {
    return "MasterKey[kid=" + kid + "]";
  }

  private static byte[] decode(String value) {
    if (value.length() == 2 * LENGTH && value.chars().allMatch(MasterKey::isHex)) {
      return HexFormat.of().parseHex(value);
    }
    String b64 = value;
    // base64url and base64 differ in two characters; accept both, with or without padding.
    b64 = b64.replace('-', '+').replace('_', '/');
    while (b64.length() % 4 != 0) {
      b64 = b64 + "=";
    }
    try {
      return Base64.getDecoder().decode(b64.getBytes(StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isHex(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static String keyId(byte[] key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
