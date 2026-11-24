/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The secret columns in one place (16 §8.3, WO-28): the associated data is the one the stores used
 * before ({@code <table>:<column>:<row id>}), so envelopes written earlier still open, a value
 * sealed for one row or column does not open for another, and the key id of an envelope is readable
 * without a key.
 */
@Tag("req:SEC-52")
@Tag("req:C-226")
class SecretFieldTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static MasterKey key() {
    byte[] b = new byte[MasterKey.LENGTH];
    RANDOM.nextBytes(b);
    return MasterKey.of(b);
  }

  @Test
  @DisplayName(
      "T-SEC-52 every secret column keeps the associated data of WO-25/WO-26 (table:column:row)")
  void associatedData() {
    UUID id = UUID.randomUUID();
    assertThat(SecretField.CHANNEL_CONFIG.aad(id.toString()))
        .isEqualTo(EnvelopeCipher.aad("notification_channels", "config_enc", id.toString()));
    assertThat(SecretField.WEBHOOK_SIGNING_KEY.aad(id + "/k1"))
        .isEqualTo(EnvelopeCipher.aad("webhook_signing_keys", "key_enc", id + "/k1"));
    assertThat(SecretField.COMMAND_RESULT.aad(id.toString()))
        .isEqualTo(EnvelopeCipher.aad("command_results", "body_enc", id.toString()));
    assertThat(SecretField.APP_USER_ID_SALT.aad("7")).isEqualTo("apps:user_id_salt_enc:7");
    assertThat(Arrays.stream(SecretField.values()).map(SecretField::qualifiedName).toList())
        .containsExactly(
            "notification_channels.config_enc",
            "webhook_signing_keys.key_enc",
            "command_results.body_enc",
            "apps.user_id_salt_enc");
  }

  @Test
  @DisplayName(
      "T-SEC-52 an envelope written with EnvelopeCipher and the old AAD opens through SecretField;"
          + " another row or column does not; the key id is read without the key")
  void roundTrip() {
    MasterKey k = key();
    EnvelopeCipher cipher = new EnvelopeCipher(new MasterKeyRing(k, null));
    UUID id = UUID.randomUUID();
    String legacy =
        cipher.encryptString(
            "{\"url\":\"secret\"}",
            EnvelopeCipher.aad("notification_channels", "config_enc", id.toString()));
    assertThat(SecretField.CHANNEL_CONFIG.decryptString(cipher, legacy, id.toString()))
        .isEqualTo("{\"url\":\"secret\"}");
    assertThatThrownBy(
            () -> SecretField.CHANNEL_CONFIG.decrypt(cipher, legacy, UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            SecretUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo("authentication_failed"));
    assertThatThrownBy(() -> SecretField.COMMAND_RESULT.decrypt(cipher, legacy, id.toString()))
        .isInstanceOf(SecretUnavailableException.class);

    byte[] salt = new byte[32];
    RANDOM.nextBytes(salt);
    String sealed = SecretField.APP_USER_ID_SALT.encrypt(cipher, salt, "42");
    byte[] stored = SecretField.stored(sealed);
    assertThat(new String(stored, StandardCharsets.US_ASCII)).isEqualTo(sealed);
    assertThat(SecretField.envelope(stored)).isEqualTo(sealed);
    assertThat(SecretField.envelope(null)).isNull();
    assertThat(SecretField.kidOf(sealed)).isEqualTo(k.kid());
    assertThat(SecretField.kidOf("not an envelope")).isNull();
    assertThat(SecretField.APP_USER_ID_SALT.decrypt(cipher, sealed, "42")).isEqualTo(salt);
  }
}
