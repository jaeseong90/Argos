/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.channel;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code webhook_signing_keys} (V008): the key as an envelope string, never plaintext.
 * {@link #toString()} leaves the envelope out.
 *
 * @param channelId the channel
 * @param keyId 16 hex characters
 * @param keyEnc the envelope of the signing secret
 * @param createdAt creation
 * @param retireAfter end of the rotation grace, null for the current key
 */
public record SigningKeyRow(
    UUID channelId, String keyId, String keyEnc, Instant createdAt, Instant retireAfter) {

  @Override
  public String toString() {
    return "SigningKeyRow[channelId=" + channelId + ", keyId=" + keyId + "]";
  }
}
