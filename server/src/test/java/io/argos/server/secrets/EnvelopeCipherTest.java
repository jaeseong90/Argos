/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:SEC-52")
class EnvelopeCipherTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String AAD = EnvelopeCipher.aad("notification_channels", "config_enc", "c1");

  private static MasterKey newKey() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return MasterKey.of(bytes);
  }

  @Test
  @DisplayName(
      "T-SEC-52a an envelope is v1:<kid>:<nonce>:<ct||tag>, AES-256-GCM, without the plaintext,"
          + " with a new nonce every time")
  void format() throws Exception {
    MasterKey key = newKey();
    EnvelopeCipher cipher = new EnvelopeCipher(new MasterKeyRing(key, null));
    String secret = "https://hooks.example.com/services/T000/B000/XXXXXXXX";

    String envelope = cipher.encryptString(secret, AAD);

    String[] parts = envelope.split(":", -1);
    assertThat(parts).hasSize(4);
    assertThat(parts[0]).isEqualTo("v1");
    assertThat(parts[1]).isEqualTo(key.kid()).matches("[0-9a-f]{16}");
    assertThat(Base64.getUrlDecoder().decode(parts[2])).hasSize(12);
    // ciphertext of the same length as the plaintext plus a 16-byte tag
    assertThat(Base64.getUrlDecoder().decode(parts[3]))
        .hasSize(secret.getBytes(StandardCharsets.UTF_8).length + 16);
    assertThat(envelope).doesNotContain("hooks.example.com").doesNotContain("XXXXXXXX");
    assertThat(cipher.decryptString(envelope, AAD)).isEqualTo(secret);

    Set<String> nonces = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      nonces.add(cipher.encryptString(secret, AAD).split(":", -1)[2]);
    }
    assertThat(nonces).as("CSPRNG nonce per encryption").hasSize(200);
  }

  @Test
  @DisplayName("T-SEC-52a the kid is SHA-256(key)[0:8] in hex (16 §8.2)")
  void kid() throws Exception {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    MasterKey key = MasterKey.of(bytes);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    assertThat(key.kid()).isEqualTo(HexFormat.of().formatHex(digest, 0, 8));
  }

  @Test
  @DisplayName(
      "T-SEC-52a an envelope moved to another row or column does not decrypt (AAD), nor a tampered"
          + " one")
  void aadAndTamper() {
    EnvelopeCipher cipher = new EnvelopeCipher(new MasterKeyRing(newKey(), null));
    String envelope = cipher.encryptString("secret value", AAD);

    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    envelope, EnvelopeCipher.aad("notification_channels", "config_enc", "c2")))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class,
            e -> {
              assertThat(e.reason()).isEqualTo("authentication_failed");
              assertThat(e.errorCode()).isEqualTo(ErrorCode.SECRET_UNAVAILABLE);
              assertThat(e.errorCode().status()).isEqualTo(409);
            });
    String[] parts = envelope.split(":", -1);
    byte[] ct = Base64.getUrlDecoder().decode(parts[3]);
    ct[0] ^= 1;
    String tampered =
        parts[0]
            + ":"
            + parts[1]
            + ":"
            + parts[2]
            + ":"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(ct);
    assertThatThrownBy(() -> cipher.decrypt(tampered, AAD))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo("authentication_failed"));
    assertThatThrownBy(() -> cipher.decrypt("v1:zz:abc", AAD))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class, e -> assertThat(e.reason()).isEqualTo("malformed"));
  }

  @Test
  @DisplayName(
      "T-SEC-52 an envelope of the previous key still decrypts during a rotation; new envelopes use"
          + " the current key")
  void previousKey() {
    MasterKey oldKey = newKey();
    String written = new EnvelopeCipher(new MasterKeyRing(oldKey, null)).encryptString("v", AAD);
    MasterKey current = newKey();
    EnvelopeCipher rotated = new EnvelopeCipher(new MasterKeyRing(current, oldKey));

    assertThat(rotated.decryptString(written, AAD)).isEqualTo("v");
    assertThat(EnvelopeCipher.kidOf(rotated.encryptString("v", AAD))).isEqualTo(current.kid());

    EnvelopeCipher withoutOld = new EnvelopeCipher(new MasterKeyRing(current, null));
    assertThatThrownBy(() -> withoutOld.decrypt(written, AAD))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class, e -> assertThat(e.reason()).isEqualTo("unknown_key"));
  }

  @Test
  @DisplayName("T-SEC-52a without a master key nothing is encrypted: 409 secret_unavailable")
  void noKey() {
    EnvelopeCipher cipher = new EnvelopeCipher(new MasterKeyRing(null, null));
    assertThat(cipher.available()).isFalse();
    assertThatThrownBy(() -> cipher.encryptString("v", AAD))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class,
            e -> {
              assertThat(e.reason()).isEqualTo("no_master_key");
              assertThat(e.getMessage()).isEqualTo("secret_unavailable");
            });
  }

  @Test
  @DisplayName("T-SEC-52a a closed key is unusable (zeroed at shutdown)")
  void closedKey() {
    MasterKey key = newKey();
    MasterKeyRing ring = new MasterKeyRing(key, null);
    EnvelopeCipher cipher = new EnvelopeCipher(ring);
    ring.destroy();
    assertThatThrownBy(() -> cipher.encryptString("v", AAD))
        .isInstanceOf(SecretUnavailableException.class);
  }
}
