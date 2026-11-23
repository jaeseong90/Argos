/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption of stored secrets (16 §8.3, SEC-52): AES-256-GCM under the master key, a new
 * 12-byte nonce from a CSPRNG for every encryption, a 128-bit tag, and the associated data {@code
 * <table>:<column>:<row id>} so that an envelope copied into another row or column does not
 * decrypt.
 *
 * <pre>
 * v1:&lt;kid 16 hex&gt;:&lt;base64url(nonce 12 B)&gt;:&lt;base64url(ciphertext || tag 16 B)&gt;
 * </pre>
 *
 * The key id is {@link MasterKey#kid()} ({@code SHA-256(key)[0:8]}, 16 §8.2: eight bytes, sixteen
 * hex characters). Decryption picks the current or the previous key by that id ({@link
 * MasterKeyRing#find}). Every failure is a {@link SecretUnavailableException} (409 {@code
 * secret_unavailable}) with a fixed reason; neither the plaintext nor the envelope is ever part of
 * an exception or a log line.
 */
@Component
public class EnvelopeCipher {

  /** The format version prefix. */
  public static final String VERSION = "v1";

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final Pattern KID = Pattern.compile("[0-9a-f]{16}");
  private static final Pattern AAD_PART = Pattern.compile("[a-z_]+");

  private final MasterKeyRing keys;
  private final SecureRandom random;

  @Autowired
  public EnvelopeCipher(MasterKeyRing keys) {
    this(keys, new SecureRandom());
  }

  EnvelopeCipher(MasterKeyRing keys, SecureRandom random) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.random = Objects.requireNonNull(random, "random");
  }

  /** Whether a master key is loaded (secrets can be stored). */
  public boolean available() {
    return keys.available();
  }

  /**
   * The associated data of a column value: {@code <table>:<column>:<row id>} (16 §8.3).
   *
   * @param table table name, lower snake case
   * @param column column name, lower snake case
   * @param rowId the row's identity (a UUID, or a composite like {@code <channel>/<key>})
   */
  public static String aad(String table, String column, String rowId) {
    if (!AAD_PART.matcher(table).matches() || !AAD_PART.matcher(column).matches()) {
      throw new IllegalArgumentException("table and column are lower snake case");
    }
    return table + ":" + column + ":" + Objects.requireNonNull(rowId, "rowId");
  }

  /**
   * Encrypts {@code plaintext} under the current key.
   *
   * @param aad {@link #aad} of the destination column and row
   * @return the envelope string
   * @throws SecretUnavailableException {@code no_master_key} when no key is loaded
   */
  public String encrypt(byte[] plaintext, String aad) {
    Objects.requireNonNull(plaintext, "plaintext");
    MasterKey key =
        keys.current().orElseThrow(() -> new SecretUnavailableException("no_master_key"));
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plaintext);
      Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
      return VERSION
          + ":"
          + key.kid()
          + ":"
          + b64.encodeToString(nonce)
          + ":"
          + b64.encodeToString(sealed);
    } catch (GeneralSecurityException | IllegalStateException e) {
      throw new SecretUnavailableException("encryption_failed");
    }
  }

  /** {@link #encrypt(byte[], String)} of a UTF-8 string. */
  public String encryptString(String plaintext, String aad) {
    return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
  }

  /**
   * Decrypts an envelope with the key it names (current or previous).
   *
   * @param envelope the stored envelope string
   * @param aad {@link #aad} of the column and row it was read from
   * @return the plaintext; the caller overwrites it when done where practical
   * @throws SecretUnavailableException {@code malformed}, {@code unknown_key} (the key of the
   *     envelope is not loaded, or no key at all) or {@code authentication_failed} (wrong key,
   *     tampered envelope or different associated data)
   */
  public byte[] decrypt(String envelope, String aad) {
    if (envelope == null) {
      throw new SecretUnavailableException("malformed");
    }
    String[] parts = envelope.split(":", -1);
    if (parts.length != 4 || !VERSION.equals(parts[0]) || !KID.matcher(parts[1]).matches()) {
      throw new SecretUnavailableException("malformed");
    }
    byte[] nonce;
    byte[] sealed;
    try {
      Base64.Decoder b64 = Base64.getUrlDecoder();
      nonce = b64.decode(parts[2]);
      sealed = b64.decode(parts[3]);
    } catch (IllegalArgumentException e) {
      throw new SecretUnavailableException("malformed");
    }
    if (nonce.length != NONCE_BYTES || sealed.length < TAG_BITS / 8) {
      throw new SecretUnavailableException("malformed");
    }
    MasterKey key =
        keys.find(parts[1]).orElseThrow(() -> new SecretUnavailableException("unknown_key"));
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(sealed);
    } catch (AEADBadTagException e) {
      throw new SecretUnavailableException("authentication_failed");
    } catch (GeneralSecurityException | IllegalStateException e) {
      throw new SecretUnavailableException("authentication_failed");
    }
  }

  /** {@link #decrypt} to a UTF-8 string. */
  public String decryptString(String envelope, String aad) {
    return new String(decrypt(envelope, aad), StandardCharsets.UTF_8);
  }

  /** The key id an envelope names, or null when it is not an envelope (key rotation reports). */
  public static String kidOf(String envelope) {
    if (envelope == null) {
      return null;
    }
    String[] parts = envelope.split(":", -1);
    return parts.length == 4 && VERSION.equals(parts[0]) && KID.matcher(parts[1]).matches()
        ? parts[1]
        : null;
  }
}
