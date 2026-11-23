/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One channel type's delivery (12 §5.5): formats a {@link ChannelMessage} and sends it to the
 * channel's destination. Implementations validate the destination again before every send (16 §8.1:
 * at every real send), throw {@link DestinationBlockedException} when it fails and {@link
 * DeliveryException} for every other failure.
 */
public interface NotificationChannel {

  /** The type this implementation sends. */
  ChannelType type();

  /**
   * Sends {@code message} to {@code channel}.
   *
   * @return the status code (HTTP status or SMTP reply) and duration of an accepted delivery
   * @throws DeliveryException when the destination did not accept it
   * @throws DestinationBlockedException when the destination fails validation now
   */
  Outcome send(OpenChannel channel, ChannelMessage message) throws DeliveryException;

  /** An accepted delivery. */
  record Outcome(Integer statusCode, long durationMs) {}

  /**
   * A channel with its decrypted configuration, alive for one send. {@link #toString()} hides the
   * secrets.
   *
   * @param id channel id
   * @param name channel name
   * @param type channel type
   * @param config decrypted configuration
   * @param signingSecrets valid webhook signing secrets, newest first
   */
  record OpenChannel(
      UUID id, String name, ChannelType type, ChannelConfig config, List<String> signingSecrets) {

    public OpenChannel {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(config, "config");
      signingSecrets = signingSecrets == null ? List.of() : List.copyOf(signingSecrets);
    }

    @Override
    public String toString() {
      return "OpenChannel[id=" + id + ", type=" + type + ", " + config + "]";
    }
  }
}
