/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.channel;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code notification_channels} (V001 + V008). {@code configEnc} is the envelope string of
 * the channel's secret configuration (16 §8.3), never plaintext; {@code configMasked} is the masked
 * JSON the API shows. {@link #toString()} leaves the envelope out.
 *
 * @param id channel id
 * @param name operator-given name
 * @param type {@code email}, {@code slack} or {@code webhook}
 * @param configEnc the envelope (empty for a deleted channel)
 * @param configMasked masked JSON
 * @param enabled whether deliveries go to it
 * @param status {@code ok} or {@code failing}
 * @param consecutiveFailures failed deliveries since the last success
 * @param lastSuccessAt last successful delivery
 * @param lastFailureAt last failed delivery
 * @param createdAt creation
 * @param updatedAt last change
 * @param signingKeys valid webhook signing keys (0, 1 or 2)
 */
public record ChannelRow(
    UUID id,
    String name,
    String type,
    String configEnc,
    String configMasked,
    boolean enabled,
    String status,
    int consecutiveFailures,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant createdAt,
    Instant updatedAt,
    int signingKeys) {

  @Override
  public String toString() {
    return "ChannelRow[id="
        + id
        + ", type="
        + type
        + ", enabled="
        + enabled
        + ", status="
        + status
        + "]";
  }
}
