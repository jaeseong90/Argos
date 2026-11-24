/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.SecretField;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.storage.postgres.alert.channel.ChannelRepository;
import io.argos.server.storage.postgres.alert.channel.ChannelRow;
import io.argos.server.storage.postgres.alert.channel.SigningKeyRow;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Encryption of the channel secrets (16 §8.3, §8.4, SEC-52): the configuration into {@code
 * notification_channels.config_enc} (AAD {@code notification_channels:config_enc:<id>}) and the
 * signing secrets into {@code webhook_signing_keys.key_enc} (AAD {@code
 * webhook_signing_keys:key_enc:<channel>/<key id>}), and the decryption of both for one send.
 */
@Component
public class ChannelVault {

  /** Bytes of a generated signing secret (16 §8.4: 32). */
  static final int SECRET_BYTES = 32;

  private final EnvelopeCipher cipher;
  private final ChannelRepository channels;
  private final SecureRandom random = new SecureRandom();

  public ChannelVault(EnvelopeCipher cipher, ChannelRepository channels) {
    this.cipher = cipher;
    this.channels = channels;
  }

  /** Whether secrets can be stored now (a master key is loaded). */
  public boolean available() {
    return cipher.available();
  }

  /** The envelope of a channel configuration. */
  public String encryptConfig(UUID channelId, ChannelConfig config) {
    return cipher.encryptString(config.toJson(), configAad(channelId));
  }

  /** The decrypted configuration of a stored channel. */
  public ChannelConfig decryptConfig(ChannelRow row) {
    if (row.configEnc() == null || row.configEnc().isEmpty()) {
      throw new SecretUnavailableException("malformed");
    }
    return ChannelConfig.fromJson(cipher.decryptString(row.configEnc(), configAad(row.id())));
  }

  /** A new signing secret: 32 random bytes, base64url (43 characters), shown once. */
  public String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The row of a new current signing key holding {@code secret}. */
  public SigningKeyRow newKey(UUID channelId, String secret, Instant now) {
    byte[] id = new byte[8];
    random.nextBytes(id);
    String keyId = HexFormat.of().formatHex(id);
    String envelope = cipher.encryptString(secret, keyAad(channelId, keyId));
    return new SigningKeyRow(channelId, keyId, envelope, now, null);
  }

  /** The channel with its decrypted configuration and valid signing secrets, for one send. */
  public NotificationChannel.OpenChannel open(ChannelRow row, Instant now) {
    ChannelType type =
        ChannelType.of(row.type()).orElseThrow(() -> new SecretUnavailableException("malformed"));
    ChannelConfig config = decryptConfig(row);
    List<String> secrets = new ArrayList<>();
    if (type == ChannelType.WEBHOOK) {
      for (SigningKeyRow key : channels.validKeys(row.id(), now)) {
        secrets.add(cipher.decryptString(key.keyEnc(), keyAad(row.id(), key.keyId())));
      }
    }
    return new NotificationChannel.OpenChannel(row.id(), row.name(), type, config, secrets);
  }

  static String configAad(UUID channelId) {
    return SecretField.CHANNEL_CONFIG.aad(channelId.toString());
  }

  static String keyAad(UUID channelId, String keyId) {
    return SecretField.WEBHOOK_SIGNING_KEY.aad(channelId + "/" + keyId);
  }
}
